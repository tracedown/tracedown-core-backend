package dev.tracedown.gateway.controllers.orgs
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.pfs.toPage
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.data.orgs.GroupMember
import dev.tracedown.gateway.data.orgs.GroupSummary
import dev.tracedown.gateway.data.orgs.UpdateGroupRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.GrantPolicy
import dev.tracedown.gateway.util.orgGroupSections
import dev.tracedown.gateway.util.requireGrantable
import dev.tracedown.gateway.util.requireGroupGrantable
import dev.tracedown.gateway.util.requireOrgPolicyWrite
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

object GroupController {

    /** Creates a new group in the organization with all permissions set to NONE. */
    @Injectable("group.create")
    fun createGroup(orgId: UUID, name: String, requestingUserId: UUID): GroupSummary {
        if (name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)

        // Transaction-scoped: a registered before-hook can count existing groups
        // and block atomically with the insert below.
        return Interceptors.injectableInTx("group.create", InterceptorContext(orgId = orgId, userId = requestingUserId)) {
            requireOrgWrite(orgId, requestingUserId) { it.users }

            val exists = OrgGroups.selectAll()
                .where { (OrgGroups.organizationId eq orgId) and (OrgGroups.name eq name) }
                .any()
            if (exists) throw ConflictException()

            val groupId = UUID.randomUUID()
            OrgGroups.insert {
                it[id] = groupId
                it[organizationId] = orgId
                it[OrgGroups.name] = name
            }

            AuditService.log(orgId, requestingUserId, "create.group", "group", groupId.toString(), entityDisplayName = name)
            OutboxEmit.emitResourceEvent(
                "resource.group.created", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.created", buildJsonObject { put("groupId", groupId.toString()) })

            groupSummary(groupId)
        }
    }

    /** Lists all groups in the organization with member counts. */
    fun listGroups(orgId: UUID, requestingUserId: UUID, pfs: PfsParams): Page<GroupSummary> {
        return transaction {
            requireOrgRead(orgId, requestingUserId) { it.users }

            val query = OrgGroups.selectAll()
                .where { OrgGroups.organizationId eq orgId }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row -> groupSummaryFromRow(row) }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns details of a single group. */
    fun getGroup(orgId: UUID, groupId: UUID, requestingUserId: UUID): GroupSummary {
        return transaction {
            requireOrgRead(orgId, requestingUserId) { it.users }
            groupSummary(groupId, orgId)
        }
    }

    /**
     * Updates a group's name and/or permission levels.
     * Only non-null fields in the request are applied.
     * Access levels are validated to be 0 (none), 1 (read), or 2 (write).
     */
    fun updateGroup(orgId: UUID, groupId: UUID, request: UpdateGroupRequest, requestingUserId: UUID): GroupSummary {
        return transaction {
            val caller = requireOrgWrite(orgId, requestingUserId) { it.users }

            val group = OrgGroups.selectAll()
                .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
                .firstOrNull()
                ?: throw NotFoundException()

            // Validate access levels
            listOfNotNull(request.users, request.settings, request.domains, request.webhooks, request.notifications, request.admin, request.workspaces).forEach { level ->
                if (level !in 0..2) throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }

            // A group is a grant with an indirection: raising it raises everyone
            // in it. Same rule as writing the levels onto a member directly.
            requireGrantable(caller, orgGroupSections(group), requestedSections(request))

            // TOTP enrolment enforcement is org policy, exactly like the
            // org-level toggle in OrgSettingsController — admin write, not
            // users write.
            if (request.totpRequired != null && request.totpRequired != group[OrgGroups.totpRequired]) {
                requireOrgPolicyWrite(caller)
            }

            // Check name uniqueness if changing
            if (request.name != null && request.name != group[OrgGroups.name]) {
                if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (request.name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
                val nameExists = OrgGroups.selectAll()
                    .where {
                        (OrgGroups.organizationId eq orgId) and
                        (OrgGroups.name eq request.name) and
                        (OrgGroups.id neq groupId)
                    }
                    .any()
                if (nameExists) throw ConflictException()
            }

            // totpRequired is cached in the permission blob (drives login enforcement),
            // so a change to it also needs a recompute for the group's members.
            val permissionsChanged = listOfNotNull(
                request.users, request.settings, request.domains, request.webhooks, request.notifications, request.admin, request.workspaces,
            ).isNotEmpty() || request.totpRequired != null

            val old = OrgGroups.selectAll().where { OrgGroups.id eq groupId }.first()

            OrgGroups.update({ OrgGroups.id eq groupId }) {
                request.name?.let { v -> it[name] = v }
                request.users?.let { v -> it[orgUserList] = v }
                request.settings?.let { v -> it[orgSettings] = v }
                request.domains?.let { v -> it[orgDomains] = v }
                request.webhooks?.let { v -> it[orgWebhooks] = v }
                request.notifications?.let { v -> it[orgNotifications] = v }
                request.admin?.let { v -> it[orgAdmin] = v }
                request.workspaces?.let { v -> it[orgWorkspaces] = v }
                request.totpRequired?.let { v -> it[totpRequired] = v }
            }

            if (permissionsChanged) {
                PermissionCacheService.recomputeForGroup(groupId)
            }

            AuditService.log(
                orgId, requestingUserId, "update.group", "group", groupId.toString(),
                entityDisplayName = old[OrgGroups.name],
                diff = auditDiff(
                    Triple("name", old[OrgGroups.name], request.name ?: old[OrgGroups.name]),
                    Triple("users", old[OrgGroups.orgUserList], request.users ?: old[OrgGroups.orgUserList]),
                    Triple("settings", old[OrgGroups.orgSettings], request.settings ?: old[OrgGroups.orgSettings]),
                    Triple("domains", old[OrgGroups.orgDomains], request.domains ?: old[OrgGroups.orgDomains]),
                    Triple("webhooks", old[OrgGroups.orgWebhooks], request.webhooks ?: old[OrgGroups.orgWebhooks]),
                    Triple("notifications", old[OrgGroups.orgNotifications], request.notifications ?: old[OrgGroups.orgNotifications]),
                    Triple("admin", old[OrgGroups.orgAdmin], request.admin ?: old[OrgGroups.orgAdmin]),
                    Triple("workspaces", old[OrgGroups.orgWorkspaces], request.workspaces ?: old[OrgGroups.orgWorkspaces]),
                ),
            )
            OutboxEmit.emitResourceEvent(
                "resource.group.updated", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.updated", buildJsonObject { put("groupId", groupId.toString()) })

            groupSummary(groupId)
        }
    }

    /**
     * Deletes a group and all its memberships. Hard delete — groups have no
     * soft-delete.
     *
     * A delete is strictly de-escalating, so [GrantPolicy]'s first rule — nobody
     * hands out what they do not hold — never objects. Its second rule does, and
     * must: deleting a group that carries `admin` takes org admin away from
     * everyone in it at once, and that is the same write [updateGroup] performs
     * when it sets the admin section to `none`, where admin write is required.
     * Without the check here the delete button is simply the cheaper route to
     * the identical outcome — a `users` writer emptying the org of admins.
     *
     * Expressed as what the delete writes: every section this group carries goes
     * to [AccessLevel.NONE]. A group carrying no admin is unaffected, since a
     * section already at its requested level is not a grant.
     */
    fun deleteGroup(orgId: UUID, groupId: UUID, requestingUserId: UUID) {
        transaction {
            val caller = requireOrgWrite(orgId, requestingUserId) { it.users }

            val group = OrgGroups.selectAll()
                .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
                .firstOrNull() ?: throw NotFoundException()
            val groupName = group[OrgGroups.name]

            val carried = orgGroupSections(group)
            requireGrantable(caller, carried, carried.mapValues { AccessLevel.NONE })

            // Collect affected users before deletion
            val affectedOrgUserIds = OrgUserGroups.selectAll()
                .where { OrgUserGroups.orgGroupId eq groupId }
                .map { it[OrgUserGroups.orgUserId] }

            OrgUserGroups.deleteWhere { orgGroupId eq groupId }
            OrgGroups.deleteWhere { id eq groupId }

            for (orgUserId in affectedOrgUserIds) {
                PermissionCacheService.recomputeForUser(orgUserId)
            }

            AuditService.log(orgId, requestingUserId, "delete.group", "group", groupId.toString(), entityDisplayName = groupName)
            OutboxEmit.emitResourceEvent(
                "resource.group.deleted", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.deleted", buildJsonObject { put("groupId", groupId.toString()) })
        }
    }

    /** Adds an org member to a group. The user must be an active member of the organization. */
    fun addMember(orgId: UUID, groupId: UUID, userId: UUID, requestingUserId: UUID) {
        transaction {
            val caller = requireOrgWrite(orgId, requestingUserId) { it.users }
            val orgUserId = resolveOrgUserId(orgId, groupId, userId)
            requireGroupGrantable(caller, groupSections(orgId, groupId))

            val already = OrgUserGroups.selectAll()
                .where { (OrgUserGroups.orgUserId eq orgUserId) and (OrgUserGroups.orgGroupId eq groupId) }
                .any()
            if (already) throw ConflictException()

            OrgUserGroups.insert {
                it[id] = UUID.randomUUID()
                it[OrgUserGroups.orgUserId] = orgUserId
                it[orgGroupId] = groupId
            }

            PermissionCacheService.recomputeForUser(orgUserId)

            val groupName = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()?.get(OrgGroups.name)
            AuditService.log(orgId, requestingUserId, "add.member", "group", groupId.toString(), entityDisplayName = groupName, comment = "Added user $userId")
            OutboxEmit.emitResourceEvent(
                "resource.group.updated", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.members.updated", buildJsonObject { put("groupId", groupId.toString()) })
        }
    }

    /** Lists all members of a group. */
    fun listMembers(orgId: UUID, groupId: UUID, requestingUserId: UUID, pfs: PfsParams): Page<GroupMember> {
        return transaction {
            requireOrgRead(orgId, requestingUserId) { it.users }
            requireGroupExists(orgId, groupId)

            queryGroupMembers(groupId).toPage(pfs)
        }
    }

    /**
     * Syncs group membership to match the provided user ID list.
     * Computes diff against current members: adds missing, removes extra.
     * Each added user must be an active org member.
     * Returns the updated member list.
     */
    fun syncMembers(orgId: UUID, groupId: UUID, userIds: List<UUID>, requestingUserId: UUID): List<GroupMember> {
        return transaction {
            val caller = requireOrgWrite(orgId, requestingUserId) { it.users }
            requireGroupExists(orgId, groupId)

            // Current members: orgUserId → userId
            val currentMappings = OrgUserGroups.selectAll()
                .where { OrgUserGroups.orgGroupId eq groupId }
                .map { it[OrgUserGroups.orgUserId] }
                .toSet()

            val currentUserIds = currentMappings.mapNotNull { orgUserId ->
                OrgUsers.selectAll()
                    .where { OrgUsers.id eq orgUserId }
                    .firstOrNull()?.get(OrgUsers.userId)
            }.toSet()

            val desiredUserIds = userIds.toSet()

            // Remove users no longer in the list
            val toRemove = currentUserIds - desiredUserIds
            val affectedOrgUserIds = mutableSetOf<UUID>()
            for (uid in toRemove) {
                val orgUserId = findOrgUserId(orgId, uid) ?: continue
                OrgUserGroups.deleteWhere {
                    (OrgUserGroups.orgUserId eq orgUserId) and (orgGroupId eq groupId)
                }
                affectedOrgUserIds.add(orgUserId)
            }

            // Add users not yet in the group
            val toAdd = desiredUserIds - currentUserIds
            // Removal-only syncs need no grant check — taking a group away
            // hands out nothing.
            if (toAdd.isNotEmpty()) {
                requireGroupGrantable(caller, groupSections(orgId, groupId))
            }
            for (uid in toAdd) {
                val orgUserId = resolveOrgUserId(orgId, groupId, uid)
                OrgUserGroups.insert {
                    it[id] = UUID.randomUUID()
                    it[OrgUserGroups.orgUserId] = orgUserId
                    it[orgGroupId] = groupId
                }
                affectedOrgUserIds.add(orgUserId)
            }

            for (orgUserId in affectedOrgUserIds) {
                PermissionCacheService.recomputeForUser(orgUserId)
            }

            val groupName = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()?.get(OrgGroups.name)
            AuditService.log(orgId, requestingUserId, "sync.members", "group", groupId.toString(), entityDisplayName = groupName)
            OutboxEmit.emitResourceEvent(
                "resource.group.updated", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.members.updated", buildJsonObject { put("groupId", groupId.toString()) })

            queryGroupMembers(groupId)
        }
    }

    /** Removes a user from a group. */
    fun removeMember(orgId: UUID, groupId: UUID, userId: UUID, requestingUserId: UUID) {
        transaction {
            requireOrgWrite(orgId, requestingUserId) { it.users }
            requireGroupExists(orgId, groupId)

            val orgUserId = findOrgUserId(orgId, userId)
                ?: throw NotFoundException()

            val deleted = OrgUserGroups.deleteWhere {
                (OrgUserGroups.orgUserId eq orgUserId) and (orgGroupId eq groupId)
            }
            if (deleted == 0) throw NotFoundException()

            PermissionCacheService.recomputeForUser(orgUserId)

            val groupName = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()?.get(OrgGroups.name)
            AuditService.log(orgId, requestingUserId, "remove.member", "group", groupId.toString(), entityDisplayName = groupName, comment = "Removed user $userId")
            OutboxEmit.emitResourceEvent(
                "resource.group.updated", "group", groupId,
                buildJsonObject { put("id", groupId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "group.members.updated", buildJsonObject { put("groupId", groupId.toString()) })
        }
    }

    // ── Internals ──

    private fun groupSummary(groupId: UUID, orgId: UUID? = null): GroupSummary {
        val row = if (orgId != null) {
            OrgGroups.selectAll()
                .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
                .firstOrNull()
        } else {
            OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()
        } ?: throw NotFoundException()

        return groupSummaryFromRow(row)
    }

    private fun groupSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow): GroupSummary {
        val groupId = row[OrgGroups.id]
        val memberCount = OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgGroupId eq groupId }
            .count().toInt()

        return GroupSummary(
            id = groupId.toString(),
            name = row[OrgGroups.name],
            users = row[OrgGroups.orgUserList],
            settings = row[OrgGroups.orgSettings],
            domains = row[OrgGroups.orgDomains],
            webhooks = row[OrgGroups.orgWebhooks],
            notifications = row[OrgGroups.orgNotifications],
            admin = row[OrgGroups.orgAdmin],
            workspaces = row[OrgGroups.orgWorkspaces],
            totpRequired = row[OrgGroups.totpRequired],
            memberCount = memberCount,
        )
    }

    private fun queryGroupMembers(groupId: UUID): List<GroupMember> {
        return OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgGroupId eq groupId }
            .mapNotNull { membership ->
                val orgUser = OrgUsers.selectAll()
                    .where { OrgUsers.id eq membership[OrgUserGroups.orgUserId] }
                    .firstOrNull() ?: return@mapNotNull null
                val user = Users.selectAll()
                    .where { Users.id eq orgUser[OrgUsers.userId] }
                    .firstOrNull() ?: return@mapNotNull null

                GroupMember(
                    userId = user[Users.id].toString(),
                    email = user[Users.email],
                    displayName = user[Users.displayName],
                )
            }
    }

    /** The section levels a group carries — what joining it would grant. */
    private fun groupSections(orgId: UUID, groupId: UUID): Map<String, Short> {
        val row = OrgGroups.selectAll()
            .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
            .firstOrNull()
            ?: throw NotFoundException()
        return orgGroupSections(row)
    }

    /** The section levels this request would write, omitting the fields it leaves alone. */
    private fun requestedSections(request: UpdateGroupRequest): Map<String, Short> = buildMap {
        request.users?.let { put("users", it) }
        request.settings?.let { put("settings", it) }
        request.domains?.let { put("domains", it) }
        request.webhooks?.let { put("webhooks", it) }
        request.notifications?.let { put("notifications", it) }
        request.admin?.let { put("admin", it) }
        request.workspaces?.let { put("workspaces", it) }
    }

    private fun requireGroupExists(orgId: UUID, groupId: UUID) {
        val exists = OrgGroups.selectAll()
            .where { (OrgGroups.id eq groupId) and (OrgGroups.organizationId eq orgId) }
            .any()
        if (!exists) throw NotFoundException()
    }

    /**
     * Resolves the org_users.id for a userId in the given org. Invited (not
     * yet accepted) members are eligible — groups can be pre-assigned so the
     * user lands fully provisioned; the dispatcher and login both gate on
     * active status, so nothing leaks before acceptance.
     */
    private fun resolveOrgUserId(orgId: UUID, groupId: UUID, userId: UUID): UUID {
        requireGroupExists(orgId, groupId)

        val orgUser = OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq userId) and
                (OrgUsers.status inList listOf("active", "invited")) and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull()
            ?: throw BadRequestException(ErrorCodes.NOT_ORG_MEMBER)

        return orgUser[OrgUsers.id]
    }

    /**
     * Like [resolveOrgUserId] but nullable and without the group-existence
     * check — used by the removal paths. Invited members are eligible for the
     * same reason they are in [resolveOrgUserId]: a group pre-assigned to a
     * pending invite must be removable without revoking the invite.
     */
    private fun findOrgUserId(orgId: UUID, userId: UUID): UUID? {
        return OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq userId) and
                (OrgUsers.status inList listOf("active", "invited")) and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull()?.get(OrgUsers.id)
    }
}
