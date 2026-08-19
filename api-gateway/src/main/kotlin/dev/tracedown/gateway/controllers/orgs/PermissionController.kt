package dev.tracedown.gateway.controllers.orgs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Users
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.orgs.OrgSectionPermissions
import dev.tracedown.gateway.data.orgs.OrgUserSummary
import dev.tracedown.gateway.data.orgs.PermissionSet
import dev.tracedown.gateway.data.orgs.ResourceGrant
import dev.tracedown.gateway.data.orgs.UpdatePermissionsRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object PermissionController {

    private val validResourceTypes = setOf("workspace", "project", "service")

    /** Lists active organization members with their section levels. Requires users.read. */
    fun listUsers(orgId: UUID, requestingUserId: UUID, pfs: PfsParams): Page<OrgUserSummary> {
        return transaction {
            requireOrgRead(orgId, requestingUserId) { it.users }

            val ownerId = Organizations.selectAll()
                .where { Organizations.id eq orgId }
                .first()[Organizations.ownerId]

            // Explicit join column: OrgUsers has two FKs to Users (user_id,
            // invited_by), so the implicit join is ambiguous.
            val query = OrgUsers
                .join(Users, JoinType.INNER, OrgUsers.userId, Users.id)
                .selectAll()
                .where {
                    (OrgUsers.organizationId eq orgId) and
                    (OrgUsers.status neq "invited") and
                    (OrgUsers.deleted eq false)
                }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val rows = pagedQuery.toList()

            val orgUserIds = rows.map { it[OrgUsers.id] }
            val groupsByOrgUser: Map<UUID, List<String>> = if (orgUserIds.isEmpty()) {
                emptyMap()
            } else {
                OrgUserGroups.selectAll()
                    .where { OrgUserGroups.orgUserId inList orgUserIds }
                    .groupBy({ it[OrgUserGroups.orgUserId] }, { it[OrgUserGroups.orgGroupId].toString() })
            }

            val items = rows.map { row ->
                OrgUserSummary(
                    userId = row[OrgUsers.userId].toString(),
                    email = row[Users.email],
                    displayName = row[Users.displayName],
                    isOwner = row[OrgUsers.userId] == ownerId,
                    isActive = row[OrgUsers.isActive],
                    org = OrgSectionPermissions(
                        users = row[OrgUsers.orgUserList],
                        settings = row[OrgUsers.orgSettings],
                        domains = row[OrgUsers.orgDomains],
                        webhooks = row[OrgUsers.orgWebhooks],
                        notifications = row[OrgUsers.orgNotifications],
                        admin = row[OrgUsers.orgAdmin],
                        workspaces = row[OrgUsers.orgWorkspaces],
                        extra = OrgPermissions.shortMapFromJson(row[OrgUsers.orgExtraPerms]),
                    ),
                    groupIds = groupsByOrgUser[row[OrgUsers.id]] ?: emptyList(),
                )
            }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Fails when the target is the org owner or the requester themself. */
    private fun requireManageableTarget(orgId: UUID, targetUserId: UUID, requestingUserId: UUID) {
        if (targetUserId == requestingUserId) {
            throw BadRequestException(ErrorCodes.INVALID_REQUEST_BODY)
        }
        val ownerId = Organizations.selectAll()
            .where { Organizations.id eq orgId }
            .first()[Organizations.ownerId]
        if (targetUserId == ownerId) {
            throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
        }
    }

    /** Enables/disables a member. Disabled members keep their row but cannot access the org. */
    fun setUserActive(orgId: UUID, targetUserId: UUID, isActive: Boolean, requestingUserId: UUID) {
        transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }
            requireManageableTarget(orgId, targetUserId, requestingUserId)

            val updated = OrgUsers.update({
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq targetUserId) and
                (OrgUsers.deleted eq false)
            }) {
                it[OrgUsers.isActive] = isActive
                // The auth path only accepts status "active" — this is the lock.
                it[status] = if (isActive) "active" else "disabled"
            }
            if (updated == 0) throw NotFoundException()

            val targetName = Users.selectAll()
                .where { Users.id eq targetUserId }
                .firstOrNull()?.get(Users.displayName)

            AuditService.log(
                orgId, requestingUserId, if (isActive) "enable.user" else "disable.user", "user", targetUserId.toString(),
                entityDisplayName = targetName,
                diff = auditDiff(Triple("isActive", !isActive, isActive)),
            )
            RealtimePublisher.publish("org:$orgId", orgId, "user.updated", buildJsonObject { put("userId", targetUserId.toString()) })
        }
    }

    /** Removes a member (three-tier deletion) and their group memberships. */
    fun removeUser(orgId: UUID, targetUserId: UUID, requestingUserId: UUID) {
        transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }
            requireManageableTarget(orgId, targetUserId, requestingUserId)

            val row = OrgUsers.selectAll()
                .where {
                    (OrgUsers.organizationId eq orgId) and
                    (OrgUsers.userId eq targetUserId) and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            val now = Instant.now()
            OrgUserGroups.deleteWhere { orgUserId eq row[OrgUsers.id] }
            // Direct resource grants must not outlive the membership: they key on
            // this org_user id (no FK/cascade), so leaving them would silently
            // restore the member's old access if the id is later resurrected by a
            // re-invite — and would otherwise linger forever (the purge job never
            // clears resource_permissions).
            ResourcePermissions.deleteWhere {
                (ResourcePermissions.orgId eq orgId) and
                (ResourcePermissions.principalType eq "org_user") and
                (ResourcePermissions.principalId eq row[OrgUsers.id])
            }
            OrgUsers.update({ OrgUsers.id eq row[OrgUsers.id] }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = now
                it[isActive] = false
            }

            // If this was the user's last org, schedule the orphaned account for deletion.
            AccountLifecycle.reconcile(targetUserId, now)

            val targetName = Users.selectAll()
                .where { Users.id eq targetUserId }
                .firstOrNull()?.get(Users.displayName)

            AuditService.log(
                orgId, requestingUserId, "remove.user", "user", targetUserId.toString(),
                entityDisplayName = targetName,
            )
            RealtimePublisher.publish("org:$orgId", orgId, "user.removed", buildJsonObject { put("userId", targetUserId.toString()) })
        }
    }

    /** Returns the full permission set for a user in the org. */
    fun getUserPermissions(orgId: UUID, userId: UUID, requestingUserId: UUID): PermissionSet {
        return transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }

            val orgUser = findOrgUser(orgId, userId)
            val orgPerms = OrgSectionPermissions(
                users = orgUser[OrgUsers.orgUserList],
                settings = orgUser[OrgUsers.orgSettings],
                domains = orgUser[OrgUsers.orgDomains],
                webhooks = orgUser[OrgUsers.orgWebhooks],
                notifications = orgUser[OrgUsers.orgNotifications],
                admin = orgUser[OrgUsers.orgAdmin],
                workspaces = orgUser[OrgUsers.orgWorkspaces],
                extra = OrgPermissions.shortMapFromJson(orgUser[OrgUsers.orgExtraPerms]),
            )

            val resources = ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                    (ResourcePermissions.principalType eq "org_user") and
                    (ResourcePermissions.principalId eq orgUser[OrgUsers.id])
                }
                .map { resourceGrantFromRow(it) }

            PermissionSet(orgPerms, resources)
        }
    }

    /**
     * Updates a user's permissions. Org section fields update directly on org_users.
     * Resource grants are diff-based: adds new, updates changed, removes missing.
     */
    fun updateUserPermissions(orgId: UUID, userId: UUID, request: UpdatePermissionsRequest, requestingUserId: UUID): PermissionSet {
        return transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }

            val orgUser = findOrgUser(orgId, userId)
            val orgUserId = orgUser[OrgUsers.id]

            if (request.org != null) {
                validateOrgSections(request.org)
                OrgUsers.update({ OrgUsers.id eq orgUserId }) {
                    it[orgUserList] = request.org.users
                    it[orgSettings] = request.org.settings
                    it[orgDomains] = request.org.domains
                    it[orgWebhooks] = request.org.webhooks
                    it[orgNotifications] = request.org.notifications
                    it[orgAdmin] = request.org.admin
                    it[orgWorkspaces] = request.org.workspaces
                    it[orgExtraPerms] = extraToJson(request.org.extra)
                }
            }

            if (request.resources != null) {
                syncResourceGrants(orgId, "org_user", orgUserId, request.resources)
            }

            PermissionCacheService.recomputeForUser(orgUserId)

            val targetName = Users.selectAll()
                .where { Users.id eq userId }
                .firstOrNull()?.get(Users.displayName)

            AuditService.log(
                orgId, requestingUserId, "update.permissions", "user", userId.toString(),
                entityDisplayName = targetName,
                diff = request.org?.let {
                    auditDiff(
                        Triple("users", orgUser[OrgUsers.orgUserList], it.users),
                        Triple("settings", orgUser[OrgUsers.orgSettings], it.settings),
                        Triple("domains", orgUser[OrgUsers.orgDomains], it.domains),
                        Triple("webhooks", orgUser[OrgUsers.orgWebhooks], it.webhooks),
                        Triple("notifications", orgUser[OrgUsers.orgNotifications], it.notifications),
                        Triple("admin", orgUser[OrgUsers.orgAdmin], it.admin),
                        Triple("workspaces", orgUser[OrgUsers.orgWorkspaces], it.workspaces),
                    )
                },
                comment = if (request.resources != null) "resource grants replaced" else null,
            )
            RealtimePublisher.publish("org:$orgId", orgId, "user.permissions.updated", buildJsonObject { put("userId", userId.toString()) })

            getUserPermissions(orgId, userId, requestingUserId)
        }
    }

    /** Returns the full permission set for a group. */
    fun getGroupPermissions(orgId: UUID, groupId: UUID, requestingUserId: UUID): PermissionSet {
        return transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }

            val group = OrgGroups.selectAll()
                .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
                .firstOrNull()
                ?: throw NotFoundException()

            val orgPerms = OrgSectionPermissions(
                users = group[OrgGroups.orgUserList],
                settings = group[OrgGroups.orgSettings],
                domains = group[OrgGroups.orgDomains],
                webhooks = group[OrgGroups.orgWebhooks],
                notifications = group[OrgGroups.orgNotifications],
                admin = group[OrgGroups.orgAdmin],
                workspaces = group[OrgGroups.orgWorkspaces],
                extra = OrgPermissions.shortMapFromJson(group[OrgGroups.orgExtraPerms]),
            )

            val resources = ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                    (ResourcePermissions.principalType eq "org_group") and
                    (ResourcePermissions.principalId eq groupId)
                }
                .map { resourceGrantFromRow(it) }

            PermissionSet(orgPerms, resources)
        }
    }

    /**
     * Updates a group's permissions. Org section fields update directly on org_groups.
     * Resource grants are diff-based.
     */
    fun updateGroupPermissions(orgId: UUID, groupId: UUID, request: UpdatePermissionsRequest, requestingUserId: UUID): PermissionSet {
        return transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }

            val group = OrgGroups.selectAll()
                .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
                .firstOrNull()
                ?: throw NotFoundException()

            if (request.org != null) {
                validateOrgSections(request.org)
                OrgGroups.update({ OrgGroups.id eq groupId }) {
                    it[orgUserList] = request.org.users
                    it[orgSettings] = request.org.settings
                    it[orgDomains] = request.org.domains
                    it[orgWebhooks] = request.org.webhooks
                    it[orgNotifications] = request.org.notifications
                    it[orgAdmin] = request.org.admin
                    it[orgWorkspaces] = request.org.workspaces
                    it[orgExtraPerms] = extraToJson(request.org.extra)
                }
            }

            if (request.resources != null) {
                syncResourceGrants(orgId, "org_group", groupId, request.resources)
            }

            PermissionCacheService.recomputeForGroup(groupId)

            AuditService.log(
                orgId, requestingUserId, "update.permissions", "group", groupId.toString(),
                entityDisplayName = group[OrgGroups.name],
                diff = request.org?.let {
                    auditDiff(
                        Triple("users", group[OrgGroups.orgUserList], it.users),
                        Triple("settings", group[OrgGroups.orgSettings], it.settings),
                        Triple("domains", group[OrgGroups.orgDomains], it.domains),
                        Triple("webhooks", group[OrgGroups.orgWebhooks], it.webhooks),
                        Triple("notifications", group[OrgGroups.orgNotifications], it.notifications),
                        Triple("admin", group[OrgGroups.orgAdmin], it.admin),
                        Triple("workspaces", group[OrgGroups.orgWorkspaces], it.workspaces),
                    )
                },
                comment = if (request.resources != null) "resource grants replaced" else null,
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.updated", buildJsonObject { put("groupId", groupId.toString()) })

            getGroupPermissions(orgId, groupId, requestingUserId)
        }
    }

    // ── Internals ──

    /**
     * Invited (not yet accepted) members are included — permissions can be
     * pre-configured like group memberships; everything gates on active
     * status until acceptance.
     */
    private fun findOrgUser(orgId: UUID, userId: UUID): org.jetbrains.exposed.sql.ResultRow {
        return OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq userId) and
                (OrgUsers.status inList listOf("active", "invited")) and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull()
            ?: throw NotFoundException()
    }

    /**
     * Syncs resource permission grants to match the desired list (diff-based).
     * Deletes all existing grants for the principal and inserts the new set.
     */
    private fun syncResourceGrants(orgId: UUID, principalType: String, principalId: UUID, desired: List<ResourceGrant>) {
        for (grant in desired) {
            if (grant.resourceType !in validResourceTypes) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
            if (grant.permissions !in 0..2) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
        }

        // Delete existing grants for this principal
        ResourcePermissions.deleteWhere {
            (ResourcePermissions.orgId eq orgId) and
            (ResourcePermissions.principalType eq principalType) and
            (ResourcePermissions.principalId eq principalId)
        }

        // Insert new grants (skip none/0 — no point storing them)
        for (grant in desired) {
            if (grant.permissions <= 0) continue
            val resourceId = try { UUID.fromString(grant.resourceId) } catch (e: Exception) {
                throw BadRequestException(ErrorCodes.INVALID_UUID)
            }
            ResourcePermissions.insert {
                it[id] = UUID.randomUUID()
                it[ResourcePermissions.orgId] = orgId
                it[ResourcePermissions.principalType] = principalType
                it[ResourcePermissions.principalId] = principalId
                it[resourceType] = grant.resourceType
                it[ResourcePermissions.resourceId] = resourceId
                it[permissions] = grant.permissions
            }
        }
    }

    private fun validateOrgSections(sections: OrgSectionPermissions) {
        val levels = listOf(
            sections.users, sections.settings, sections.domains, sections.webhooks,
            sections.notifications, sections.admin, sections.workspaces,
        ) + sections.extra.values
        levels.forEach {
            if (it !in 0..2) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    /** Serializes registered extension-section grants to the flat JSONB map. */
    private fun extraToJson(extra: Map<String, Short>): JsonObject = buildJsonObject {
        for ((key, level) in extra) {
            put(key, level.toInt())
        }
    }

    private fun resourceGrantFromRow(row: org.jetbrains.exposed.sql.ResultRow) = ResourceGrant(
        resourceType = row[ResourcePermissions.resourceType],
        resourceId = row[ResourcePermissions.resourceId].toString(),
        permissions = row[ResourcePermissions.permissions],
    )
}
