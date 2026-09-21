package dev.tracedown.gateway.controllers

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.NotificationTemplates
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.models.OrgRulePresets
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.WebhookVariables
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.controllers.domains.DomainController
import dev.tracedown.gateway.controllers.integrations.GrafanaIntegrationController
import dev.tracedown.gateway.controllers.notifications.NotificationTemplateController
import dev.tracedown.gateway.controllers.orgs.InviteController
import dev.tracedown.gateway.controllers.orgs.OrgSettingsController
import dev.tracedown.gateway.controllers.orgs.OrgVariableController
import dev.tracedown.gateway.controllers.orgs.PermissionController
import dev.tracedown.gateway.controllers.presets.RulePresetController
import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.webhooks.WebhookController
import dev.tracedown.gateway.controllers.webhooks.WebhookVariableController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import kotlinx.serialization.json.buildJsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * One rule, every delete: `purge_after = deleted_at + retention`.
 *
 * The delete paths used to disagree about what a deletion schedules. Some
 * honoured the operator's retention, some stamped the deletion instant — so the
 * row was erased within minutes however long the operator had asked for it to
 * be kept — and most left the column NULL, which is not a long window but no
 * window at all: [dev.tracedown.worker.jobs.PurgeJob] reads `purge_after` and
 * nothing else, so those rows were never erased.
 *
 * These tests pin the rule at both ends of the setting: `0`, the install
 * default, where deleted means purgeable at once, and `30`, an install that
 * keeps deleted data for a month.
 *
 * [`every delete path gives its rows a purge date no earlier than the
 * retention`] is the one that catches drift: it drives every delete endpoint
 * there is and then asks the *database* — every table carrying the three
 * deletion columns, found in `information_schema` rather than listed here —
 * whether anything came out of it unscheduled or scheduled too soon. A new
 * table, or a new delete path that forgets the stamp, fails it without anyone
 * having to remember to add a case.
 */
@Testcontainers
class DeletionRetentionStampTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_deletion_stamp_test")
            .withUsername("test")
            .withPassword("test")

        // TIMESTAMP(0) columns round to the second.
        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        private const val RETENTION_DAYS = 30
        private val RETENTION_WINDOW = java.time.Duration.ofDays(RETENTION_DAYS.toLong())

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

    /** Every test leaves the process on the install default it found it on. */
    @AfterEach
    fun resetRetention() = DeletionRetention.init(DeletionRetention.DEFAULT_RETENTION_DAYS)

    // ── Fixtures ──

    private fun seedUser(): UUID = transaction {
        val id = UUID.randomUUID()
        Users.insert {
            it[Users.id] = id
            it[email] = "stamp-$id@tracedown.test"
            it[passwordHash] = "x"
            it[displayName] = "u"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedOrg(ownerId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        Organizations.insert {
            it[Organizations.id] = id
            it[name] = "org-${id.toString().take(8)}"
            it[Organizations.ownerId] = ownerId
            it[createdAt] = NOW
        }
        OrgUsers.insert {
            it[OrgUsers.id] = UUID.randomUUID()
            it[organizationId] = id
            it[userId] = ownerId
            it[status] = "active"
            it[joinedAt] = NOW
            it[inviteToken] = "t-${UUID.randomUUID()}"
        }
        id
    }

    private fun seedWorkspace(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        Workspaces.insert {
            it[Workspaces.id] = id
            it[organizationId] = orgId
            it[name] = "ws-${id.toString().take(8)}"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedProject(workspaceId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        Projects.insert {
            it[Projects.id] = id
            it[Projects.workspaceId] = workspaceId
            it[name] = "proj-${id.toString().take(8)}"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedService(projectId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        Services.insert {
            it[Services.id] = id
            it[Services.projectId] = projectId
            it[name] = "svc-${id.toString().take(8)}"
            it[isActive] = true
            it[createdAt] = NOW
        }
        id
    }

    /** Owner, org, workspace, project, service — the spine every test needs. */
    private data class Tree(
        val ownerId: UUID,
        val orgId: UUID,
        val workspaceId: UUID,
        val projectId: UUID,
        val serviceId: UUID,
    )

    private fun seedTree(): Tree {
        val ownerId = seedUser()
        val orgId = seedOrg(ownerId)
        val workspaceId = seedWorkspace(orgId)
        val projectId = seedProject(workspaceId)
        return Tree(ownerId, orgId, workspaceId, projectId, seedService(projectId))
    }

    // ── Readers ──

    private fun row(table: org.jetbrains.exposed.v1.core.Table, idCol: org.jetbrains.exposed.v1.core.Column<UUID>, id: UUID) =
        transaction { table.selectAll().where { idCol eq id }.single() }

    /**
     * Asserts the one rule: the row is deleted, carries a deletion instant, and
     * its purge date is exactly that instant plus the configured retention.
     */
    private fun assertStamped(
        deletedAt: Instant?,
        purgeAfter: Instant?,
        retentionDays: Int,
        what: String,
    ) {
        assertNotNull(deletedAt, "$what: a soft-delete has to record when")
        assertNotNull(purgeAfter, "$what: a soft-delete with no purge date is never erased")
        assertEquals(
            deletedAt!!.plus(java.time.Duration.ofDays(retentionDays.toLong())),
            purgeAfter,
            "$what: purge_after must be deleted_at + the configured retention",
        )
    }

    // ── The rule, level by level ──

    @Test
    fun `a service delete stamps the service`() {
        for (retention in listOf(0, RETENTION_DAYS)) {
            DeletionRetention.init(retention)
            val tree = seedTree()

            ServiceController.delete(tree.orgId, tree.serviceId, tree.ownerId)

            val service = row(Services, Services.id, tree.serviceId)
            assertTrue(service[Services.deleted])
            assertStamped(
                service[Services.deletedAt], service[Services.purgeAfter], retention,
                "service delete (retention=$retention)",
            )
        }
    }

    @Test
    fun `a project delete stamps the project and everything it carries down`() {
        for (retention in listOf(0, RETENTION_DAYS)) {
            DeletionRetention.init(retention)
            val tree = seedTree()
            val integrationId = seedGrafanaIntegration(tree.orgId, tree.projectId)

            ProjectController.delete(tree.orgId, tree.projectId, tree.ownerId)

            val project = row(Projects, Projects.id, tree.projectId)
            val service = row(Services, Services.id, tree.serviceId)
            val integration = row(GrafanaIntegrations, GrafanaIntegrations.id, integrationId)

            assertStamped(
                project[Projects.deletedAt], project[Projects.purgeAfter], retention,
                "project delete (retention=$retention)",
            )
            assertEquals(project[Projects.deletedAt], service[Services.deletedAt], "one delete, one instant")
            assertEquals(project[Projects.purgeAfter], service[Services.purgeAfter], "one delete, one purge date")
            assertEquals(project[Projects.purgeAfter], integration[GrafanaIntegrations.purgeAfter])
        }
    }

    @Test
    fun `a workspace delete stamps its whole subtree with one purge date`() {
        for (retention in listOf(0, RETENTION_DAYS)) {
            DeletionRetention.init(retention)
            val tree = seedTree()

            WorkspaceController.delete(tree.orgId, tree.workspaceId, tree.ownerId)

            val workspace = row(Workspaces, Workspaces.id, tree.workspaceId)
            assertStamped(
                workspace[Workspaces.deletedAt], workspace[Workspaces.purgeAfter], retention,
                "workspace delete (retention=$retention)",
            )
            val stamp = workspace[Workspaces.purgeAfter]
            assertEquals(stamp, row(Projects, Projects.id, tree.projectId)[Projects.purgeAfter])
            assertEquals(stamp, row(Services, Services.id, tree.serviceId)[Services.purgeAfter])
        }
    }

    @Test
    fun `an organization delete carries its workspaces, projects, services and memberships down`() {
        for (retention in listOf(0, RETENTION_DAYS)) {
            DeletionRetention.init(retention)
            val tree = seedTree()

            OrgSettingsController.deleteOrg(tree.orgId, tree.ownerId)

            val org = row(Organizations, Organizations.id, tree.orgId)
            assertStamped(
                org[Organizations.deletedAt], org[Organizations.purgeAfter], retention,
                "organization delete (retention=$retention)",
            )
            val stamp = org[Organizations.purgeAfter]

            val workspace = row(Workspaces, Workspaces.id, tree.workspaceId)
            assertTrue(
                workspace[Workspaces.deleted],
                "the org's workspaces used to stay live under a deleted organization",
            )
            assertEquals(stamp, workspace[Workspaces.purgeAfter])

            val project = row(Projects, Projects.id, tree.projectId)
            assertTrue(project[Projects.deleted], "the org's projects go with it too")
            assertEquals(stamp, project[Projects.purgeAfter])

            assertEquals(stamp, row(Services, Services.id, tree.serviceId)[Services.purgeAfter])

            val membership = transaction {
                OrgUsers.selectAll()
                    .where { (OrgUsers.organizationId eq tree.orgId) and (OrgUsers.userId eq tree.ownerId) }
                    .single()
            }
            assertTrue(membership[OrgUsers.deleted])
            assertEquals(stamp, membership[OrgUsers.purgeAfter], "a membership used to go in with no purge date")
        }
    }

    @Test
    fun `a cascade leaves a child the user had already deleted on its own schedule`() {
        DeletionRetention.init(RETENTION_DAYS)
        val tree = seedTree()

        // Deleted three days ago, on its own, with its own purge date.
        val earlier = NOW.minus(3, ChronoUnit.DAYS)
        transaction {
            Projects.update({ Projects.id eq tree.projectId }) {
                it[deleted] = true
                it[deletedAt] = earlier
                it[purgeAfter] = earlier.plus(RETENTION_WINDOW)
            }
        }

        WorkspaceController.delete(tree.orgId, tree.workspaceId, tree.ownerId)

        val project = row(Projects, Projects.id, tree.projectId)
        assertEquals(
            earlier, project[Projects.deletedAt],
            "a restore must put back what this delete took, not what the user deleted days ago",
        )
        assertEquals(earlier.plus(RETENTION_WINDOW), project[Projects.purgeAfter])
    }

    // ── Accounts: the retention is a floor the orphan grace cannot undercut ──

    @Test
    fun `an orphaned account is held for the longer of the grace window and the retention`() {
        val graceDays = AccountLifecycle.ORPHAN_GRACE_SECONDS / 86_400L

        DeletionRetention.init(0)
        val shortRetention = seedUser()
        transaction { AccountLifecycle.reconcile(shortRetention, NOW) }
        assertEquals(
            NOW.plusSeconds(AccountLifecycle.ORPHAN_GRACE_SECONDS),
            row(Users, Users.id, shortRetention)[Users.purgeAfter],
            "with no retention configured the ${graceDays}-day orphan grace still stands",
        )

        DeletionRetention.init(RETENTION_DAYS)
        val longRetention = seedUser()
        transaction { AccountLifecycle.reconcile(longRetention, NOW) }
        assertEquals(
            NOW.plus(RETENTION_WINDOW),
            row(Users, Users.id, longRetention)[Users.purgeAfter],
            "a ${graceDays}-day grace must not erase an account the operator asked to keep for $RETENTION_DAYS",
        )
    }

    @Test
    fun `an abandoned signup is scheduled against the retention, not erased at once`() {
        DeletionRetention.init(RETENTION_DAYS)
        val abandoned = seedUser()
        transaction {
            Users.update({ Users.id eq abandoned }) {
                it[createdAt] = NOW.minusSeconds(AccountLifecycle.ORPHAN_GRACE_SECONDS * 2)
            }
            AccountLifecycle.markAbandonedOrphans(NOW)
        }

        val user = row(Users, Users.id, abandoned)
        assertTrue(user[Users.deleted])
        assertStamped(user[Users.deletedAt], user[Users.purgeAfter], RETENTION_DAYS, "abandoned signup")
    }

    @Test
    fun `an expired invite's membership is scheduled rather than left unpurgeable`() {
        DeletionRetention.init(RETENTION_DAYS)
        val tree = seedTree()
        val inviteeId = seedUser()
        val membershipId = UUID.randomUUID()
        transaction {
            OrgUsers.insert {
                it[OrgUsers.id] = membershipId
                it[organizationId] = tree.orgId
                it[userId] = inviteeId
                it[status] = "invited"
                it[inviteToken] = "t-${UUID.randomUUID()}"
                it[inviteExpiresAt] = NOW.minusSeconds(AccountLifecycle.EXPIRED_INVITE_GRACE_SECONDS * 2)
            }
            AccountLifecycle.sweepExpiredInvites(NOW)
        }

        val membership = row(OrgUsers, OrgUsers.id, membershipId)
        assertTrue(membership[OrgUsers.deleted])
        assertStamped(
            membership[OrgUsers.deletedAt], membership[OrgUsers.purgeAfter], RETENTION_DAYS,
            "expired invite",
        )
    }

    // ── The sweep: every path, then ask the database ──

    @Test
    fun `every delete path gives its rows a purge date no earlier than the retention`() {
        DeletionRetention.init(RETENTION_DAYS)
        val added = newOffenders(RETENTION_DAYS) { runEveryDeletePath() }
        assertTrue(
            added.isEmpty(),
            "these tables came out of a delete with no purge date, or one sooner than the configured " +
                "$RETENTION_DAYS-day retention: $added",
        )
    }

    @Test
    fun `with no retention configured every delete path still gives its rows a purge date`() {
        DeletionRetention.init(0)
        val added = newOffenders(0) { runEveryDeletePath() }
        assertTrue(added.isEmpty(), "a soft-deleted row with no purge date is never erased: $added")
    }

    /**
     * Drives every delete endpoint the gateway has, on freshly-seeded rows, so
     * the sweep below has something from each of them to look at.
     */
    private fun runEveryDeletePath() {
        val tree = seedTree()
        val (ownerId, orgId, workspaceId, projectId, serviceId) = tree

        // Variables at all four resource scopes, plus the webhook scope.
        val orgVar = seedOrgVariable(orgId)
        val workspaceVar = seedWorkspaceVariable(workspaceId)
        val projectVar = seedProjectVariable(projectId)
        val serviceVar = seedServiceVariable(serviceId)
        OrgVariableController.delete(orgId, orgVar, ownerId)
        WorkspaceController.deleteVariable(orgId, workspaceId, workspaceVar, ownerId)
        ProjectController.deleteVariable(orgId, projectId, projectVar, ownerId)
        ServiceController.deleteVariable(orgId, serviceId, serviceVar, ownerId)

        val webhookId = seedWebhook(orgId)
        val webhookVar = seedWebhookVariable(orgId, webhookId)
        WebhookVariableController.delete(orgId, webhookId, webhookVar, ownerId)
        WebhookController.delete(orgId, webhookId, ownerId)

        ApiKeyController.delete(orgId, seedApiKey(orgId), ownerId)
        DomainController.delete(orgId, seedDomain(orgId), ownerId)
        NotificationTemplateController.delete(orgId, seedTemplate(orgId), ownerId)
        RulePresetController.delete(orgId, ownerId, seedRulePreset(orgId, workspaceId))
        seedGrafanaIntegration(orgId, projectId)
        GrafanaIntegrationController.delete(orgId, projectId, ownerId)

        // A removed member, and a revoked invite.
        val memberId = seedUser()
        seedMembership(orgId, memberId, status = "active")
        PermissionController.removeUser(orgId, memberId, ownerId)
        val inviteId = seedMembership(orgId, seedUser(), status = "invited")
        InviteController.revokeInvite(orgId, inviteId, ownerId)

        // The containers, bottom-up, each on its own tree so one delete does not
        // pre-empt the next.
        ServiceController.delete(orgId, serviceId, ownerId)
        val forProject = seedTree()
        ProjectController.delete(forProject.orgId, forProject.projectId, forProject.ownerId)
        val forWorkspace = seedTree()
        WorkspaceController.delete(forWorkspace.orgId, forWorkspace.workspaceId, forWorkspace.ownerId)
        val forOrg = seedTree()
        OrgSettingsController.deleteOrg(forOrg.orgId, forOrg.ownerId)
    }

    /**
     * Runs [block] and reports what it added to the offender tally, per table.
     *
     * The tally is taken before and after rather than filtered by time: the
     * whole class shares one database and one clock second, so "rows this test
     * deleted" is not something a timestamp can answer. What the delta does
     * answer is the only question that matters — did *this* set of deletes
     * leave anything unscheduled.
     */
    private fun newOffenders(retentionDays: Int, block: () -> Unit): Map<String, Long> {
        val before = offendersByTable(retentionDays)
        block()
        val after = offendersByTable(retentionDays)
        return after
            .mapValues { (table, count) -> count - (before[table] ?: 0L) }
            .filterValues { it > 0L }
    }

    /**
     * Asks every table that carries the three deletion columns — found in
     * `information_schema`, so a table added later is covered without anyone
     * touching this test — how many soft-deleted rows have no purge date, or
     * one earlier than the retention promises.
     *
     * The comparison is `>=` on purpose: an account that lost its last
     * membership is additionally held for the orphan grace window, which may be
     * longer. What must never happen is a row erased *sooner* than the operator
     * asked, or not scheduled at all.
     */
    private fun offendersByTable(retentionDays: Int): Map<String, Long> = transaction {
        val tables = mutableListOf<String>()
        exec(
            """
            SELECT table_name FROM information_schema.columns
             WHERE table_schema = current_schema()
               AND column_name IN ('deleted', 'deleted_at', 'purge_after')
             GROUP BY table_name HAVING count(DISTINCT column_name) = 3
             ORDER BY table_name
            """
        ) { rs -> while (rs.next()) tables.add(rs.getString(1)) }

        assertTrue(tables.size >= 17, "expected the three-tier tables to be found, got $tables")

        // Compared in Kotlin, not in SQL: these are `timestamp` columns without
        // a zone, so `deleted_at + interval '30 days'` in the database is 30
        // calendar days while the delete paths add an absolute 30 × 24 hours.
        // Across a daylight-saving change the two differ by an hour, which is
        // enough to make an SQL-side comparison report deletes that are in fact
        // correct.
        val window = java.time.Duration.ofDays(retentionDays.toLong())
        val offenders = linkedMapOf<String, Long>()
        for (table in tables) {
            var count = 0L
            exec("SELECT deleted_at, purge_after FROM $table WHERE deleted = true") { rs ->
                while (rs.next()) {
                    val deletedAt = rs.getTimestamp(1)?.toInstant()
                    val purgeAfter = rs.getTimestamp(2)?.toInstant()
                    if (purgeAfter == null || (deletedAt != null && purgeAfter < deletedAt.plus(window))) {
                        count++
                    }
                }
            }
            offenders[table] = count
        }
        offenders
    }

    // ── Seeders for the sweep ──

    private fun seedOrgVariable(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        OrgVariables.insert {
            it[OrgVariables.id] = id
            it[organizationId] = orgId
            it[key] = "k-${id.toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        id
    }

    private fun seedWorkspaceVariable(workspaceId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        WorkspaceVariables.insert {
            it[WorkspaceVariables.id] = id
            it[WorkspaceVariables.workspaceId] = workspaceId
            it[key] = "k-${id.toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        id
    }

    private fun seedProjectVariable(projectId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        ProjectVariables.insert {
            it[ProjectVariables.id] = id
            it[ProjectVariables.projectId] = projectId
            it[key] = "k-${id.toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        id
    }

    private fun seedServiceVariable(serviceId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        ServiceVariables.insert {
            it[ServiceVariables.id] = id
            it[ServiceVariables.serviceId] = serviceId
            it[key] = "k-${id.toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        id
    }

    private fun seedWebhook(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        WebhookDeliveries.insert {
            it[WebhookDeliveries.id] = id
            it[organizationId] = orgId
            it[name] = "wh-${id.toString().take(8)}"
            it[url] = "https://example.test/hook"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedWebhookVariable(orgId: UUID, webhookId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        WebhookVariables.insert {
            it[WebhookVariables.id] = id
            it[organizationId] = orgId
            it[WebhookVariables.webhookId] = webhookId
            it[key] = "k-${id.toString().take(8)}"
            it[value] = "v"
            it[secret] = false
            it[encrypted] = false
            it[createdAt] = NOW
            it[updatedAt] = NOW
        }
        id
    }

    private fun seedApiKey(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        ApiKeys.insert {
            it[ApiKeys.id] = id
            it[organizationId] = orgId
            it[name] = "key-${id.toString().take(8)}"
            it[keyHash] = "h-$id"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedDomain(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        OrgDomains.insert {
            it[OrgDomains.id] = id
            it[organizationId] = orgId
            it[domain] = "${id.toString().take(8)}.example.test"
            it[challenge] = "c"
            it[verificationType] = "dns"
            it[status] = "pending"
        }
        id
    }

    private fun seedTemplate(orgId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        NotificationTemplates.insert {
            it[NotificationTemplates.id] = id
            it[organizationId] = orgId
            it[name] = "tpl-${id.toString().take(8)}"
            it[text] = "hello"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedRulePreset(orgId: UUID, workspaceId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        OrgRulePresets.insert {
            it[OrgRulePresets.id] = id
            it[organizationId] = orgId
            it[OrgRulePresets.workspaceId] = workspaceId
            it[displayName] = "preset-${id.toString().take(8)}"
            it[script] = "GET https://example.test"
            it[createdAt] = NOW
        }
        id
    }

    private fun seedGrafanaIntegration(orgId: UUID, projectId: UUID): UUID = transaction {
        val id = UUID.randomUUID()
        GrafanaIntegrations.insert {
            it[GrafanaIntegrations.id] = id
            it[organizationId] = orgId
            it[GrafanaIntegrations.projectId] = projectId
            it[name] = "graf-${id.toString().take(8)}"
            it[config] = buildJsonObject { }
            it[enabled] = true
            it[createdAt] = NOW
        }
        id
    }

    private fun seedMembership(orgId: UUID, userId: UUID, status: String): UUID = transaction {
        val id = UUID.randomUUID()
        OrgUsers.insert {
            it[OrgUsers.id] = id
            it[organizationId] = orgId
            it[OrgUsers.userId] = userId
            it[OrgUsers.status] = status
            it[inviteToken] = "t-${UUID.randomUUID()}"
            if (status == "active") it[joinedAt] = NOW
        }
        id
    }

    /** The sweep is only worth anything if it can fail. */
    @Test
    fun `the sweep would notice an unscheduled row`() {
        DeletionRetention.init(RETENTION_DAYS)
        val tree = seedTree()

        val added = newOffenders(RETENTION_DAYS) {
            transaction {
                Services.update({ Services.id eq tree.serviceId }) {
                    it[deleted] = true
                    it[deletedAt] = Instant.now()
                    it[purgeAfter] = null
                }
            }
        }

        assertEquals(mapOf("services" to 1L), added, "the sweep has to be able to fail")
        assertNull(
            row(Services, Services.id, tree.serviceId)[Services.purgeAfter],
            "sanity: the row really has no purge date",
        )
    }
}
