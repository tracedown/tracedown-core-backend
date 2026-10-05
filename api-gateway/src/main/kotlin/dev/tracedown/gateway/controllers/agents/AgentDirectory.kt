package dev.tracedown.gateway.controllers.agents

import dev.tracedown.common.agents.AgentVisibility
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.gateway.data.agents.PublicAgentSummary
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * The agents a member may name in a service's agent list, by slug.
 *
 * The same gate as the dashboard's agent health roster: membership, re-resolved
 * per call, and the deployment's [AgentVisibility] narrowing — and less than it
 * shows: a slug and a label, no liveness, no address.
 */
object AgentDirectory {

    /** The active agents [userId] can see in [orgId], ordered by slug. */
    fun list(orgId: UUID, userId: UUID): List<PublicAgentSummary> = transaction {
        // 403 not_org_member when the membership is gone.
        requireCachedPermissions(orgId, userId)
        val rows = ProbeAgents.select(ProbeAgents.slug, ProbeAgents.label)
            .where { (ProbeAgents.isActive eq true) and (ProbeAgents.deleted eq false) }
            .orderBy(ProbeAgents.slug)
            .toList()
        val visible = AgentVisibility.visible(orgId, userId, rows.map { it[ProbeAgents.slug] })
        rows.filter { it[ProbeAgents.slug] in visible }
            .map { PublicAgentSummary(slug = it[ProbeAgents.slug], label = it[ProbeAgents.label]) }
    }
}
