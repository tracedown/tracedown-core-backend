package dev.tracedown.common.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One failed assertion of a probe run, reduced to what a one-line failure
 * preview shows.
 *
 * A scope assertion (`.expect()`, `.check()`) carries its [scope], the
 * [expected] value and what the target answered as [actual]. An `.assert()`
 * condition has no scope: [scope] is `"assert"`, [expression] is the condition
 * rendered back to source (spec §9.2) and [actual] is its resolved left
 * operand — the right one is usually the literal already in the expression.
 */
data class FailedAssertionPreview(
    val scope: String,
    val expected: String?,
    val actual: String?,
    val expression: String? = null,
)

/**
 * Picks the failed assertions out of a ProbeResult's `calls` (spec §9.2).
 *
 * `assertions[]` holds whatever the executor wrote, so nothing here throws:
 * an entry that is not an object is passed over, and a value of any JSON shape
 * — `expected: [200, 404]` for a status list, an object for a schema — is
 * rendered as text rather than read as a primitive. Only `failed` counts;
 * `indeterminate` is not a failure (spec §5.4).
 */
object FailedAssertions {

    fun fromCalls(calls: JsonArray?, limit: Int = Int.MAX_VALUE): List<FailedAssertionPreview> {
        val failed = mutableListOf<FailedAssertionPreview>()
        for (call in calls ?: return failed) {
            val assertions = (call as? JsonObject)?.get("assertions") as? JsonArray ?: continue
            for (assertion in assertions) {
                if (failed.size >= limit) return failed
                val obj = assertion as? JsonObject ?: continue
                if (text(obj["outcome"]) != "failed") continue
                failed += if (text(obj["method"]) == "assert") {
                    FailedAssertionPreview(
                        scope = "assert",
                        expected = null,
                        actual = text(obj["actualLhs"]),
                        expression = text(obj["expression"]),
                    )
                } else {
                    FailedAssertionPreview(
                        scope = text(obj["scope"]) ?: "unknown",
                        expected = text(obj["expected"]),
                        actual = text(obj["actual"]),
                    )
                }
            }
        }
        return failed
    }

    /**
     * A JSON value as display text: a string without its quotes, `null` as
     * the word, anything else as compact JSON. Absent is Kotlin null.
     */
    private fun text(element: JsonElement?): String? = when (element) {
        null -> null
        is JsonNull -> "null"
        is JsonPrimitive -> element.content
        else -> element.toString()
    }
}
