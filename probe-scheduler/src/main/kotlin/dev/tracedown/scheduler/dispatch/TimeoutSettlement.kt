package dev.tracedown.scheduler.dispatch

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * Settles whose a timed-out run is before it is recorded: the endpoint's, or
 * the probing agent's.
 *
 * An agent that times out reads the kernel's record of the stalled connection
 * (`TCP_INFO`) and says what it shows, on the result's `attribution`:
 *
 * - `endpoint` — the endpoint acknowledged everything sent, nothing was lost,
 *   and it still did not answer. Recorded as the `timeout` it is.
 * - `agent` — the run was stalled in the agent's own work, outside any
 *   request. Recorded as an `error`: nothing was learned about the endpoint.
 * - `network` — packets were lost, or nothing answered at all. That alone does
 *   not say whose end of the path failed, so a second agent is asked to reach
 *   the same endpoint (`POST /reach`: connections and TLS handshakes, never an
 *   HTTP request, so a script that changes things is never repeated). Reached
 *   cleanly from there → the trouble was on the first agent's side, recorded
 *   as an `error`. Anything else — lossy, unreachable, no second agent, a
 *   second agent too old to answer — and it stays a `timeout`.
 * - `unknown`, or no attribution at all (an agent older than the reading) —
 *   a `timeout`, exactly as before any of this.
 *
 * So the only way a timeout stops being announced is positive evidence that
 * the endpoint was fine: an `error` carries no notification event and never
 * becomes the next run's `prev` (see [SyntheticProbeResult.error]). Every
 * doubt resolves to the alert.
 */
object TimeoutSettlement {

    /** Where a second agent is asked to connect, from the first agent's attribution. */
    data class Target(val host: String, val port: Int, val tls: Boolean)

    /** A second agent's answer from `/reach`; [reading] is null when it could not be asked. */
    data class Witness(val slug: String, val reading: JsonObject?)

    suspend fun settle(
        result: JsonObject,
        agentSlug: String,
        witness: suspend (Target) -> Witness?,
    ): JsonObject {
        if (result.string("outcome") != "timeout") return result
        val attribution = result["attribution"] as? JsonObject ?: return result
        val verdict = attribution.string("verdict") ?: return result
        val reason = reasonOf(attribution, verdict)

        return when (verdict) {
            "agent" -> asError(
                result,
                attribution,
                "the run stalled in agent $agentSlug's own work ($reason); " +
                    "not counted as the endpoint timing out",
                buildJsonObject { put("status", "error"); put("basis", "agent") },
            )
            "network" -> {
                val target = targetOf(attribution)
                    ?: return settled(result, attribution, timeoutSettlement("no target to re-check"))
                val asked = witness(target)
                    ?: return settled(result, attribution, timeoutSettlement("no second agent to re-check from"))
                val reading = asked.reading
                    ?: return settled(
                        result, attribution,
                        timeoutSettlement("agent ${asked.slug} could not be asked", asked),
                    )
                if (reading.string("path") == "clean") {
                    asError(
                        result,
                        attribution,
                        "agent $agentSlug lost its connection to ${target.host}:${target.port} ($reason), " +
                            "while agent ${asked.slug} reached it over a clean path; " +
                            "not counted as the endpoint timing out",
                        buildJsonObject {
                            put("status", "error")
                            put("basis", "witness")
                            put("witness", witnessRecord(asked))
                        },
                    )
                } else {
                    settled(
                        result, attribution,
                        timeoutSettlement("agent ${asked.slug} did not reach it cleanly either", asked),
                    )
                }
            }
            else -> settled(result, attribution, timeoutSettlement(reason))
        }
    }

    /**
     * The agent to ask: another one, preferably in another region (by the
     * slug's prefix, `eu-west-p01` → `eu-west`), so the two paths share as
     * little as possible. Null when the timed-out agent is the only one.
     */
    fun pickWitness(candidates: List<AgentSelector.Agent>, timedOut: AgentSelector.Agent): AgentSelector.Agent? {
        val others = candidates.filter { it.id != timedOut.id }
        val region = regionOf(timedOut.slug)
        return others.firstOrNull { regionOf(it.slug) != region } ?: others.firstOrNull()
    }

    private fun regionOf(slug: String): String = slug.substringBeforeLast('-')

    private fun targetOf(attribution: JsonObject): Target? {
        val target = attribution["target"] as? JsonObject ?: return null
        val host = target.string("host")?.takeIf { it.isNotBlank() } ?: return null
        val port = (target["port"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..65535 } ?: return null
        val tls = (target["tls"] as? JsonPrimitive)?.booleanOrNull ?: return null
        return Target(host, port, tls)
    }

    /** The agent's own words for why: the run's, or the first call's that carries the verdict. */
    private fun reasonOf(attribution: JsonObject, verdict: String): String =
        attribution.string("reason")
            ?: (attribution["calls"] as? JsonArray)
                ?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it.string("verdict") == verdict }
                ?.string("reason")
            ?: verdict

    private fun timeoutSettlement(basis: String, witness: Witness? = null) = buildJsonObject {
        put("status", "timeout")
        put("basis", basis)
        if (witness != null) put("witness", witnessRecord(witness))
    }

    private fun witnessRecord(witness: Witness): JsonObject = buildJsonObject {
        put("agent", witness.slug)
        witness.reading?.let { put("reading", it) }
    }

    private fun settled(result: JsonObject, attribution: JsonObject, settlement: JsonObject): JsonObject =
        JsonObject(result + ("attribution" to JsonObject(attribution + ("settlement" to settlement))))

    /**
     * The run as an `error`: the diagnostic in `error`, the observed outcome
     * kept on the attribution, and the `timeout` events removed — an error is
     * not announced, and they are what would announce it.
     */
    private fun asError(result: JsonObject, attribution: JsonObject, message: String, settlement: JsonObject): JsonObject {
        val fields = result.toMutableMap()
        fields["outcome"] = JsonPrimitive("error")
        fields["error"] = JsonPrimitive(message)
        (result["actions"] as? JsonObject)?.let { actions ->
            val notifications = actions["notifications"] as? JsonArray
            if (notifications != null) {
                val kept = notifications.filterNot { (it as? JsonObject)?.string("trigger") == "timeout" }
                fields["actions"] = JsonObject(actions + ("notifications" to JsonArray(kept)))
            }
        }
        fields["attribution"] = JsonObject(
            attribution + mapOf(
                "observedOutcome" to JsonPrimitive("timeout"),
                "settlement" to settlement,
            )
        )
        return JsonObject(fields)
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
}
