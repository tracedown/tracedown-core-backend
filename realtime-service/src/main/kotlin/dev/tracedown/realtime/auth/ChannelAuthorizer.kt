package dev.tracedown.realtime.auth

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.auth.resolveCachedPermissions
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Authorizes WebSocket channel subscriptions and relays against the SAME
 * resource-permission model the REST API enforces — the realtime-service shares
 * the database, so a live channel must not become a side door around it.
 *
 * Resource-scoped channels (`service:`, `project:`, `workspace:`, and the
 * collaborative-edit `svc-edit:` relay) name a specific resource; a bare org
 * member must not read another team's live probe stream or inject script edits
 * into a service they cannot access. Membership alone (which the org-scoped
 * session already proves) is NOT sufficient for these — the caller must hold
 * the resource grant, with the same downward inheritance the gateway applies.
 *
 * The fleet channels (`agents`, `agents:summary`) are the one global feed, and
 * they ARE gated here — see [canSubscribe].
 *
 * The remaining non-resource channels (`org:`, `session:`) are governed
 * elsewhere (org scope / self) and are not gated here.
 */
object ChannelAuthorizer {

    /**
     * True if [userId] may subscribe (read) to [channel] within [orgId].
     *
     * The fleet feed (`agents`, `agents:*`) carries every registered agent's
     * slug and liveness, and it is fanned out globally rather than per org —
     * the broadcast path exempts it from the org filter precisely because in
     * Core the fleet is shared platform infrastructure. It had no check of any
     * kind, so any established socket could subscribe to it, including one
     * whose session names an organization the user is no longer in. It now
     * requires the same thing the REST fleet endpoints require: that the
     * subscriber actually be a member of the org their session names.
     *
     * That is the whole of the correct answer for Core, where every agent
     * genuinely belongs to every org in the install. A deployment that gives
     * agents owners has a second problem this cannot solve from here — the
     * events themselves would still name other orgs' agents — and closes it by
     * filtering the fan-out; see `AgentVisibility`.
     */
    fun canSubscribe(userId: UUID, orgId: UUID, channel: String): Boolean {
        if (isFleetChannel(channel)) {
            return transaction { resolveCachedPermissions(orgId, userId) != null }
        }
        return checkResourceChannel(userId, orgId, channel, requireWrite = false)
    }

    /** The global probe-agent feed: `agents` and its `agents:<view>` variants. */
    fun isFleetChannel(channel: String): Boolean =
        channel == "agents" || channel.startsWith("agents:")

    /** True if [userId] may relay (write) into [channel] within [orgId]. */
    fun canRelay(userId: UUID, orgId: UUID, channel: String): Boolean =
        checkResourceChannel(userId, orgId, channel, requireWrite = true)

    private fun checkResourceChannel(
        userId: UUID,
        orgId: UUID,
        channel: String,
        requireWrite: Boolean,
    ): Boolean {
        val (resourceType, rawId) = parseResourceChannel(channel)
            ?: return true // not a resource-scoped channel — not gated here
        val resourceId = try {
            UUID.fromString(rawId)
        } catch (_: Exception) {
            return false
        }

        return transaction {
            val cached = resolveCachedPermissions(orgId, userId) ?: return@transaction false

            when (resourceType) {
                "workspace" -> {
                    if (!workspaceInOrg(resourceId, orgId)) return@transaction false
                    check(cached, "workspace", resourceId, emptyList(), requireWrite)
                }
                "project" -> {
                    val workspaceId = projectWorkspace(resourceId, orgId) ?: return@transaction false
                    check(cached, "project", resourceId, listOf("workspace::$workspaceId"), requireWrite)
                }
                "service" -> {
                    val ctx = serviceContext(resourceId, orgId) ?: return@transaction false
                    check(
                        cached, "service", resourceId,
                        listOf("project::${ctx.first}", "workspace::${ctx.second}"),
                        requireWrite,
                    )
                }
                else -> false
            }
        }
    }

    private fun check(
        cached: dev.tracedown.common.auth.CachedPermissions,
        type: String,
        id: UUID,
        parentChain: List<String>,
        requireWrite: Boolean,
    ): Boolean = if (requireWrite) {
        canWriteResource(cached, type, id, parentChain)
    } else {
        canAccessResource(cached, type, id, parentChain)
    }

    /** Maps a channel name to (resourceType, id) or null if not resource-scoped. */
    private fun parseResourceChannel(channel: String): Pair<String, String>? = when {
        channel.startsWith("svc-edit:") -> "service" to channel.removePrefix("svc-edit:")
        channel.startsWith("service:") -> "service" to channel.removePrefix("service:")
        channel.startsWith("project:") -> "project" to channel.removePrefix("project:")
        channel.startsWith("workspace:") -> "workspace" to channel.removePrefix("workspace:")
        else -> null
    }

    private fun workspaceInOrg(workspaceId: UUID, orgId: UUID): Boolean =
        Workspaces.selectAll()
            .where { (Workspaces.id eq workspaceId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .any()

    private fun projectWorkspace(projectId: UUID, orgId: UUID): UUID? =
        Projects
            .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
            .selectAll()
            .where { (Projects.id eq projectId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .firstOrNull()
            ?.get(Projects.workspaceId)

    /** Returns (projectId, workspaceId) for a service in [orgId], or null. */
    private fun serviceContext(serviceId: UUID, orgId: UUID): Pair<UUID, UUID>? =
        Services
            .join(Projects, JoinType.INNER, Services.projectId, Projects.id)
            .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
            .selectAll()
            .where { (Services.id eq serviceId) and (Workspaces.organizationId eq orgId) }
            .limit(1)
            .firstOrNull()
            ?.let { it[Services.projectId] to it[Projects.workspaceId] }
}
