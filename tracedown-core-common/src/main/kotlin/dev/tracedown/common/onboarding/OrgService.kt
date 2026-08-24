package dev.tracedown.common.onboarding

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.config.OrgConfig
import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.common.variables.SystemVariableSeeder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

object OrgService {

    private val log = LoggerFactory.getLogger(OrgService::class.java)

    data class CreateOrgResult(
        val orgId: UUID,
        val workspaceId: UUID,
        val orgUserId: UUID,
    )

    /**
     * Creates an organization with default groups, assigns the owner to the
     * first admin-level group, and creates a "Default" workspace.
     *
     * Called by bootstrap (single-org mode) and can be invoked programmatically for multi-org setups.
     */
    @Injectable("org.create")
    fun createOrg(
        name: String,
        ownerId: UUID,
        defaultGroups: List<DefaultGroupConfig>,
    ): CreateOrgResult {
        return Interceptors.injectable("org.create", InterceptorContext(userId = ownerId)) {
        transaction {
            val now = Instant.now()
            val orgId = UUID.randomUUID()
            val workspaceId = UUID.randomUUID()
            val orgUserId = UUID.randomUUID()

            // 1. Create organization
            Organizations.insert {
                it[id] = orgId
                it[Organizations.name] = name
                it[Organizations.ownerId] = ownerId
                it[deleted] = false
                it[createdAt] = now
            }
            OutboxEmit.emitResourceEvent(
                "resource.org.created", "org", orgId,
                buildJsonObject { put("id", orgId.toString()); put("ownerId", ownerId.toString()) },
            )

            // Mint the org's data-encryption key up front so secret variables
            // encrypt under it from the first write. Best effort: a caller that
            // has not initialized the platform key can still create orgs — the
            // DEK is then minted lazily on the first secret write.
            VariableCrypto.mintOrgKeyIfInitialized(orgId)

            // Starter templates for the org, from the bootstrap seam: the built-in
            // hardcoded set by default, or whatever provider a host has registered.
            OrgBootstrapSeeder.seedForOrg(orgId, ownerId)

            // 2. Create org membership for owner (full permissions)
            OrgUsers.insert {
                it[id] = orgUserId
                it[organizationId] = orgId
                it[userId] = ownerId
                it[joinedAt] = now
                it[status] = "active"
                it[deleted] = false
                it[orgUserList] = AccessLevel.WRITE
                it[orgSettings] = AccessLevel.WRITE
                it[orgDomains] = AccessLevel.WRITE
                it[orgWebhooks] = AccessLevel.WRITE
                it[orgNotifications] = AccessLevel.WRITE
                it[orgAdmin] = AccessLevel.WRITE
                it[orgWorkspaces] = AccessLevel.WRITE
                it[inviteToken] = ""
            }
            OutboxEmit.emitResourceEvent(
                "resource.membership.created", "membership", orgUserId,
                buildJsonObject { put("id", orgUserId.toString()); put("orgId", orgId.toString()); put("userId", ownerId.toString()) },
            )

            // 3. Set selectedOrgId if not already set (first org for this user)
            val currentSelected = Users.selectAll()
                .where { Users.id eq ownerId }
                .first()[Users.selectedOrgId]
            if (currentSelected == null) {
                Users.update({ Users.id eq ownerId }) {
                    it[selectedOrgId] = orgId
                }
            }

            // 4. Create default groups (filtered through platform config for extensibility)
            val groupDefs = PlatformDefaults.orgConfig.filterDefaultGroups(
                defaultGroups.map {
                    OrgConfig.GroupDef(it.name, it.users, it.settings, it.domains, it.webhooks, it.notifications, it.admin, it.workspaces, it.extraPerms)
                }
            )

            var adminGroupId: UUID? = null

            for (def in groupDefs) {
                val groupId = UUID.randomUUID()
                OrgGroups.insert {
                    it[id] = groupId
                    it[organizationId] = orgId
                    it[OrgGroups.name] = def.name
                    it[orgUserList] = def.users
                    it[orgSettings] = def.settings
                    it[orgDomains] = def.domains
                    it[orgWebhooks] = def.webhooks
                    it[orgNotifications] = def.notifications
                    it[orgAdmin] = def.admin
                    it[orgWorkspaces] = def.workspaces
                    if (def.extraPerms.isNotEmpty()) {
                        it[orgExtraPerms] = JsonObject(
                            def.extraPerms.mapValues { (_, level) -> JsonPrimitive(level.toInt()) }
                        )
                    }
                }

                // First group with write on users is the admin group
                if (adminGroupId == null && def.users >= AccessLevel.WRITE) {
                    adminGroupId = groupId
                }
            }

            // 5. Assign owner to admin group
            if (adminGroupId != null) {
                OrgUserGroups.insert {
                    it[id] = UUID.randomUUID()
                    it[OrgUserGroups.orgUserId] = orgUserId
                    it[orgGroupId] = adminGroupId
                }
            }

            // 6. Create default workspace
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[Workspaces.name] = "Default"
                it[deleted] = false
                it[createdAt] = now
            }
            OutboxEmit.emitResourceEvent(
                "resource.workspace.created", "workspace", workspaceId,
                buildJsonObject { put("id", workspaceId.toString()); put("orgId", orgId.toString()) },
            )

            // 7. Seed org-level override variables (maxTimeoutMs, maxRedirects)
            SystemVariableSeeder.seedOrg(orgId, now)

            // 8. Compute permission cache for owner
            PermissionCacheService.recomputeForUser(orgUserId)

            log.info("Created org '{}' ({}), workspace ({}), {} groups, owner={}",
                name, orgId, workspaceId, groupDefs.size, ownerId)

            CreateOrgResult(orgId, workspaceId, orgUserId)
        }
        }
    }
}
