package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ServiceAllowedAgents
import io.lettuce.core.api.sync.RedisCommands
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Selects probe agents for a service based on its probe mode.
 *
 * - ``consecutive``: round-robin via Redis counter
 * - ``simultaneous``: all eligible agents
 * - ``random``: one random agent
 */
class AgentSelector(private val redis: RedisCommands<String, String>) {

    data class Agent(
        val id: Long,
        val slug: String,
        val uri: String,
    )

    /**
     * Returns the agent(s) to dispatch to for this probe run.
     *
     * @param serviceId the service being probed
     * @param probeMode one of "consecutive", "simultaneous", "random"
     * @return list of agents to dispatch to (may be empty if none eligible)
     */
    fun select(serviceId: UUID, probeMode: String): List<Agent> {
        val agents = eligible(serviceId)
        if (agents.isEmpty()) return emptyList()

        return when (probeMode) {
            "consecutive" -> {
                // Per-service phase offset: services created (and ticking)
                // together otherwise rotate in lockstep — every tick sends
                // the whole fleet to ONE agent while the others idle.
                val offset = serviceId.hashCode().toLong() and 0xffff
                val idx = redis.incr("probe_rr:$serviceId") + offset
                val selected = agents[((idx - 1) % agents.size).toInt()]
                listOf(selected)
            }
            "simultaneous" -> agents
            "random" -> listOf(agents.random())
            else -> listOf(agents.first())
        }
    }

    /**
     * Every agent this service is allowed to run on, in a stable order.
     *
     * [select] narrows this to the agent(s) one tick dispatches to; the
     * re-dispatch path needs the full set so it can fall back to an agent the
     * mode did not pick. The membership rule is identical either way — a run
     * never lands on an agent the health check has not cleared.
     */
    fun eligible(serviceId: UUID): List<Agent> = transaction { eligibleAgents(serviceId) }

    private fun eligibleAgents(serviceId: UUID): List<Agent> {
        // Check if service has specific allowed agents
        val allowedIds = ServiceAllowedAgents.selectAll()
            .where { ServiceAllowedAgents.serviceId eq serviceId }
            .map { it[ServiceAllowedAgents.probeAgentId] }

        val query = if (allowedIds.isNotEmpty()) {
            ProbeAgents.selectAll()
                .where {
                    (ProbeAgents.id inList allowedIds) and
                    (ProbeAgents.isActive eq true) and
                    (ProbeAgents.lastStatus eq "success")
                }
        } else {
            // No restrictions — use all active, healthy agents
            ProbeAgents.selectAll()
                .where {
                    (ProbeAgents.isActive eq true) and
                    (ProbeAgents.lastStatus eq "success")
                }
        }

        val eligible = query.orderBy(ProbeAgents.id).map { toAgent(it) }

        // Honor an overlay-supplied allowlist (null = no restriction, the Core default).
        val allowed = AgentAllowlist.provider.allowedAgentIds(serviceId) ?: return eligible
        return eligible.filter { it.id in allowed }
    }

    private fun toAgent(row: ResultRow) = Agent(
        id = row[ProbeAgents.id],
        slug = row[ProbeAgents.slug],
        uri = row[ProbeAgents.agentUri],
    )
}
