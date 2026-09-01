package dev.tracedown.scheduler.results

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Redacts secret variable values out of a raw ProbeResult before it leaves the
 * scheduler for the results queue.
 *
 * A script may embed a `secret` variable in a request URL or header; the executor
 * echoes the resolved request (url + headers) back in the ProbeResult, so the
 * plaintext would otherwise be persisted verbatim and shown to org members. This
 * walks every string leaf of the result and replaces any occurrence of a secret's
 * plaintext with a fixed mask — covering `request.url`, `request.headers.*`,
 * redirect chains, and any other string field, wherever the value happens to land.
 *
 * Only SECRET variables are redacted (never `variable`/`metric`), and only their
 * resolved plaintext values for this run (surfaced by [VariableResolver]) — so
 * unrelated response content is untouched unless it literally equals a secret.
 */
object ResultRedactor {

    private const val MASK = "••••••" // ••••••

    /**
     * Response (and request) header names whose value is a credential or a
     * session token and must never be persisted verbatim, regardless of whether
     * any script variable happens to equal it. `set-cookie`/`cookie` carry
     * session material; `authorization`/`proxy-authorization` carry bearer or
     * basic credentials. Compared case-insensitively — response header names are
     * lower-cased per spec §9, but a request echoes them as the script wrote them.
     */
    private val SENSITIVE_HEADERS = setOf(
        "authorization", "proxy-authorization", "set-cookie", "cookie",
    )

    /**
     * Returns [result] with sensitive headers masked and every occurrence of a
     * secret plaintext masked.
     *
     * The header pass runs unconditionally — a `Set-Cookie` or `Authorization`
     * header the probed endpoint returned is sensitive whether or not a secret
     * variable was involved, and the executor echoes request/response headers
     * back into the result that is persisted and shown to org members. It runs
     * first so that a masked header value is not then re-scanned for secrets.
     */
    fun redact(result: JsonObject, secretValues: Set<String>): JsonObject {
        val headerRedacted = redactSensitiveHeaders(result) as JsonObject
        // Longest-first so that a secret that is a substring of another is masked
        // fully rather than leaving a fragment behind.
        val secrets = secretValues.filter { it.isNotBlank() }.sortedByDescending { it.length }
        if (secrets.isEmpty()) return headerRedacted
        return redactElement(headerRedacted, secrets) as JsonObject
    }

    /**
     * Masks the values of [SENSITIVE_HEADERS] wherever a `headers` object appears
     * in the result — request headers, response headers, and any redirect hop.
     * A multi-valued header (a JSON array, as `set-cookie` can be) has every
     * element masked.
     */
    // Structural sharing: an unchanged subtree is returned by identity, so a
    // result with no sensitive header (and no secrets) comes back as the very
    // same instance rather than a rebuilt copy.
    private fun redactSensitiveHeaders(el: JsonElement): JsonElement = when (el) {
        is JsonObject -> {
            var changed = false
            val next = LinkedHashMap<String, JsonElement>(el.size)
            for ((key, value) in el) {
                val newValue = if (key.equals("headers", ignoreCase = true) && value is JsonObject) {
                    maskHeaders(value)
                } else {
                    redactSensitiveHeaders(value)
                }
                if (newValue !== value) changed = true
                next[key] = newValue
            }
            if (changed) JsonObject(next) else el
        }
        is JsonArray -> {
            var changed = false
            val next = ArrayList<JsonElement>(el.size)
            for (item in el) {
                val newItem = redactSensitiveHeaders(item)
                if (newItem !== item) changed = true
                next.add(newItem)
            }
            if (changed) JsonArray(next) else el
        }
        else -> el
    }

    private fun maskHeaders(headers: JsonObject): JsonObject {
        var changed = false
        val next = LinkedHashMap<String, JsonElement>(headers.size)
        for ((name, value) in headers) {
            val newValue = if (name.lowercase() in SENSITIVE_HEADERS) maskValue(value) else value
            if (newValue !== value) changed = true
            next[name] = newValue
        }
        return if (changed) JsonObject(next) else headers
    }

    private fun maskValue(value: JsonElement): JsonElement = when (value) {
        is JsonArray -> JsonArray(value.map { JsonPrimitive(MASK) })
        else -> JsonPrimitive(MASK)
    }

    private fun redactElement(el: JsonElement, secrets: List<String>): JsonElement = when (el) {
        is JsonObject -> JsonObject(el.mapValues { redactElement(it.value, secrets) })
        is JsonArray -> JsonArray(el.map { redactElement(it, secrets) })
        is JsonPrimitive -> if (el.isString) redactString(el.content, secrets) else el
    }

    private fun redactString(value: String, secrets: List<String>): JsonPrimitive {
        var out = value
        for (secret in secrets) {
            if (out.contains(secret)) out = out.replace(secret, MASK)
        }
        return if (out == value) JsonPrimitive(value) else JsonPrimitive(out)
    }
}
