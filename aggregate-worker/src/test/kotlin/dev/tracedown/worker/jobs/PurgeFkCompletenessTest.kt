package dev.tracedown.worker.jobs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.NotificationTemplates
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.models.OrgEncryptionKeys
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgRulePresets
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.PasswordResetTokens
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeAggregates
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeStepAggregates
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.ProjectNotificationTemplates
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.ResourceWebhookAccess
import dev.tracedown.common.models.ServiceAllowedAgents
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.SystemAlertDismissals
import dev.tracedown.common.models.SystemAlerts
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.WebhookVariables
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The purge has to be able to finish, on a database with something in every
 * table that could hold it up.
 *
 * [PurgeJob] erases by hand-written cascade, leaf-first, because most of the
 * schema's foreign keys are `NO ACTION`: a single row left behind anywhere
 * below an organization blocks the delete of the organization row, the group
 * fails, and it fails again on every run after that. Twice already a table has
 * been found missing from a cascade only after the cascade started running
 * (`project_notification_templates`, then workspace-scoped `org_rule_presets`).
 *
 * So this test does not check a list. It reads the foreign keys out of the
 * migrated database, works out every table that hangs off an organization or an
 * account however indirectly, and insists that this test seeds all of them —
 * [`every table that hangs off an organization or an account is seeded here`].
 * A table added later with a foreign key into that graph fails that test until
 * someone seeds it here, and seeding it is what makes the purge tests below
 * exercise it.
 *
 * The purge tests then populate an organization completely and purge at each
 * level. A blocked cascade rolls its group back, so "the row is gone" is the
 * assertion: it can only be true if every foreign key underneath it was
 * satisfied.
 */
@Testcontainers
class PurgeFkCompletenessTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_purge_fk_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        private val PAST: Instant = NOW.minus(2, ChronoUnit.DAYS)

        /** Where the graph starts: everything an install holds hangs off one of these. */
        private val ROOTS = setOf("organizations", "users")

        /**
         * Tables in the graph that this test deliberately does not seed.
         *
         * Empty, and meant to stay that way: a table reachable from an
         * organization is a table that can block its purge, so the honest way to
         * exclude one is to explain why here.
         */
        private val NOT_SEEDED = emptySet<String>()

        /** Every table this test puts a row in. */
        private val SEEDED = setOf(
            "agent_bootstrap_tokens", "agent_certificates", "agent_health_checks", "api_keys",
            "body_stores", "grafana_integrations", "notification_log", "notification_silences",
            "notification_templates", "org_audit_log", "org_domains", "org_encryption_keys",
            "org_groups", "org_rule_presets", "org_user_groups", "org_users", "org_variables",
            "organizations", "password_reset_tokens", "probe_agents", "probe_aggregates",
            "probe_results", "probe_step_aggregates", "probe_steps", "project_notification_templates",
            "project_variables", "projects", "resource_permissions", "resource_webhook_access",
            "service_allowed_agents", "service_variables", "services", "sessions",
            "system_alert_dismissals", "system_alerts", "totp_recovery_codes", "users",
            "webhook_deliveries", "webhook_variables", "workspace_variables", "workspaces",
        )

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))
        }
    }

    // ── The foreign-key graph, read from the database itself ──

    /** Every foreign key as `child table` → `parent table`. */
    private fun foreignKeys(): List<Pair<String, String>> = transaction {
        val edges = mutableListOf<Pair<String, String>>()
        exec(
            """
            SELECT c.conrelid::regclass::text AS child, c.confrelid::regclass::text AS parent
              FROM pg_constraint c
              JOIN pg_namespace n ON n.oid = c.connamespace
             WHERE c.contype = 'f' AND n.nspname = current_schema()
            """
        ) { rs -> while (rs.next()) edges.add(rs.getString(1) to rs.getString(2)) }
        edges
    }

    /** Every table reachable from [ROOTS] by following foreign keys downwards. */
    private fun reachableFromRoots(): Set<String> {
        val edges = foreignKeys()
        val reached = ROOTS.toMutableSet()
        var grew = true
        while (grew) {
            grew = false
            for ((child, parent) in edges) {
                if (parent in reached && reached.add(child)) grew = true
            }
        }
        return reached
    }

    @Test
    fun `every table that hangs off an organization or an account is seeded here`() {
        val reachable = reachableFromRoots()

        val unseeded = (reachable - SEEDED - NOT_SEEDED).sorted()
        assertTrue(
            unseeded.isEmpty(),
            "these tables hold rows that can block a purge and nothing here puts a row in them: " +
                "$unseeded — seed them in seedOrganization()/seedAccount() (and, if the purge does not " +
                "reach them, fix PurgeJob), or say in NOT_SEEDED why they cannot block anything",
        )

        val stale = (SEEDED - reachable).sorted()
        assertTrue(stale.isEmpty(), "these are seeded but no longer hang off the graph: $stale")
    }

    // ── A fully-populated organization ──

    private class Org(
        val ownerId: UUID,
        val orgId: UUID,
        val workspaceId: UUID,
        val projectId: UUID,
        val serviceId: UUID,
        val resultId: UUID,
        val agentId: Long,
        val bodyStoreId: UUID,
    )

    private fun seedOrganization(): Org = transaction {
        val ownerId = UUID.randomUUID()
        Users.insert {
            it[id] = ownerId
            it[email] = "fk-$ownerId@t.dev"
            it[passwordHash] = "x"
            it[displayName] = "owner"
            it[createdAt] = NOW
        }

        val orgId = UUID.randomUUID()
        Organizations.insert {
            it[id] = orgId
            it[name] = "fk-${orgId.toString().take(8)}"
            it[Organizations.ownerId] = ownerId
            it[createdAt] = NOW
        }
        // The owner's selected organization: an ON DELETE SET NULL link that has
        // to be cleared by the schema, not by the cascade.
        Users.update({ Users.id eq ownerId }) { it[selectedOrgId] = orgId }

        OrgEncryptionKeys.insert {
            it[OrgEncryptionKeys.orgId] = orgId
            it[wrappedDek] = "dek"
            it[createdAt] = NOW
        }

        val bodyStoreId = UUID.randomUUID()
        BodyStores.insert {
            it[id] = bodyStoreId
            it[organizationId] = orgId
            it[name] = "store-${bodyStoreId.toString().take(8)}"
            it[kind] = "filesystem"
            it[mode] = "in_place"
            it[rootPath] = "/tmp/bodies"
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }

        // A platform-level agent pointed at the organization's store — the row
        // whose pointer blocks the store's own cascade if nothing releases it.
        val agentId = ProbeAgents.insert {
            it[slug] = "agent-${UUID.randomUUID().toString().take(12)}"
            it[label] = "agent"
            it[agentUri] = "https://agent.test"
            it[publicKey] = "pk"
            it[lastPing] = NOW
            it[lastStatus] = "success"
            it[lastPingDelayMs] = 0
            it[lastPongDeltaMs] = 0
            it[createdAt] = NOW
            it[ProbeAgents.bodyStoreId] = bodyStoreId
        }[ProbeAgents.id]

        AgentCertificates.insert {
            it[id] = UUID.randomUUID()
            it[probeAgentId] = agentId
            it[certificatePem] = "pem"
            it[fingerprint] = "fp-${UUID.randomUUID()}"
            it[issuedAt] = NOW
            it[expiresAt] = NOW.plusSeconds(86_400)
            it[createdAt] = NOW
        }
        AgentHealthChecks.insert {
            it[id] = UUID.randomUUID()
            it[probeAgentId] = agentId
            it[challengeId] = "ch-${UUID.randomUUID()}"
            it[challengedAt] = NOW
            it[result] = "pass"
            it[createdAt] = NOW
        }
        AgentBootstrapTokens.insert {
            it[id] = UUID.randomUUID()
            it[slug] = "boot-${UUID.randomUUID().toString().take(12)}"
            it[label] = "boot"
            it[tokenHash] = "h-${UUID.randomUUID()}"
            it[expiresAt] = NOW.plusSeconds(3600)
            it[createdBy] = ownerId
            it[createdAt] = NOW
            it[AgentBootstrapTokens.bodyStoreId] = bodyStoreId
        }

        val workspaceId = UUID.randomUUID()
        Workspaces.insert {
            it[id] = workspaceId
            it[organizationId] = orgId
            it[name] = "ws"
            it[createdAt] = NOW
        }
        val projectId = UUID.randomUUID()
        Projects.insert {
            it[id] = projectId
            it[Projects.workspaceId] = workspaceId
            it[name] = "proj"
            it[createdAt] = NOW
        }
        val serviceId = UUID.randomUUID()
        Services.insert {
            it[id] = serviceId
            it[Services.projectId] = projectId
            it[name] = "svc"
            it[createdAt] = NOW
        }

        val resultId = UUID.randomUUID()
        ProbeResults.insert {
            it[id] = resultId
            it[ProbeResults.serviceId] = serviceId
            it[probeAgentId] = agentId
            it[startedAt] = NOW
            it[status] = "success"
            it[runDurationMs] = 10
            it[rawResult] = JsonObject(emptyMap())
            it[ProbeResults.projectId] = projectId
            it[ProbeResults.workspaceId] = workspaceId
            it[organizationId] = orgId
        }
        // The last-run pointer: an ON DELETE SET NULL link back up from the
        // service to a result the same cascade deletes.
        Services.update({ Services.id eq serviceId }) { it[lastRunId] = resultId }

        ProbeSteps.insert {
            it[id] = UUID.randomUUID()
            it[probeResultId] = resultId
            it[stepNum] = 1
            it[requestUrl] = "https://example.test"
            it[ProbeSteps.bodyStoreId] = bodyStoreId
            it[createdAt] = NOW
        }
        ProbeAggregates.insert {
            it[id] = UUID.randomUUID()
            it[ProbeAggregates.serviceId] = serviceId
            it[probeAgentId] = agentId
            it[bucketStart] = NOW
            it[bucketType] = "hourly"
        }
        ProbeStepAggregates.insert {
            it[id] = UUID.randomUUID()
            it[ProbeStepAggregates.serviceId] = serviceId
            it[bucketStart] = NOW
            it[bucketType] = "hourly"
            it[endpointKey] = "GET example.test/"
            it[statusCode] = 200
            it[callCount] = 1
            it[timedCount] = 1
            it[sumDnsMs] = 1
            it[sumConnectMs] = 1
            it[sumTlsMs] = 1
            it[sumTtfbMs] = 1
            it[sumTransferMs] = 1
            it[sumResponseMs] = 1
            it[sumSizeBytes] = 1
            it[sizedCount] = 1
        }
        ServiceAllowedAgents.insert {
            it[id] = UUID.randomUUID()
            it[ServiceAllowedAgents.serviceId] = serviceId
            it[probeAgentId] = agentId
        }

        // Variables at every scope.
        ServiceVariables.insert {
            it[id] = UUID.randomUUID()
            it[ServiceVariables.serviceId] = serviceId
            it[createdBy] = ownerId
            it[key] = "k-${UUID.randomUUID().toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        ProjectVariables.insert {
            it[id] = UUID.randomUUID()
            it[ProjectVariables.projectId] = projectId
            it[createdBy] = ownerId
            it[key] = "k-${UUID.randomUUID().toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        WorkspaceVariables.insert {
            it[id] = UUID.randomUUID()
            it[WorkspaceVariables.workspaceId] = workspaceId
            it[createdBy] = ownerId
            it[key] = "k-${UUID.randomUUID().toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        OrgVariables.insert {
            it[id] = UUID.randomUUID()
            it[OrgVariables.organizationId] = orgId
            it[createdBy] = ownerId
            it[key] = "k-${UUID.randomUUID().toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }

        val membershipId = UUID.randomUUID()
        OrgUsers.insert {
            it[id] = membershipId
            it[organizationId] = orgId
            it[userId] = ownerId
            it[status] = "active"
            it[joinedAt] = NOW
            it[inviteToken] = "t-${UUID.randomUUID()}"
        }
        val groupId = UUID.randomUUID()
        OrgGroups.insert {
            it[id] = groupId
            it[organizationId] = orgId
            it[name] = "grp-${groupId.toString().take(8)}"
        }
        OrgUserGroups.insert {
            it[id] = UUID.randomUUID()
            it[orgUserId] = membershipId
            it[orgGroupId] = groupId
        }
        // Silences at every scope they can hold.
        for (scope in 0..3) {
            NotificationSilences.insert {
                it[id] = UUID.randomUUID()
                it[orgUserId] = membershipId
                if (scope == 1) it[NotificationSilences.workspaceId] = workspaceId
                if (scope == 2) it[NotificationSilences.projectId] = projectId
                if (scope == 3) it[NotificationSilences.serviceId] = serviceId
                it[channel] = "email"
            }
        }

        val templateId = UUID.randomUUID()
        NotificationTemplates.insert {
            it[id] = templateId
            it[organizationId] = orgId
            it[name] = "tpl-${templateId.toString().take(8)}"
            it[text] = "hi"
            it[createdAt] = NOW
        }
        ProjectNotificationTemplates.insert {
            it[id] = UUID.randomUUID()
            it[notificationTemplateId] = templateId
            it[ProjectNotificationTemplates.projectId] = projectId
        }
        NotificationLog.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[NotificationLog.serviceId] = serviceId
            it[probeResultId] = resultId
            it[channel] = "email"
            it[recipient] = "someone@t.dev"
            it[status] = "sent"
            it[createdAt] = NOW
        }

        val webhookId = UUID.randomUUID()
        WebhookDeliveries.insert {
            it[id] = webhookId
            it[organizationId] = orgId
            it[name] = "wh-${webhookId.toString().take(8)}"
            it[url] = "https://example.test/hook"
            it[createdAt] = NOW
        }
        WebhookVariables.insert {
            it[id] = UUID.randomUUID()
            it[WebhookVariables.organizationId] = orgId
            it[WebhookVariables.webhookId] = webhookId
            it[createdBy] = ownerId
            it[key] = "k-${UUID.randomUUID().toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        ResourceWebhookAccess.insert {
            it[id] = UUID.randomUUID()
            it[ResourceWebhookAccess.orgId] = orgId
            it[resourceType] = "service"
            it[resourceId] = serviceId
            it[webhookDeliveryId] = webhookId
            it[createdAt] = NOW
        }
        ResourcePermissions.insert {
            it[id] = UUID.randomUUID()
            it[ResourcePermissions.orgId] = orgId
            it[principalType] = "org_user"
            it[principalId] = membershipId
            it[resourceType] = "workspace"
            it[resourceId] = workspaceId
            it[permissions] = 3
        }

        ApiKeys.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[createdBy] = ownerId
            it[name] = "key"
            it[keyHash] = "h-${UUID.randomUUID()}"
            it[createdAt] = NOW
        }
        OrgDomains.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[domain] = "${UUID.randomUUID().toString().take(8)}.example.test"
            it[challenge] = "c"
            it[verificationType] = "dns"
            it[status] = "pending"
        }
        // Both scopes of preset: the workspace-scoped one is the foreign key
        // that was found missing from the workspace cascade.
        OrgRulePresets.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[createdBy] = ownerId
            it[displayName] = "org-preset"
            it[script] = "GET https://example.test"
            it[createdAt] = NOW
        }
        OrgRulePresets.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[OrgRulePresets.workspaceId] = workspaceId
            it[createdBy] = ownerId
            it[displayName] = "ws-preset"
            it[script] = "GET https://example.test"
            it[createdAt] = NOW
        }
        GrafanaIntegrations.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[GrafanaIntegrations.projectId] = projectId
            it[name] = "graf"
            it[config] = buildJsonObject { }
            it[createdAt] = NOW
        }
        OrgAuditLog.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[userId] = ownerId
            it[action] = "create.org"
            it[createdAt] = NOW
        }

        val alertId = UUID.randomUUID()
        SystemAlerts.insert {
            it[id] = alertId
            it[organizationId] = orgId
            it[alertType] = "agent.down"
            it[createdAt] = NOW
            it[lastSeenAt] = NOW
        }
        SystemAlertDismissals.insert {
            it[id] = UUID.randomUUID()
            it[SystemAlertDismissals.alertId] = alertId
            it[userId] = ownerId
            it[dismissedAt] = NOW
        }

        Sessions.insert {
            it[id] = UUID.randomUUID()
            it[userId] = ownerId
            it[organizationId] = orgId
            it[expiresAt] = NOW.plusSeconds(3600)
            it[lastActiveAt] = NOW
            it[createdAt] = NOW
        }
        PasswordResetTokens.insert {
            it[id] = UUID.randomUUID()
            it[userId] = ownerId
            it[tokenHash] = "h-${UUID.randomUUID()}"
            it[expiresAt] = NOW.plusSeconds(3600)
            it[createdAt] = NOW
        }
        TotpRecoveryCodes.insert {
            it[id] = UUID.randomUUID()
            it[userId] = ownerId
            it[codeHash] = "h-${UUID.randomUUID()}"
            it[createdAt] = NOW
        }

        Org(ownerId, orgId, workspaceId, projectId, serviceId, resultId, agentId, bodyStoreId)
    }

    private fun runPurge() = runBlocking { PurgeJob(storageClient = NoStorage()).execute() }

    private class NoStorage : dev.tracedown.common.storage.BodyStorageClient() {
        override fun delete(uri: String): Boolean = true
        override fun deleteAll(uris: Collection<String>) =
            dev.tracedown.common.storage.BodyDeleteOutcome(failed = emptyMap())
    }

    private fun exists(table: org.jetbrains.exposed.v1.core.Table, column: org.jetbrains.exposed.v1.core.Column<UUID>, id: UUID) =
        transaction { table.selectAll().where { column eq id }.any() }

    // ── Purge at each level, with every foreign key populated ──

    @Test
    fun `purging a service succeeds with every service-level table populated`() {
        val org = seedOrganization()
        transaction {
            Services.update({ Services.id eq org.serviceId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertFalse(exists(Services, Services.id, org.serviceId), "the service was not purged")
        assertFalse(exists(ProbeResults, ProbeResults.id, org.resultId), "its results went with it")
        assertTrue(exists(Projects, Projects.id, org.projectId), "nothing above it was touched")
    }

    @Test
    fun `purging a project succeeds with every project-level table populated`() {
        val org = seedOrganization()
        transaction {
            Projects.update({ Projects.id eq org.projectId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertFalse(exists(Projects, Projects.id, org.projectId), "the project was not purged")
        assertFalse(exists(Services, Services.id, org.serviceId))
        assertTrue(exists(Workspaces, Workspaces.id, org.workspaceId))
    }

    @Test
    fun `purging a workspace succeeds with every workspace-level table populated`() {
        val org = seedOrganization()
        transaction {
            Workspaces.update({ Workspaces.id eq org.workspaceId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertFalse(exists(Workspaces, Workspaces.id, org.workspaceId), "the workspace was not purged")
        assertFalse(exists(Projects, Projects.id, org.projectId))
        assertTrue(exists(Organizations, Organizations.id, org.orgId))
    }

    @Test
    fun `purging an organization succeeds with every table under it populated`() {
        val org = seedOrganization()
        transaction {
            Organizations.update({ Organizations.id eq org.orgId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertFalse(exists(Organizations, Organizations.id, org.orgId), "the organization was not purged")
        assertFalse(exists(Workspaces, Workspaces.id, org.workspaceId))
        assertFalse(exists(Services, Services.id, org.serviceId))
        assertFalse(
            exists(BodyStores, BodyStores.id, org.bodyStoreId),
            "an organization's body stores go with it",
        )

        // The agent is platform-level: it survives, holding no store any more.
        val agent = transaction {
            ProbeAgents.selectAll().where { ProbeAgents.id eq org.agentId }.single()
        }
        assertNull(
            agent[ProbeAgents.bodyStoreId],
            "the agent's pointer at the organization's store has to be released, or nothing purges",
        )
    }

    @Test
    fun `purging an organization takes a subtree that was never soft-deleted`() {
        // The legacy shape: the organization row was flipped by a delete that
        // did not carry its workspaces and projects down. The purge reaches them
        // by parentage, so this has to succeed rather than block forever.
        val org = seedOrganization()
        transaction {
            Organizations.update({ Organizations.id eq org.orgId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }
        assertFalse(
            transaction { Workspaces.selectAll().where { Workspaces.id eq org.workspaceId }.single() }
                [Workspaces.deleted],
            "the workspace is deliberately left live",
        )

        runPurge()

        assertFalse(exists(Organizations, Organizations.id, org.orgId))
        assertFalse(exists(Workspaces, Workspaces.id, org.workspaceId), "a live child must not block it")
    }

    @Test
    fun `purging a membership on its own takes what hangs off it`() {
        val org = seedOrganization()
        val membershipId = transaction {
            OrgUsers.selectAll().where { OrgUsers.organizationId eq org.orgId }.single()[OrgUsers.id]
        }
        transaction {
            OrgUsers.update({ OrgUsers.id eq membershipId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertFalse(exists(OrgUsers, OrgUsers.id, membershipId), "a removed membership is never purged")
        assertEquals(
            0L,
            transaction {
                NotificationSilences.selectAll()
                    .where { NotificationSilences.orgUserId eq membershipId }.count()
            },
            "its silences go with it",
        )
        assertEquals(
            0L,
            transaction {
                ResourcePermissions.selectAll()
                    .where { ResourcePermissions.principalId eq membershipId }.count()
            },
            "so do the resource grants keyed to it — nothing else would ever remove them",
        )
        assertTrue(exists(Organizations, Organizations.id, org.orgId), "the organization is untouched")
    }

    @Test
    fun `a blocked group does not stop the other groups purging`() {
        val blocked = seedOrganization()
        val fine = seedOrganization()
        // A third organization, purging nothing: the blocking row hangs off it
        // so that no other group can clear it first.
        val bystander = seedOrganization()

        transaction {
            // A probe_steps row of another organization naming this one's store.
            // The foreign key has no ON DELETE action, so the cascade off
            // `organizations` cannot remove the store and the group fails.
            ProbeSteps.insert {
                it[id] = UUID.randomUUID()
                it[probeResultId] = bystander.resultId
                it[stepNum] = 2
                it[requestUrl] = "https://example.test/2"
                it[ProbeSteps.bodyStoreId] = blocked.bodyStoreId
                it[createdAt] = NOW
            }
            Organizations.update({ Organizations.id eq blocked.orgId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
            Services.update({ Services.id eq fine.serviceId }) {
                it[deleted] = true
                it[deletedAt] = PAST
                it[purgeAfter] = PAST
            }
        }

        runPurge()

        assertTrue(
            exists(Organizations, Organizations.id, blocked.orgId),
            "the blocked organization stays, to be retried",
        )
        assertFalse(
            exists(Services, Services.id, fine.serviceId),
            "one blocked group must not stall erasure platform-wide",
        )

        // Leave nothing behind: the organizations group is one transaction for
        // all due organizations, so an organization left permanently blocked
        // here would fail every other test's organization purge too.
        transaction {
            ProbeSteps.deleteWhere { ProbeSteps.bodyStoreId eq blocked.bodyStoreId }
            Organizations.update({ Organizations.id eq blocked.orgId }) { it[purgeAfter] = null }
        }
    }
}
