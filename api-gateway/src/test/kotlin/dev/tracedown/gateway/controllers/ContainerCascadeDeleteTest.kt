package dev.tracedown.gateway.controllers

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import kotlinx.serialization.json.buildJsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * Deleting a container stops everything inside it.
 *
 * Deleting a project or a workspace used to flip that one row. Its services
 * kept `deleted = false`, and the scheduler selects on `services` alone, so
 * they went on probing, writing results and sending notifications for
 * something the owner had deleted and could no longer see.
 *
 * [schedulerWouldRun] is that query — the one the scheduler runs against
 * `services` by itself, with no parent join. It is spelled out here rather
 * than called, so these tests keep proving the *delete* stops the probe even
 * if the scheduler's own guard is ever changed or removed.
 */
@Testcontainers
class ContainerCascadeDeleteTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_cascade_delete_test")
            .withUsername("test")
            .withPassword("test")

        // TIMESTAMP(0) columns round to the second — truncate or reads come
        // back newer than writes.
        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        private lateinit var orgId: UUID
        private lateinit var ownerId: UUID

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

            transaction {
                ownerId = UUID.randomUUID()
                Users.insert {
                    it[id] = ownerId
                    it[email] = "cascade-$ownerId@tracedown.test"
                    it[passwordHash] = "x"
                    it[displayName] = "owner"
                    it[createdAt] = NOW
                }

                orgId = UUID.randomUUID()
                Organizations.insert {
                    it[id] = orgId
                    it[name] = "cascade-org"
                    it[Organizations.ownerId] = ContainerCascadeDeleteTest.ownerId
                    it[createdAt] = NOW
                }
                OrgUsers.insert {
                    it[id] = UUID.randomUUID()
                    it[organizationId] = orgId
                    it[userId] = ownerId
                    it[status] = "active"
                    it[joinedAt] = NOW
                    it[inviteToken] = "t-${UUID.randomUUID()}"
                }
            }
        }

        // ── Fixtures ──

        private fun seedWorkspace(): UUID {
            val id = UUID.randomUUID()
            transaction {
                Workspaces.insert {
                    it[Workspaces.id] = id
                    it[organizationId] = orgId
                    it[name] = "ws-$id"
                    it[createdAt] = NOW
                }
            }
            return id
        }

        private fun seedProject(workspaceId: UUID, deleted: Boolean = false): UUID {
            val id = UUID.randomUUID()
            transaction {
                Projects.insert {
                    it[Projects.id] = id
                    it[Projects.workspaceId] = workspaceId
                    it[name] = "proj-$id"
                    it[createdAt] = NOW
                    if (deleted) {
                        it[Projects.deleted] = true
                        it[deletedAt] = NOW
                    }
                }
            }
            return id
        }

        private fun seedService(projectId: UUID): UUID {
            val id = UUID.randomUUID()
            transaction {
                Services.insert {
                    it[Services.id] = id
                    it[Services.projectId] = projectId
                    it[name] = "svc-$id"
                    it[isActive] = true
                    it[createdAt] = NOW
                }
            }
            return id
        }

        private fun seedGrafanaIntegration(projectId: UUID): UUID {
            val id = UUID.randomUUID()
            transaction {
                GrafanaIntegrations.insert {
                    it[GrafanaIntegrations.id] = id
                    it[organizationId] = orgId
                    it[GrafanaIntegrations.projectId] = projectId
                    it[name] = "graf-${id.toString().take(8)}"
                    it[config] = buildJsonObject { }
                    it[enabled] = true
                    it[createdAt] = NOW
                }
            }
            return id
        }

        // ── Readers ──

        /**
         * The scheduler's own question, asked of `services` alone: active, not
         * deleted, never mind what is above it. This returning a service is
         * exactly the bug.
         */
        private fun schedulerWouldRun(serviceId: UUID): Boolean = transaction {
            Services.selectAll()
                .where {
                    (Services.id eq serviceId) and
                        (Services.isActive eq true) and
                        (Services.deleted eq false)
                }
                .any()
        }

        private fun serviceRow(serviceId: UUID) = transaction {
            Services.selectAll().where { Services.id eq serviceId }.single()
        }

        private fun projectRow(projectId: UUID) = transaction {
            Projects.selectAll().where { Projects.id eq projectId }.single()
        }

        /**
         * Replays the shipped repair migration — the file itself, not a copy of
         * its statements — against whatever the calling test seeded. Flyway
         * already applied it to an empty database in [setup]; the statements are
         * `WHERE NOT deleted`, so replaying them is safe and idempotent.
         */
        private const val REPAIR_MIGRATION =
            "db/migrations/V1789937296__stop_probes_under_deleted_parents.sql"

        private fun runDataRepairMigration() {
            val sql = checkNotNull(
                ContainerCascadeDeleteTest::class.java.classLoader
                    .getResourceAsStream(REPAIR_MIGRATION),
            ) { "$REPAIR_MIGRATION is not on the test classpath" }
                .bufferedReader().use { it.readText() }

            transaction { exec(sql) }
        }
    }

    // ── Project ──

    @Test
    fun `deleting a project stops its services probing`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val serviceId = seedService(projectId)

        assertTrue(schedulerWouldRun(serviceId), "the service is live before the delete")

        ProjectController.delete(orgId, projectId, ownerId)

        assertFalse(
            schedulerWouldRun(serviceId),
            "the service kept probing after its project was deleted",
        )
        assertTrue(serviceRow(serviceId)[Services.deleted])
    }

    @Test
    fun `a project delete stamps its services with the project's own instant`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val serviceId = seedService(projectId)

        ProjectController.delete(orgId, projectId, ownerId)

        val project = projectRow(projectId)
        val service = serviceRow(serviceId)

        assertEquals(
            project[Projects.deletedAt], service[Services.deletedAt],
            "one delete, one instant — a restore has to be able to tell what this cascade took",
        )
        assertEquals(
            project[Projects.deletedAt], project[Projects.purgeAfter],
            "with the install default of no retention, purge_after is the deletion instant " +
                "(DeletionRetentionStampTest pins the configured case)",
        )
        assertEquals(project[Projects.deletedAt], service[Services.purgeAfter])
    }

    @Test
    fun `a project delete leaves an untouched sibling project alone`() {
        val workspaceId = seedWorkspace()
        val doomedId = seedProject(workspaceId)
        val survivorId = seedProject(workspaceId)
        val doomedService = seedService(doomedId)
        val survivingService = seedService(survivorId)

        ProjectController.delete(orgId, doomedId, ownerId)

        assertFalse(schedulerWouldRun(doomedService))
        assertTrue(schedulerWouldRun(survivingService), "a sibling project's probes must keep running")
        assertFalse(projectRow(survivorId)[Projects.deleted])
    }

    @Test
    fun `a project delete carries its grafana scrape credential down`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val integrationId = seedGrafanaIntegration(projectId)

        ProjectController.delete(orgId, projectId, ownerId)

        val row = transaction {
            GrafanaIntegrations.selectAll().where { GrafanaIntegrations.id eq integrationId }.single()
        }
        assertTrue(row[GrafanaIntegrations.deleted], "the scrape token authenticates on this flag alone")
        assertEquals(projectRow(projectId)[Projects.deletedAt], row[GrafanaIntegrations.deletedAt])
    }

    @Test
    fun `a project delete leaves its variables where they are`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val variableId = UUID.randomUUID()
        transaction {
            ProjectVariables.insert {
                it[id] = variableId
                it[ProjectVariables.projectId] = projectId
                it[key] = "endpoint"
                it[value] = "https://example.test"
                it[secret] = false
                it[encrypted] = false
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }

        ProjectController.delete(orgId, projectId, ownerId)

        val row = transaction {
            ProjectVariables.selectAll().where { ProjectVariables.id eq variableId }.single()
        }
        assertFalse(
            row[ProjectVariables.deleted],
            "variables are inert once the probes are stopped; the purge reaches them by join",
        )
    }

    // ── Workspace ──

    @Test
    fun `deleting a workspace stops the services in all its projects`() {
        val workspaceId = seedWorkspace()
        val firstProject = seedProject(workspaceId)
        val secondProject = seedProject(workspaceId)
        val firstService = seedService(firstProject)
        val secondService = seedService(secondProject)

        assertTrue(schedulerWouldRun(firstService))
        assertTrue(schedulerWouldRun(secondService))

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        assertFalse(schedulerWouldRun(firstService), "the service kept probing after its workspace was deleted")
        assertFalse(schedulerWouldRun(secondService))
        assertTrue(projectRow(firstProject)[Projects.deleted], "the projects go down with the workspace")
        assertTrue(projectRow(secondProject)[Projects.deleted])
    }

    @Test
    fun `a workspace delete stamps its whole subtree with one instant`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val serviceId = seedService(projectId)

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        val workspace = transaction {
            Workspaces.selectAll().where { Workspaces.id eq workspaceId }.single()
        }
        val stamp = workspace[Workspaces.deletedAt]
        assertNotNull(stamp)
        assertEquals(stamp, workspace[Workspaces.purgeAfter])
        assertEquals(stamp, projectRow(projectId)[Projects.deletedAt])
        assertEquals(stamp, projectRow(projectId)[Projects.purgeAfter])
        assertEquals(stamp, serviceRow(serviceId)[Services.deletedAt])
        assertEquals(stamp, serviceRow(serviceId)[Services.purgeAfter])
    }

    @Test
    fun `a workspace delete stops a service left running under an already-deleted project`() {
        val workspaceId = seedWorkspace()
        // Exactly the residue the bug left behind: the project row was flipped,
        // its service was not.
        val strandedProject = seedProject(workspaceId, deleted = true)
        val strandedService = seedService(strandedProject)

        assertTrue(schedulerWouldRun(strandedService), "the stranded service is still live before the delete")

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        assertFalse(schedulerWouldRun(strandedService))
    }

    @Test
    fun `a workspace delete does not re-stamp a project the user had already deleted`() {
        val workspaceId = seedWorkspace()
        val earlierDeletion = NOW.minus(3, ChronoUnit.DAYS)
        val alreadyGone = seedProject(workspaceId)
        transaction {
            Projects.update({ Projects.id eq alreadyGone }) {
                it[deleted] = true
                it[deletedAt] = earlierDeletion
            }
        }

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        assertEquals(
            earlierDeletion, projectRow(alreadyGone)[Projects.deletedAt],
            "a restore must put back what this delete took, not what the user deleted days ago",
        )
        assertNull(
            projectRow(alreadyGone)[Projects.purgeAfter],
            "a row this cascade did not touch keeps its own purge schedule",
        )
    }

    @Test
    fun `a workspace delete leaves its variables where they are`() {
        val workspaceId = seedWorkspace()
        val variableId = UUID.randomUUID()
        transaction {
            WorkspaceVariables.insert {
                it[id] = variableId
                it[WorkspaceVariables.workspaceId] = workspaceId
                it[key] = "region"
                it[value] = "eu"
                it[secret] = false
                it[encrypted] = false
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        val row = transaction {
            WorkspaceVariables.selectAll().where { WorkspaceVariables.id eq variableId }.single()
        }
        assertFalse(row[WorkspaceVariables.deleted])
    }

    @Test
    fun `a workspace delete leaves its notification silences alone`() {
        val workspaceId = seedWorkspace()
        val orgUserId = transaction {
            OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq ownerId) }
                .single()[OrgUsers.id]
        }
        val silenceId = UUID.randomUUID()
        transaction {
            NotificationSilences.insert {
                it[id] = silenceId
                it[NotificationSilences.orgUserId] = orgUserId
                it[NotificationSilences.workspaceId] = workspaceId
                it[channel] = "email"
            }
        }

        WorkspaceController.delete(orgId, workspaceId, ownerId)

        val survives = transaction {
            NotificationSilences.selectAll().where { NotificationSilences.id eq silenceId }.any()
        }
        assertTrue(survives, "silences carry no soft-delete of their own; the purge removes them by join")
    }

    // ── The repair for databases that already ran the broken delete ──

    @Test
    fun `the data migration stops services left live under a deleted container`() {
        val liveWorkspace = seedWorkspace()
        val deletedProject = seedProject(liveWorkspace, deleted = true)
        val strandedByProject = seedService(deletedProject)
        val integration = seedGrafanaIntegration(deletedProject)

        val deletedWorkspace = seedWorkspace()
        val projectUnderIt = seedProject(deletedWorkspace)
        val strandedByWorkspace = seedService(projectUnderIt)
        val workspaceStamp = NOW.minus(2, ChronoUnit.DAYS)
        transaction {
            Workspaces.update({ Workspaces.id eq deletedWorkspace }) {
                it[deleted] = true
                it[deletedAt] = workspaceStamp
            }
        }

        val untouched = seedWorkspace()
        val liveService = seedService(seedProject(untouched))

        assertTrue(schedulerWouldRun(strandedByProject), "the repair has something to repair")
        assertTrue(schedulerWouldRun(strandedByWorkspace))

        runDataRepairMigration()

        assertFalse(schedulerWouldRun(strandedByProject))
        assertFalse(schedulerWouldRun(strandedByWorkspace))
        assertTrue(liveService.let(::schedulerWouldRun), "a live tree is left alone")

        assertEquals(
            projectRow(deletedProject)[Projects.deletedAt],
            serviceRow(strandedByProject)[Services.deletedAt],
            "the service inherits the instant its project was deleted",
        )
        assertTrue(projectRow(projectUnderIt)[Projects.deleted], "projects under a deleted workspace go too")
        assertEquals(workspaceStamp, projectRow(projectUnderIt)[Projects.deletedAt])
        assertEquals(workspaceStamp, serviceRow(strandedByWorkspace)[Services.deletedAt])

        assertNull(
            serviceRow(strandedByProject)[Services.purgeAfter],
            "the repair stops the probes; erasing the backlog stays the operator's call",
        )

        val integrationRow = transaction {
            GrafanaIntegrations.selectAll().where { GrafanaIntegrations.id eq integration }.single()
        }
        assertTrue(integrationRow[GrafanaIntegrations.deleted])
    }

    @Test
    fun `the data migration does not re-stamp what it already repaired`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId, deleted = true)
        val serviceId = seedService(projectId)

        runDataRepairMigration()
        val firstPass = serviceRow(serviceId)[Services.deletedAt]

        runDataRepairMigration()

        assertEquals(firstPass, serviceRow(serviceId)[Services.deletedAt], "the repair is idempotent")
    }

    // ── The guard the scheduler now applies as well ──

    @Test
    fun `the scheduler's parent-aware query refuses a service under a deleted container`() {
        val workspaceId = seedWorkspace()
        val projectId = seedProject(workspaceId)
        val serviceId = seedService(projectId)

        // Simulate a cascade that missed this service: the container is gone,
        // the service row was left live.
        transaction {
            Projects.update({ Projects.id eq projectId }) {
                it[deleted] = true
                it[deletedAt] = NOW
            }
        }

        assertTrue(schedulerWouldRun(serviceId), "services alone still says yes — that is why the join exists")

        val parentAware = transaction {
            (Services innerJoin Projects innerJoin Workspaces).selectAll()
                .where {
                    (Services.id eq serviceId) and
                        (Services.isActive eq true) and
                        (Services.deleted eq false) and
                        (Projects.deleted eq false) and
                        (Workspaces.deleted eq false)
                }
                .any()
        }
        assertFalse(parentAware, "a missed cascade must never be able to probe again")
    }
}
