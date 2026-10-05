package dev.tracedown.scheduler.dispatch

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * ProbeResults the platform builds itself, standing in for a run no executor
 * finished.
 *
 * Two kinds, and which one a situation gets is the whole point:
 *
 * - [timeout] — the run was under way and the target did not answer within
 *   the budget. An observation about the target: persisted as a `timeout`,
 *   it moves the service's `last_run_id` and counts as downtime, and the next
 *   successful run announces `recovered` from `prev.outcome`. Notification
 *   events (`actions.notifications`, lace-extensions.md §12) are emitted by
 *   the Lace extensions as a run evaluates, and the dispatcher alerts on
 *   nothing else; a result built here never ran, so it has none — and a
 *   `timeout` without one is announced only by that recovery, the one mail
 *   about the incident saying it is over. So [timeout] carries the event the
 *   extension could not emit, in the shape laceEmitRecovery uses for a
 *   run-level event (`callIndex` -1: nothing to point at).
 * - [error] — the check did not evaluate: an agent that took the job and
 *   never answered, or broke, or an embedded executor that threw. Nothing may
 *   be claimed about the target, so nothing is announced to the service's
 *   owner — the `error` on the result (shown with the run) says what went
 *   wrong, which for a script that will not parse is theirs to fix. Its
 *   outcome is outside the ProbeResult vocabulary; the ingestor normalises
 *   it to `error`, which still counts against uptime but does not move
 *   `last_run_id`, so it never becomes `prev` and no recovery is announced
 *   from it. The operator learns from the agent health rounds and the logs.
 */
object SyntheticProbeResult {

    /**
     * Opens the message a [timeout] is announced with: a dispatcher-side
     * template, filled in where the names are known. The probe agent's own
     * stand-in results open the same way.
     */
    const val SERVICE_PREFIX = "\${s.name} in \${w.name}.\${p.name}"

    /**
     * A `timeout` for a run that produced no result within [elapsedMs].
     * [diagnostic] is kept on the result as its `error`; [text] is what the
     * service's owner is told, a template over [SERVICE_PREFIX]. Empty
     * `calls` because no call produced timings.
     */
    fun timeout(elapsedMs: Long, diagnostic: String, text: String): JsonObject = buildJsonObject {
        put("outcome", "timeout")
        put("elapsedMs", elapsedMs)
        put("calls", buildJsonArray {})
        put("actions", buildJsonObject {
            put("notifications", buildJsonArray { add(notificationEvent("timeout", text)) })
        })
        put("error", diagnostic)
    }

    /**
     * A result for a run that was taken on and could not complete. [detail]
     * is what the person who wrote the script needs to see.
     */
    fun error(detail: String): JsonObject = buildJsonObject {
        put("outcome", "error")
        put("elapsedMs", 0)
        put("calls", buildJsonArray {})
        put("error", detail)
    }

    private fun notificationEvent(trigger: String, text: String): JsonObject = buildJsonObject {
        put("callIndex", -1)
        put("conditionIndex", -1)
        put("trigger", trigger)
        put("scope", JsonNull)
        put("notification", buildJsonObject {
            put("tag", "text")
            put("value", text)
        })
    }
}
