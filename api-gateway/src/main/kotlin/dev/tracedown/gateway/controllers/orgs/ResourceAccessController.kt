package dev.tracedown.gateway.controllers.orgs

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Users
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.data.orgs.ResourceAccessEntry
import dev.tracedown.gateway.data.orgs.UpsertAccessRequest
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.requireCachedPermissions
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Resource-scoped access management: who (users/groups) holds a grant on a
 * workspace, project or service. The complement of the principal-scoped
 * editing in [PermissionController] — same `resource_permissions` rows.
 *
 * All operations require write access to the resource itself.
 */
object ResourceAccessController {

    /** Resolves the resource within the org and returns its ancestor chain. */
    private fun parentChain(resourceType: String, resourceId: UUID, orgId: UUID): List<String> =
        when (resourceType) {
            "workspace" -> {
                ResourceResolver.resolveWorkspace(resourceId, orgId)
                emptyList()
            }
            "project" -> {
                val ctx = ResourceResolver.resolveProject(resourceId, orgId)
                listOf("workspace::${ctx.workspaceId}")
            }
            "service" -> {
                val ctx = ResourceResolver.resolveService(resourceId, orgId)
                listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
            }
            else -> throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

    /**
     * Write access to the resource itself — the right to hand out access to it.
     * [PermissionController] shares this: editing a principal's grants is the
     * same act from the other end, so it takes the same guard.
     */
    internal fun requireResourceWrite(orgId: UUID, userId: UUID, resourceType: String, resourceId: UUID) {
        val chain = parentChain(resourceType, resourceId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canWriteResource(cached, resourceType, resourceId, chain)) {
            throw NotFoundException()
        }
    }

    fun list(orgId: UUID, resourceType: String, resourceId: UUID, requestingUserId: UUID): List<ResourceAccessEntry> {
        return transaction {
            requireResourceWrite(orgId, requestingUserId, resourceType, resourceId)

            val rows = ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                    (ResourcePermissions.resourceType eq resourceType) and
                    (ResourcePermissions.resourceId eq resourceId) and
                    (ResourcePermissions.permissions greaterEq 1)
                }
                .toList()

            val userLevels = rows.filter { it[ResourcePermissions.principalType] == "org_user" }
                .associate { it[ResourcePermissions.principalId] to it[ResourcePermissions.permissions] }
            val groupLevels = rows.filter { it[ResourcePermissions.principalType] == "org_group" }
                .associate { it[ResourcePermissions.principalId] to it[ResourcePermissions.permissions] }

            val groups = if (groupLevels.isEmpty()) emptyList() else OrgGroups.selectAll()
                .where { OrgGroups.id inList groupLevels.keys }
                .map { row ->
                    ResourceAccessEntry(
                        principalType = "group",
                        principalId = row[OrgGroups.id].toString(),
                        name = row[OrgGroups.name],
                        permissions = groupLevels.getValue(row[OrgGroups.id]),
                    )
                }

            val users = if (userLevels.isEmpty()) emptyList() else OrgUsers
                .join(Users, JoinType.INNER, OrgUsers.userId, Users.id)
                .selectAll()
                .where { (OrgUsers.id inList userLevels.keys) and (OrgUsers.deleted eq false) }
                .map { row ->
                    ResourceAccessEntry(
                        principalType = "user",
                        principalId = row[OrgUsers.userId].toString(),
                        name = row[Users.displayName],
                        email = row[Users.email],
                        permissions = userLevels.getValue(row[OrgUsers.id]),
                    )
                }

            groups.sortedBy { it.name } + users.sortedBy { it.name }
        }
    }

    fun upsert(orgId: UUID, resourceType: String, resourceId: UUID, request: UpsertAccessRequest, requestingUserId: UUID) {
        if (request.permissions !in 1..2) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        transaction {
            requireResourceWrite(orgId, requestingUserId, resourceType, resourceId)
            val (storedType, storedId) = resolvePrincipal(orgId, request.principalType, request.principalId)

            val oldLevel = ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                    (ResourcePermissions.principalType eq storedType) and
                    (ResourcePermissions.principalId eq storedId) and
                    (ResourcePermissions.resourceType eq resourceType) and
                    (ResourcePermissions.resourceId eq resourceId)
                }
                .firstOrNull()?.get(ResourcePermissions.permissions)

            ResourcePermissions.deleteWhere {
                (ResourcePermissions.orgId eq orgId) and
                (principalType eq storedType) and
                (principalId eq storedId) and
                (ResourcePermissions.resourceType eq resourceType) and
                (ResourcePermissions.resourceId eq resourceId)
            }
            ResourcePermissions.insert {
                it[id] = UUID.randomUUID()
                it[ResourcePermissions.orgId] = orgId
                it[principalType] = storedType
                it[principalId] = storedId
                it[ResourcePermissions.resourceType] = resourceType
                it[ResourcePermissions.resourceId] = resourceId
                it[permissions] = request.permissions
            }

            recomputeAndPublish(orgId, storedType, storedId, request.principalId)
            AuditService.log(
                orgId, requestingUserId, "grant.access", resourceType, resourceId.toString(),
                entityDisplayName = null,
                diff = auditDiff(Triple("level", oldLevel ?: "none", request.permissions)),
                comment = "${request.principalType} ${request.principalId}",
            )
            publishAccessChanged(orgId, resourceType, resourceId)
        }
    }

    fun remove(orgId: UUID, resourceType: String, resourceId: UUID, principalType: String, principalId: String, requestingUserId: UUID) {
        transaction {
            requireResourceWrite(orgId, requestingUserId, resourceType, resourceId)
            val (storedType, storedId) = resolvePrincipal(orgId, principalType, principalId)

            val deleted = ResourcePermissions.deleteWhere {
                (ResourcePermissions.orgId eq orgId) and
                (ResourcePermissions.principalType eq storedType) and
                (ResourcePermissions.principalId eq storedId) and
                (ResourcePermissions.resourceType eq resourceType) and
                (ResourcePermissions.resourceId eq resourceId)
            }
            if (deleted == 0) throw NotFoundException()

            recomputeAndPublish(orgId, storedType, storedId, principalId)
            AuditService.log(
                orgId, requestingUserId, "revoke.access", resourceType, resourceId.toString(),
                entityDisplayName = null,
                comment = "$principalType $principalId",
            )
            publishAccessChanged(orgId, resourceType, resourceId)
        }
    }

    /** Maps the API's user/group ids onto stored principal rows. */
    private fun resolvePrincipal(orgId: UUID, principalType: String, principalId: String): Pair<String, UUID> {
        val id = parseUuid(principalId, "principal ID")
        return when (principalType) {
            "user" -> {
                val orgUser = OrgUsers.selectAll()
                    .where {
                        (OrgUsers.organizationId eq orgId) and
                        (OrgUsers.userId eq id) and
                        (OrgUsers.deleted eq false)
                    }
                    .firstOrNull() ?: throw NotFoundException()
                "org_user" to orgUser[OrgUsers.id]
            }
            "group" -> {
                OrgGroups.selectAll()
                    .where { (OrgGroups.id eq id) and (OrgGroups.organizationId eq orgId) }
                    .firstOrNull() ?: throw NotFoundException()
                "org_group" to id
            }
            else -> throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    private fun recomputeAndPublish(orgId: UUID, storedType: String, storedId: UUID, apiPrincipalId: String) {
        if (storedType == "org_user") {
            PermissionCacheService.recomputeForUser(storedId)
            RealtimePublisher.publish("org:$orgId", orgId, "user.permissions.updated",
                buildJsonObject { put("userId", apiPrincipalId) })
        } else {
            PermissionCacheService.recomputeForGroup(storedId)
            RealtimePublisher.publish("org:$orgId", orgId, "group.updated",
                buildJsonObject { put("groupId", apiPrincipalId) })
        }
    }

    private fun publishAccessChanged(orgId: UUID, resourceType: String, resourceId: UUID) {
        RealtimePublisher.publish("org:$orgId", orgId, "access.changed", buildJsonObject {
            put("resourceType", resourceType)
            put("resourceId", resourceId.toString())
        })
    }
}
