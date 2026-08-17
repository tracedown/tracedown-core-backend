package dev.tracedown.scheduler.dispatch

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonObject
import java.util.UUID

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
     * (embedded execution); [result] is null when the executor was unreachable
     * and produced nothing to persist.
     */
    data class Execution(
        val agentId: Long?,
        val result: JsonObject?,
        val egressBytes: Long = 0L,
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
 */
class AgentExecutionBackend(
    private val selector: AgentSelector,
    private val dispatch: AgentDispatchService,
) : ProbeExecutionBackend {

    override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
        val agents = selector.select(request.serviceId, request.probeMode)
        if (agents.isEmpty()) return emptyList()
        return coroutineScope {
            agents.map { agent ->
                async {
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
                    ProbeExecutionBackend.Execution(
                        agentId = agent.id,
                        result = dispatched.result,
                        egressBytes = dispatched.agentEgressBytes,
                    )
                }
            }.awaitAll()
        }
    }

    override fun close() = dispatch.close()
}
