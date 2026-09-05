package dev.tracedown.common.auth

import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Computes and writes the permission_cache JSONB on org_users.
 *
 * The cache is the union (max) of direct org_users permissions + all group
 * permissions, with resource hierarchy enforcement (child access implies
 * parent read access).
 *
 * Invalidation entry points:
 * - [recomputeForUser] — when direct user permissions change
 * - [recomputeForGroup] — when group permissions or membership change
 * - [recomputeForOrg] — when org-level policy changes (e.g. totpRequired)
 */
object PermissionCacheService {

    private val log = LoggerFactory.getLogger(PermissionCacheService::class.java)

    /** Recomputes cache for a single org_users row. */
    fun recomputeForUser(orgUserId: UUID) {
        val membership = OrgUsers.selectAll()
            .where { OrgUsers.id eq orgUserId }
            .firstOrNull() ?: return

        computeAndWrite(membership[OrgUsers.id], membership[OrgUsers.organizationId], membership[OrgUsers.userId])
    }

    /** Recomputes cache for all members of a group. */
    fun recomputeForGroup(groupId: UUID) {
        val memberOrgUserIds = OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgGroupId eq groupId }
            .map { it[OrgUserGroups.orgUserId] }

        for (orgUserId in memberOrgUserIds) {
            recomputeForUser(orgUserId)
        }
    }

    /** Recomputes cache for all active members of an org. */
    fun recomputeForOrg(orgId: UUID) {
        val members = OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .toList()

        for (member in members) {
            computeAndWrite(member[OrgUsers.id], orgId, member[OrgUsers.userId])
        }

        log.debug("Recomputed permission cache for {} members of org {}", members.size, orgId)
    }

    private fun computeAndWrite(orgUserId: UUID, orgId: UUID, userId: UUID) {
        val cached = compute(orgUserId, orgId, userId)
        val json = cached.toJsonObject()

        OrgUsers.update({ OrgUsers.id eq orgUserId }) {
            it[permissionCache] = json
        }
    }

    internal fun compute(orgUserId: UUID, orgId: UUID, userId: UUID): CachedPermissions {
        // 1. Direct org_users permissions
        val direct = OrgUsers.selectAll()
            .where { OrgUsers.id eq orgUserId }
            .first()

        var users = direct[OrgUsers.orgUserList]
        var settings = direct[OrgUsers.orgSettings]
        var domains = direct[OrgUsers.orgDomains]
        var webhooks = direct[OrgUsers.orgWebhooks]
        var notifications = direct[OrgUsers.orgNotifications]
        var admin = direct[OrgUsers.orgAdmin]
        var workspaces = direct[OrgUsers.orgWorkspaces]

        // Registered extension sections: union of the flat maps below (max per key).
        val extraMaps = mutableListOf(OrgPermissions.shortMapFromJson(direct[OrgUsers.orgExtraPerms]))

        // 2. Union with group permissions (max per section)
        val groupIds = OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgUserId eq orgUserId }
            .map { it[OrgUserGroups.orgGroupId] }

        var totpRequired = false

        for (groupId in groupIds) {
            val group = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull() ?: continue

            users = maxOf(users, group[OrgGroups.orgUserList])
            settings = maxOf(settings, group[OrgGroups.orgSettings])
            domains = maxOf(domains, group[OrgGroups.orgDomains])
            webhooks = maxOf(webhooks, group[OrgGroups.orgWebhooks])
            notifications = maxOf(notifications, group[OrgGroups.orgNotifications])
            admin = maxOf(admin, group[OrgGroups.orgAdmin])
            workspaces = maxOf(workspaces, group[OrgGroups.orgWorkspaces])
            extraMaps.add(OrgPermissions.shortMapFromJson(group[OrgGroups.orgExtraPerms]))

            if (group[OrgGroups.totpRequired]) totpRequired = true
        }

        // Take the max level per extension-section key across direct + group maps.
        // Iterate registered sections unioned with any keys actually present so
        // unknown keys are never dropped.
        val extraKeys = LinkedHashSet(PermissionSections.registered())
        extraMaps.forEach { extraKeys += it.keys }
        val extra = extraKeys.associateWith { key ->
            extraMaps.maxOfOrNull { it[key] ?: AccessLevel.NONE } ?: AccessLevel.NONE
        }

        // 3. Check org-level TOTP
        val org = Organizations.selectAll()
            .where { Organizations.id eq orgId }
            .firstOrNull()
        if (org != null && org[Organizations.totpRequired]) totpRequired = true

        // 4. Resource permissions — union across direct + group grants
        val resources = mutableMapOf<String, Short>()

        // Direct grants (principal_type = 'org_user')
        ResourcePermissions.selectAll()
            .where {
                (ResourcePermissions.orgId eq orgId) and
                (ResourcePermissions.principalType eq "org_user") and
                (ResourcePermissions.principalId eq orgUserId)
            }
            .forEach { row ->
                val key = "${row[ResourcePermissions.resourceType]}::${row[ResourcePermissions.resourceId]}"
                val current = resources[key] ?: AccessLevel.NONE
                resources[key] = maxOf(current, row[ResourcePermissions.permissions])
            }

        // Group grants (principal_type = 'org_group')
        if (groupIds.isNotEmpty()) {
            ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                    (ResourcePermissions.principalType eq "org_group") and
                    (ResourcePermissions.principalId inList groupIds)
                }
                .forEach { row ->
                    val key = "${row[ResourcePermissions.resourceType]}::${row[ResourcePermissions.resourceId]}"
                    val current = resources[key] ?: AccessLevel.NONE
                    resources[key] = maxOf(current, row[ResourcePermissions.permissions])
                }
        }

        // 5. Hierarchy enforcement: child access implies parent read
        enforceHierarchy(resources, orgId)

        return CachedPermissions(
            org = OrgPermissions(users, settings, domains, webhooks, notifications, admin, workspaces, extra = extra),
            resources = resources,
            totpRequired = totpRequired,
        )
    }

    /**
     * Enforces resource hierarchy: access on a child implies at least read on its parent.
     * - service access → project read → workspace read
     * - project access → workspace read
     *
     * Walks all service and project entries, looks up their parents, and ensures
     * parent entries are at least READ.
     */
    private fun enforceHierarchy(resources: MutableMap<String, Short>, orgId: UUID) {
        // Collect service → project mappings
        val serviceKeys = resources.keys.filter { it.startsWith("service::") }
        for (serviceKey in serviceKeys) {
            val serviceId = UUID.fromString(serviceKey.removePrefix("service::"))
            val service = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull() ?: continue

            val projectId = service[Services.projectId]
            val projectKey = "project::$projectId"
            val currentProjectAccess = resources[projectKey] ?: AccessLevel.NONE
            if (currentProjectAccess < AccessLevel.READ) {
                resources[projectKey] = AccessLevel.READ
            }
        }

        // Collect project → workspace mappings
        val projectKeys = resources.keys.filter { it.startsWith("project::") }
        for (projectKey in projectKeys) {
            val projectId = UUID.fromString(projectKey.removePrefix("project::"))
            val project = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull() ?: continue

            val workspaceId = project[Projects.workspaceId]
            val workspaceKey = "workspace::$workspaceId"
            val currentWorkspaceAccess = resources[workspaceKey] ?: AccessLevel.NONE
            if (currentWorkspaceAccess < AccessLevel.READ) {
                resources[workspaceKey] = AccessLevel.READ
            }
        }
    }
}
