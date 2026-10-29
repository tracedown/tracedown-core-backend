package dev.tracedown.notifications.templates

import kotlinx.serialization.json.*
import java.util.UUID

/**
 * A rendered notification ready for delivery.
 */
data class RenderedNotification(
    val text: String,
    val callIndex: Int,
    val trigger: String,
    val scope: String?,
)

/**
 * Builds rendered notifications from a probe result's actions.notifications array.
 *
 * Single-pass design: receives the full raw result once, groups notification events
 * by (callIndex, trigger), resolves templates, walks the result JSON for runtime vars,
 * and returns baked plaintext for each group.
 */
object NotificationBuilder {

    /**
     * Builds all rendered notifications from a raw probe result.
     *
     * @param rawResult the full Lace ProbeResult JSON
     * @param staticVars pre-resolved context vars (w.name, p.name, s.name, s.schedule, etc.)
     * @param orgId the organization ID for template resolution
     * @param projectId the project ID for template resolution
     * @return list of rendered notifications, one per (callIndex, trigger) group
     */
    fun buildNotifications(
        rawResult: JsonObject,
        staticVars: Map<String, String>,
        orgId: UUID,
        projectId: UUID,
    ): List<RenderedNotification> {
        val actions = rawResult["actions"]?.jsonObject ?: return emptyList()
        val notifications = actions["notifications"]?.jsonArray ?: return emptyList()
        if (notifications.isEmpty()) return emptyList()

        val calls = rawResult["calls"]?.jsonArray

        // Group by (callIndex, trigger)
        data class GroupKey(val callIndex: Int, val trigger: String)

        val groups = notifications.groupBy { event ->
            val obj = event.jsonObject
            GroupKey(
                callIndex = obj["callIndex"]?.jsonPrimitive?.intOrNull ?: 0,
                trigger = obj["trigger"]?.jsonPrimitive?.content ?: "expect",
            )
        }

        return groups.map { (key, events) ->
            val firstEvent = events.first().jsonObject
            val notificationObj = firstEvent["notification"]?.jsonObject
            val tag = notificationObj?.get("tag")?.jsonPrimitive?.content ?: "structured"
            val scope = firstEvent["scope"]?.jsonPrimitive?.contentOrNull

            // Resolve template
            val template = resolveTemplate(tag, notificationObj, key.trigger, orgId, projectId)

            // Build runtime vars from the result JSON, plus ${conditions} — the
            // list of EVERY failed scope in this group (not just the first), so a
            // multi-scope assertion reports all its failures in one message.
            val runtimeVars = buildRuntimeVars(key.callIndex, key.trigger, scope, notificationObj, calls, rawResult)
            val allVars = staticVars + runtimeVars + ("conditions" to buildConditions(key.trigger, events))

            val text = TemplateRenderer.render(template, allVars)

            RenderedNotification(
                text = text,
                callIndex = key.callIndex,
                trigger = key.trigger,
                scope = scope,
            )
        }
    }

    private fun resolveTemplate(
        tag: String,
        notificationObj: JsonObject?,
        trigger: String,
        orgId: UUID,
        projectId: UUID,
    ): String {
        // A named template always wins (user-defined for this org/project).
        if (tag == "template") {
            val name = notificationObj?.get("name")?.jsonPrimitive?.contentOrNull
            return name?.let { TemplateResolver.resolveByName(orgId, projectId, it) }
                ?: TemplateResolver.defaultTemplate(trigger)
        }
        // Recovery is composed by the platform: the extension only signals the
        // transition, so ignore any inline text and use the default recovery
        // template (which reports downtime).
        if (trigger == "recovered") return TemplateResolver.defaultTemplate(trigger)
        // Payload field differs by tag (spec §12.1): text carries `value`,
        // structured carries a `data` object handled via runtime vars.
        return when (tag) {
            "text" -> notificationObj?.get("value")?.jsonPrimitive?.contentOrNull
                ?: TemplateResolver.defaultTemplate(trigger)
            else -> TemplateResolver.defaultTemplate(trigger)
        }
    }

    private fun buildRuntimeVars(
        callIndex: Int,
        trigger: String,
        scope: String?,
        notificationObj: JsonObject?,
        calls: JsonArray?,
        rawResult: JsonObject,
    ): Map<String, String> {
        val vars = mutableMapOf<String, String>()

        vars["trigger"] = trigger
        vars["callIndex"] = callIndex.toString()
        if (scope != null) vars["scope"] = scope
        vars["status"] = rawResult["outcome"]?.jsonPrimitive?.content ?: ""

        // Extract from calls[callIndex]
        val call = calls?.getOrNull(callIndex) as? JsonObject
        if (call != null) {
            val request = call["request"] as? JsonObject
            val response = call["response"] as? JsonObject
            vars["url"] = request?.get("url")?.jsonPrimitive?.content ?: ""
            vars["ms"] = response?.get("responseTimeMs")?.jsonPrimitive?.content
                ?: rawResult["elapsedMs"]?.jsonPrimitive?.content ?: ""
        } else {
            vars["url"] = ""
            vars["ms"] = rawResult["elapsedMs"]?.jsonPrimitive?.content ?: ""
        }

        // Expected/actual: structured payloads carry them directly; for
        // text/template payloads fall back to the failing assertion of the
        // same scope on this call (conditionIndex is -1 in practice).
        val data = notificationObj?.get("data")
        if (data is JsonObject && data.containsKey("expression")) {
            // An .assert() condition (lace-extensions §8.6) has no expected
            // value; the nearest reading is right operand vs left operand.
            vars["expression"] = data["expression"]?.jsonPrimitive?.contentOrNull ?: ""
            vars["expected"] = operand(data["actualRhs"])
            vars["actual"] = operand(data["actualLhs"])
        } else if (data is JsonObject) {
            vars["expected"] = data["expected"]?.jsonPrimitive?.contentOrNull ?: ""
            vars["actual"] = data["actual"]?.jsonPrimitive?.contentOrNull ?: ""
            // Baseline spikes compare against a rolling average, not an expectation.
            vars["average"] = data["average"]?.jsonPrimitive?.contentOrNull ?: ""
        } else {
            val assertion = (call?.get("assertions") as? JsonArray)
                ?.map { it.jsonObject }
                ?.firstOrNull {
                    it["outcome"]?.jsonPrimitive?.contentOrNull == "failed" &&
                        (scope == null || it["scope"]?.jsonPrimitive?.contentOrNull == scope)
                }
            vars["expected"] = assertion?.get("expected")?.jsonPrimitive?.contentOrNull ?: ""
            vars["actual"] = assertion?.get("actual")?.jsonPrimitive?.contentOrNull ?: ""
        }

        // The notification's own text (recovery/error messages reference it).
        // A connection error arrives structured, its message in `data.error`
        // (laceNotifications), so fall back to that, then to the call's error.
        vars["text"] = notificationObj?.get("value")?.jsonPrimitive?.contentOrNull
            ?: (data as? JsonObject)?.get("error")?.jsonPrimitive?.contentOrNull
            ?: call?.get("error")?.jsonPrimitive?.contentOrNull
            ?: ""

        return vars
    }

    /**
     * Builds the `${conditions}` list for a group — one entry per failed scope,
     * e.g. "[status: expected 200, got 500; totalDelayMs: expected 2, got 165]".
     * Baseline spikes compare against a rolling average, so they read
     * "average N" instead of "expected N", and .assert() conditions read
     * "this.body.page eq 1 (left: null, right: 1)". Non-structured groups yield "".
     */
    private fun buildConditions(trigger: String, events: List<JsonElement>): String {
        val parts = events.mapNotNull { event ->
            val obj = event.jsonObject
            val data = obj["notification"]?.jsonObject?.get("data") as? JsonObject ?: return@mapNotNull null
            val expression = data["expression"]?.jsonPrimitive?.contentOrNull
            if (expression != null) {
                return@mapNotNull "$expression (left: ${operand(data["actualLhs"])}, right: ${operand(data["actualRhs"])})"
            }
            val scope = obj["scope"]?.jsonPrimitive?.contentOrNull
                ?: data["scope"]?.jsonPrimitive?.contentOrNull
                ?: data["metric"]?.jsonPrimitive?.contentOrNull
                ?: "?"
            val actual = data["actual"]?.jsonPrimitive?.contentOrNull ?: ""
            if (trigger == "baseline_spike") {
                "$scope: average ${data["average"]?.jsonPrimitive?.contentOrNull ?: ""}, got $actual"
            } else {
                "$scope: expected ${data["expected"]?.jsonPrimitive?.contentOrNull ?: ""}, got $actual"
            }
        }
        return if (parts.isEmpty()) "" else parts.joinToString("; ", prefix = "[", postfix = "]")
    }

    /** A resolved operand as text: strings bare, null as `null`, anything else as JSON. */
    private fun operand(value: JsonElement?): String = when (value) {
        null, JsonNull -> "null"
        is JsonPrimitive -> value.content
        else -> value.toString()
    }
}
