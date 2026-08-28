package dev.tracedown.gateway.routes.v1.agents

import dev.tracedown.common.agents.AgentVisibility
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

@Serializable
data class AgentStatus(
    val agentSlug: String,
    val status: String,
    val lastCheck: String?,
    val lastResponseMs: Int?,
)

@Serializable
data class AgentHealthResponse(
    val statuses: List<AgentStatus>,
)

/**
 * @OpenAPITag Agents
 * Probe agent health status.
 */
@Resource("/api/v1/agents")
class Agents {
    @Resource("health")
    class Health(val parent: Agents = Agents())
}

/** Registers agent health routes. */
fun Route.agentRoutes() {
    /**
     * Health status of the probe agents the caller may see. No pagination.
     *
     * This is the fleet roster, not a status page: it names every agent and its
     * liveness. It used to answer any authenticated session, with no
     * organization context at all — a user who had not even selected an org
     * could enumerate the whole fleet. It now requires an org context, and the
     * result narrows through [AgentVisibility], so a deployment that gives
     * agents owners answers with the caller's own agents rather than everyone's.
     *
     * Deliberately gated on membership only, not on the `settings` grant the
     * admin list requires: this response carries a slug and a liveness state,
     * nothing configurable and no addresses, and every member has a legitimate
     * interest in whether the machinery running their probes is up.
     */
    get<Agents.Health> {
        val (principal, orgId) = requireAuthWithOrg(call)

        val statuses = transaction {
            val rows = ProbeAgents.selectAll()
                .where { (ProbeAgents.isActive eq true) and (ProbeAgents.deleted eq false) }
                .toList()
            val visible = AgentVisibility.visible(orgId, principal.userId, rows.map { it[ProbeAgents.slug] })
            rows.filter { it[ProbeAgents.slug] in visible }
                .map { row ->
                    AgentStatus(
                        agentSlug = row[ProbeAgents.slug],
                        status = row[ProbeAgents.lastStatus],
                        lastCheck = row[ProbeAgents.lastPing].toString(),
                        lastResponseMs = row[ProbeAgents.lastPongDeltaMs],
                    )
                }
        }

        call.respond(AgentHealthResponse(statuses = statuses))
    }
}
