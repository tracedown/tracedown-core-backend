package dev.tracedown.scheduler.dispatch

import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import dev.tracedown.scheduler.crypto.PayloadSealing
import kotlinx.coroutines.CancellationException
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
 * The agent expects `POST /probe` with a JSON body containing `script`,
 * `variables`, `requestTimeoutMs` and, optionally, `prev`, `allowBodySave` and
 * `secretValues`. It answers with the raw ProbeResult object.
 *
 * `requestTimeoutMs` is the wall-clock budget for the **whole run** — every
 * call the script makes, start to finish — not a per-call timeout. It is the
 * same number this class sizes its own HTTP timeout from and the same number the
 * caller sizes the execution lock's TTL from, which is the point: all three
 * ends agree on when a run is over. Without it the agent ran to its script's
 * own per-call timeouts and the scheduler gave up first, recording an agent
 * fault for a run that was still going.
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
    /**
     * How long past the run budget the agent is given to answer — its own
     * processing and the network, on top of the budget it enforces itself.
     */
    private val clientGraceMs: Long = DEFAULT_CLIENT_GRACE_MS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The outcome of a single dispatch: the raw result (or synthetic/none) plus
     * the number of UTF-8 bytes of the request body actually sent to the agent.
     *
     * @param result the raw ProbeResult as a JsonObject, a synthetic error
     *   result for an agent that went silent or broke, or null when nothing was
     *   observed and the run should be attempted on another agent
     * @param agentEgressBytes UTF-8 byte size of the request body sent to the
     *   agent; zero when the agent was unreachable and nothing was sent
     * @param failure why no ProbeResult came back from the agent, or null when
     *   one did. [AgentFailure.retryable] decides whether the caller may
     *   re-dispatch; [result] and [failure] are never both null.
     */
    data class DispatchResult(
        val result: JsonObject?,
        val agentEgressBytes: Long,
        val failure: AgentFailure? = null,
        /**
         * True when [result] is the agent's own answer — it ran the script and
         * sent a ProbeResult back. False for every synthetic result, including
         * the error the scheduler records when the agent held the job and
         * never answered: that one is filed as a result but proves nothing
         * about the agent being alive.
         */
        val fromAgent: Boolean = false,
    )

    /**
     * Dispatches a probe job to an agent and returns the raw result.
     *
     * Nothing here is ever a silent failure — every path returns either a
     * ProbeResult to persist or an [AgentFailure] the caller must act on:
     *
     * - **A result from the agent** — persisted as observed, whatever its
     *   outcome. This is the target-level signal.
     * - **Request/socket timeout** — the agent held the job for the full probe
     *   budget plus grace and did not answer. Now that the budget is
     *   dispatched, an over-budget *run* comes back as a real `timeout`
     *   ProbeResult from the agent before this fires, so reaching here means
     *   the agent itself stopped answering — a fault of the agent, not an
     *   observation about the target, recorded as an `error` result on the
     *   normal persistence path (see [SyntheticProbeResult]). The probe most
     *   likely ran, and re-running it elsewhere would probe a live target
     *   twice for one scheduled tick, so it is not classified as retryable.
     *   Two populations do not get the budget and so land here for a slow
     *   target too, now recorded as `error` rather than `timeout`: agents
     *   older than 0.2.0, which never enforced one, and installs with a
     *   non-positive `probe.defaultTimeoutMs`, where it is omitted on purpose.
     * - **Refused before running** ([AgentFailure.REJECTED]) or **never
     *   reached** ([AgentFailure.UNREACHABLE]) — no result, and the caller may
     *   dispatch the same run to another eligible agent.
     * - **Broke while running** ([AgentFailure.EXECUTION_FAILED]) or **answered
     *   with a non-result** ([AgentFailure.MALFORMED_RESULT]) — a synthetic
     *   `error` ProbeResult carrying the diagnostic, persisted rather than
     *   retried, because the target may already have been called.
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
        // The budget for the probe itself, plus the grace for agent processing and network.
        val clientTimeoutMs = timeoutMs.toLong() + clientGraceMs
        val body = buildJsonObject {
            put("script", script)
            put("variables", variables)
            // The run budget, in milliseconds, for the whole run — the same
            // number clientTimeoutMs above is derived from, never a per-call
            // value. Sent inside the range the agent will act on so its own
            // clamp is a no-op; a non-positive configured timeout is omitted
            // entirely (the agent then runs to the script's own per-call
            // timeouts, which is the behaviour that predates this field) rather
            // than clamped up to the floor, which would time every probe out.
            runBudgetMs(timeoutMs)?.let { put("requestTimeoutMs", it) }
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

            // The client is not configured to throw on non-2xx, so the status
            // has to be read before the body is treated as a ProbeResult. It
            // used to be decoded blindly: an agent's 500 body parsed as JSON,
            // normalised to outcome `error`, and was then dropped downstream —
            // the run vanished with nothing but a warning.
            val statusCode = response.status.value
            val text = response.bodyAsText()
            val statusFailure = AgentFailure.ofHttpStatus(statusCode)
            if (statusFailure != null) {
                val detail = detailOf(text)
                return if (statusFailure.retryable) {
                    log.warn(
                        "dispatch to {} refused with HTTP {} ({}) — trying the next eligible agent",
                        agentUri, statusCode, detail,
                    )
                    DispatchResult(null, agentEgressBytes, statusFailure)
                } else {
                    log.error("dispatch to {} failed inside the agent: HTTP {} ({})", agentUri, statusCode, detail)
                    DispatchResult(
                        SyntheticProbeResult.error("agent failed while running the probe: HTTP $statusCode — $detail"),
                        agentEgressBytes,
                        statusFailure,
                    )
                }
            }

            val decoded = try {
                val parsed = Json.decodeFromString<JsonObject>(text)
                sealing?.open(parsed) ?: parsed
            } catch (e: Exception) {
                log.error("agent {} answered HTTP {} with something that is not a ProbeResult: {}", agentUri, statusCode, e.message)
                return DispatchResult(
                    SyntheticProbeResult.error("agent returned a body that is not a ProbeResult: ${e.message}"),
                    agentEgressBytes,
                    AgentFailure.MALFORMED_RESULT,
                )
            }
            DispatchResult(decoded, agentEgressBytes, fromAgent = true)
        } catch (e: ConnectTimeoutException) {
            // Never got a connection, so the script never started: safe to
            // re-run elsewhere. Distinct from the request/socket timeouts
            // below, which mean the agent had the job all along.
            log.warn("dispatch to {} could not connect within the connect timeout: {}", agentUri, e.message)
            DispatchResult(null, 0L, AgentFailure.UNREACHABLE)
        } catch (e: HttpRequestTimeoutException) {
            log.warn("dispatch to {} timed out after {}ms — recording error result", agentUri, clientTimeoutMs)
            DispatchResult(agentSilent(clientTimeoutMs), agentEgressBytes)
        } catch (e: SocketTimeoutException) {
            log.warn("dispatch to {} socket-timed-out after {}ms — recording error result", agentUri, clientTimeoutMs)
            DispatchResult(agentSilent(clientTimeoutMs), agentEgressBytes)
        } catch (e: CancellationException) {
            // Shutdown, not an agent fault. Rethrown so the retry loop above
            // stops rather than walking the fleet on the way down.
            throw e
        } catch (e: Exception) {
            // Connection refused, DNS, TLS handshake, pinning/revocation
            // rejection: the request never reached the agent's application.
            log.error("dispatch to {} failed: {}", agentUri, e.message)
            DispatchResult(null, 0L, AgentFailure.UNREACHABLE)
        }
    }

    /**
     * The agent took the job and returned nothing within [clientTimeoutMs] —
     * the budget it was dispatched plus the grace for processing and network.
     * The check did not evaluate, so this is an `error`, never a `timeout`.
     * Both count against uptime; the difference is that an `error` does not
     * become the next run's `prev`, so no recovery is ever announced from it,
     * and it carries no alert — the service's owner was not told of an outage
     * that there is no evidence of.
     */
    private fun agentSilent(clientTimeoutMs: Long): JsonObject =
        SyntheticProbeResult.error("agent did not return a result within ${clientTimeoutMs}ms")

    /** A short, log- and result-safe excerpt of an agent's answer. */
    private fun detailOf(body: String): String =
        body.trim().replace(Regex("\\s+"), " ").take(ERROR_DETAIL_MAX_CHARS).ifBlank { "no detail" }

    companion object {
        /** Enough to identify the fault, short enough to keep out of the result payload's way. */
        private const val ERROR_DETAIL_MAX_CHARS = 300

        /** Grace past the run budget for the agent to answer: its processing plus the network. */
        const val DEFAULT_CLIENT_GRACE_MS = 15_000L

        /**
         * The smallest run budget worth sending. Below it a probe cannot finish
         * a DNS lookup and a TLS handshake, so the agent clamps up to the same
         * floor; sending inside the range keeps that clamp a no-op.
         */
        const val MIN_RUN_BUDGET_MS = 1_000

        /**
         * The largest run budget worth sending — the Lace system ceiling
         * (`executor.maxTimeoutMs`, spec §11), which is also this scheduler's
         * `probe.maxTimeoutMs` default and the agent's own ceiling.
         */
        const val MAX_RUN_BUDGET_MS = 300_000

        /**
         * The `requestTimeoutMs` to dispatch for a configured probe timeout, or
         * null to omit the field.
         *
         * Pure so the boundary is testable without an agent. A non-positive
         * timeout is a misconfiguration, and the two ways to honour it are not
         * equivalent: clamping up to the floor would time every probe out after
         * a second, while omitting the field leaves the run to the script's own
         * per-call timeouts — exactly how the agent behaved before the budget
         * existed. Omission is the one that fails safe.
         */
        fun runBudgetMs(timeoutMs: Int): Int? =
            if (timeoutMs <= 0) null else timeoutMs.coerceIn(MIN_RUN_BUDGET_MS, MAX_RUN_BUDGET_MS)
    }

    /** Shuts down all per-agent HTTP clients. */
    fun close() {
        clientFactory.close()
    }
}
