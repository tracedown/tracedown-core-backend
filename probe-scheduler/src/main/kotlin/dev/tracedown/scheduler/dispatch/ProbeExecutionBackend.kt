package dev.tracedown.scheduler.dispatch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * How scheduled probe runs are executed.
 *
 * The default backend hands each run to the registered probe agents over mTLS
 * (selection per the service's probe mode). A host may substitute another
 * backend — e.g. an embedded in-process executor — via
 * [ProbeExecutionBackends.provider]; the surrounding pipeline (variable
 * resolution, domain policy, redaction, publishing, queue policy) is
 * backend-agnostic and stays in [DispatchQueue].
 */
interface ProbeExecutionBackend {

    /** Everything one scheduled run needs to execute. */
    data class Request(
        val serviceId: UUID,
        val orgId: UUID,
        val projectId: UUID,
        val workspaceId: UUID,
        val probeMode: String,
        val script: String,
        val variables: JsonObject,
        val timeoutMs: Int,
        val prev: JsonObject?,
        val allowBodySave: Boolean,
        val secretValues: Set<String>,
    )

    /**
     * One executed probe. [agentId] is null when no registered agent ran it
     * (embedded execution); [result] is null when the executor produced nothing
     * to persist, in which case [failureReason] says why — a run that observed
     * nothing must still be accountable for it.
     */
    data class Execution(
        val agentId: Long?,
        val result: JsonObject?,
        val egressBytes: Long = 0L,
        /**
         * Why no result came back, in the caller's own vocabulary (see
         * [AgentFailure.reason]). Null when [result] is set.
         */
        val failureReason: String? = null,
    )

    /**
     * Executes one scheduled run, possibly fanning out (e.g. simultaneous
     * probe mode). An empty list means nothing could run — no eligible
     * executor — which the caller reports as a capacity problem.
     */
    suspend fun execute(request: Request): List<Execution>

    fun close() {}
}

/** Host override point. When [provider] is null the agent backend is used. */
object ProbeExecutionBackends {
    @Volatile
    var provider: (() -> ProbeExecutionBackend)? = null
}

/**
 * The default backend: selects agents per the service's probe mode and
 * dispatches to each over slug-pinned mTLS.
 *
 * When a dispatch fails at the agent level *before* the script could run, the
 * same run is re-dispatched to the next eligible agent rather than being lost —
 * an agent killed between health rounds no longer takes every service pinned to
 * it silent. What may and may not be retried is [DispatchRetryPolicy]'s
 * decision; this class only supplies the candidates and the clock.
 */
class AgentExecutionBackend(
    private val selector: AgentSelector,
    private val dispatch: AgentDispatchService,
) : ProbeExecutionBackend {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
        val agents = selector.select(request.serviceId, request.probeMode)
        if (agents.isEmpty()) return emptyList()

        // The full allowed set, so a leg whose agent turns out to be unusable
        // has somewhere to go. Same eligibility rule as the pick above — a
        // fallback is never an agent health has not cleared. Resolved lazily:
        // a run whose dispatch works costs no second query for a fallback it
        // never asks for.
        val eligible = lazy { selector.eligible(request.serviceId) }

        // Agents this run has already been sent to, claimed atomically so two
        // concurrent legs cannot fall back onto the same agent. Seeded with the
        // mode's own picks: in `simultaneous` that is every eligible agent, so
        // there is correctly no fallback left to take.
        val tried = ConcurrentHashMap.newKeySet<Long>()
        agents.forEach { tried.add(it.id) }

        val startNanos = System.nanoTime()
        return coroutineScope {
            agents.map { agent ->
                async { runLeg(request, agent, eligible, tried, startNanos) }
            }.awaitAll()
        }
    }

    /**
     * Dispatches one leg of a run, moving to another eligible agent for as long
     * as the failures are agent-level and the retry budget allows.
     */
    private suspend fun runLeg(
        request: ProbeExecutionBackend.Request,
        firstAgent: AgentSelector.Agent,
        eligible: Lazy<List<AgentSelector.Agent>>,
        tried: MutableSet<Long>,
        startNanos: Long,
    ): ProbeExecutionBackend.Execution {
        var agent = firstAgent
        var attempts = 0
        // Bytes this leg actually put on the wire, summed across attempts — a
        // refused dispatch still sent its body.
        var egressBytes = 0L

        while (true) {
            val dispatched = dispatch.dispatch(
                agentUri = agent.uri,
                expectedSlug = agent.slug,
                script = request.script,
                variables = request.variables,
                timeoutMs = request.timeoutMs,
                prev = request.prev,
                allowBodySave = request.allowBodySave,
                secretValues = request.secretValues,
            )
            attempts++
            egressBytes += dispatched.agentEgressBytes

            val failure = dispatched.failure
            if (dispatched.result != null || failure == null) {
                return ProbeExecutionBackend.Execution(
                    agentId = agent.id,
                    result = dispatched.result,
                    egressBytes = egressBytes,
                    failureReason = if (dispatched.result == null) failure?.reason else null,
                )
            }

            val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
            val next = if (failure.retryable) claimNext(eligible, tried) else null
            if (!DispatchRetryPolicy.mayRetry(failure, attempts, elapsedMs, next != null)) {
                if (next != null) {
                    // Claimed but not used — hand it back so a sibling leg can
                    // still fall back onto it.
                    tried.remove(next.id)
                }
                log.warn(
                    "service {} could not be dispatched: {} after {} attempt(s), {}ms",
                    request.serviceId, failure.reason, attempts, elapsedMs,
                )
                return ProbeExecutionBackend.Execution(
                    agentId = agent.id,
                    result = null,
                    egressBytes = egressBytes,
                    failureReason = failure.reason,
                )
            }

            log.info(
                "service {} dispatch to agent {} failed ({}) — retrying on agent {}",
                request.serviceId, agent.slug, failure.reason, next!!.slug,
            )
            agent = next
        }
    }

    /**
     * Takes the next agent no leg of this run has used yet. `add` on a
     * concurrent set is the claim: it succeeds for exactly one caller.
     */
    private fun claimNext(eligible: Lazy<List<AgentSelector.Agent>>, tried: MutableSet<Long>): AgentSelector.Agent? =
        eligible.value.firstOrNull { tried.add(it.id) }

    override fun close() = dispatch.close()
}
