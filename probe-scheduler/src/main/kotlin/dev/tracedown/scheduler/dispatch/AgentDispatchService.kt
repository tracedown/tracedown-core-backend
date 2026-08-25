package dev.tracedown.scheduler.dispatch

import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import dev.tracedown.scheduler.crypto.PayloadSealing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Dispatches probe jobs to agents over mTLS.
 *
 * The agent expects ``POST /probe`` with a JSON body containing
 * ``script``, ``variables``, ``requestTimeoutMs``, and optionally
 * ``prev``.  It returns the raw ProbeResult dict.
 *
 * The [clientFactory] hands out a per-agent client that pins the peer to the
 * intended agent's certificate identity, so a probe (and its resolved secret
 * variables) can only ever be delivered to that specific agent.
 */
class AgentDispatchService(
    private val clientFactory: AgentMtlsClientFactory,
    /**
     * Seals the payload to the agent's certificate on top of mTLS. Null (the
     * default) keeps the wire exactly as it was — this is opt-in, and an
     * installation that never turns it on is unaffected by any of it.
     */
    private val sealing: PayloadSealing? = null,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The outcome of a single dispatch: the raw result (or synthetic/none) plus
     * the number of UTF-8 bytes of the request body actually sent to the agent.
     *
     * @param result the raw ProbeResult as a JsonObject, a synthetic timeout
     *   result, or null when the agent itself was unreachable
     * @param agentEgressBytes UTF-8 byte size of the request body sent to the
     *   agent; zero when the agent was unreachable and nothing was sent
     */
    data class DispatchResult(val result: JsonObject?, val agentEgressBytes: Long)

    /**
     * Dispatches a probe job to an agent and returns the raw result.
     *
     * A dispatch that times out (the agent did not return a result within the
     * client window) is not a silent failure: monitoring must record that the
     * probe could not complete, so this returns a synthetic `timeout`
     * ProbeResult which flows through the normal persistence path (updating
     * last_status and firing notifications) rather than vanishing. A genuine
     * failure to reach the agent (connection refused, TLS, etc.) returns null —
     * that is an agent-infrastructure fault, surfaced by agent-health, not a
     * per-service monitoring signal about the target.
     *
     * The returned [DispatchResult.agentEgressBytes] is the UTF-8 size of the
     * request body posted to the agent. A timeout still counts those bytes (the
     * body was sent); a hard unreachable failure reports zero.
     */
    suspend fun dispatch(
        agentUri: String,
        expectedSlug: String,
        script: String,
        variables: JsonObject,
        timeoutMs: Int,
        prev: JsonObject?,
            allowBodySave: Boolean = true,
        secretValues: Set<String> = emptySet(),
    ): DispatchResult {
        // Allow timeout for the probe itself + 15s overhead for agent processing/network
        val clientTimeoutMs = timeoutMs.toLong() + 15_000L
        val body = buildJsonObject {
            put("script", script)
            put("variables", variables)
            if (prev != null) put("prev", prev)
            if (!allowBodySave) put("allowBodySave", false)
            // Resolved secret plaintexts for this run. The agent masks these out of
            // any saved response body BEFORE upload, so a monitored endpoint that
            // reflects a credential never lands the org's secret in the body store
            // as plaintext. (The scheduler still masks the echoed request via
            // ResultRedactor; this covers the body bytes the agent alone holds.)
            if (secretValues.isNotEmpty()) put(
                "secretValues",
                buildJsonArray { secretValues.forEach { add(JsonPrimitive(it)) } },
            )
        }
        // Bytes dispatched to the agent for this run = UTF-8 size of the body.
        val agentEgressBytes = body.toString().toByteArray(Charsets.UTF_8).size.toLong()
        // Per-agent client: the TLS handshake pins the peer to expectedSlug's
        // certificate identity, so this body (scripts + resolved secrets) can
        // only be delivered to the intended agent.
        val client = clientFactory.client(expectedSlug)
        // Sealed or plain is decided per agent, so a fleet can be upgraded a
        // node at a time. The agent answers in whichever shape it was asked in.
        val wire = sealing?.seal(expectedSlug, body) ?: body
        return try {
            val response = client.post("$agentUri/probe") {
                contentType(ContentType.Application.Json)
                setBody(wire)
                timeout {
                    requestTimeoutMillis = clientTimeoutMs
                    socketTimeoutMillis = clientTimeoutMs
                }
            }

            val decoded = Json.decodeFromString<JsonObject>(response.bodyAsText())
            DispatchResult(sealing?.open(decoded) ?: decoded, agentEgressBytes)
        } catch (e: HttpRequestTimeoutException) {
            log.warn("dispatch to {} timed out after {}ms — recording timeout result", agentUri, clientTimeoutMs)
            DispatchResult(syntheticTimeout(clientTimeoutMs), agentEgressBytes)
        } catch (e: SocketTimeoutException) {
            log.warn("dispatch to {} socket-timed-out after {}ms — recording timeout result", agentUri, clientTimeoutMs)
            DispatchResult(syntheticTimeout(clientTimeoutMs), agentEgressBytes)
        } catch (e: Exception) {
            log.error("dispatch to {} failed: {}", agentUri, e.message)
            DispatchResult(null, 0L)
        }
    }

    /**
     * A minimal ProbeResult standing in for a probe the agent could not return
     * in time. `outcome=timeout` is the honest monitoring status; empty `calls`
     * because no call produced timings. The ingestor persists it like any other
     * timeout.
     */
    private fun syntheticTimeout(elapsedMs: Long): JsonObject = buildJsonObject {
        put("outcome", "timeout")
        put("elapsedMs", elapsedMs)
        put("calls", buildJsonArray {})
        put("error", "agent did not return a result within ${elapsedMs}ms")
    }

    /** Shuts down all per-agent HTTP clients. */
    fun close() {
        clientFactory.close()
    }
}
