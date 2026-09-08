package dev.tracedown.gateway.controllers.services

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.data.services.UpdateServiceRequest
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The first save of a service switches it on.
 *
 * A service is created switched off with no script, so the save that gives it
 * one is the moment it becomes runnable — and the separate toggle afterwards is
 * the step everybody forgets, leaving a service that looks finished and never
 * probes anything.
 *
 * `services.version` is what makes that safe to automate, and these cases are
 * the proof: it is 1 only on a service nobody has saved yet, so a service its
 * owner deliberately switched off — necessarily at version 2 or more, since
 * enabling one requires a script and writing the script is itself a save — is
 * never switched back on behind their back.
 */
@Testcontainers
class ServiceAutoEnableTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_service_auto_enable_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        private const val SCRIPT = """get("https://example.com").expect(status: 200)"""
        private const val SCRIPT_V2 = """get("https://example.com/v2").expect(status: 200)"""

        private lateinit var orgId: UUID
        private lateinit var ownerId: UUID
        private lateinit var projectId: UUID

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
                    it[email] = "auto-enable-$ownerId@tracedown.test"
                    it[passwordHash] = "x"
                    it[displayName] = "owner"
                    it[createdAt] = NOW
                }

                orgId = UUID.randomUUID()
                Organizations.insert {
                    it[id] = orgId
                    it[name] = "auto-enable-org"
                    it[Organizations.ownerId] = ServiceAutoEnableTest.ownerId
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

                val workspaceId = UUID.randomUUID()
                Workspaces.insert {
                    it[id] = workspaceId
                    it[organizationId] = orgId
                    it[name] = "auto-enable-ws"
                    it[createdAt] = NOW
                }

                projectId = UUID.randomUUID()
                Projects.insert {
                    it[id] = projectId
                    it[Projects.workspaceId] = workspaceId
                    it[name] = "auto-enable-proj"
                    it[createdAt] = NOW
                }
            }
        }

        /** A service in whatever state a case needs. Created switched off, as `create` does. */
        private fun seedService(script: String, version: Int, isActive: Boolean = false): UUID {
            val id = UUID.randomUUID()
            transaction {
                Services.insert {
                    it[Services.id] = id
                    it[Services.projectId] = ServiceAutoEnableTest.projectId
                    it[name] = "svc-$id"
                    it[Services.script] = script
                    it[Services.version] = version
                    it[Services.isActive] = isActive
                    it[createdAt] = NOW
                }
            }
            return id
        }

        private fun storedIsActive(serviceId: UUID): Boolean = transaction {
            Services.selectAll()
                .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                .single()[Services.isActive]
        }
    }

    @Test
    fun `the first save switches a new service on`() {
        val serviceId = seedService(script = "", version = 1)

        val summary = ServiceController.update(
            orgId, serviceId, UpdateServiceRequest(script = SCRIPT, version = 1), ownerId,
        )

        // Reported in the response, so the caller reflects it without a refetch.
        assertTrue(summary.isActive)
        assertEquals(2, summary.version)
        assertTrue(storedIsActive(serviceId))
    }

    @Test
    fun `a service switched off after its first save is not switched back on`() {
        // Version 2 with a script and isActive false is exactly the state an
        // owner leaves behind by disabling a service they had been running.
        val serviceId = seedService(script = SCRIPT, version = 2)

        val summary = ServiceController.update(
            orgId, serviceId, UpdateServiceRequest(script = SCRIPT_V2, version = 2), ownerId,
        )

        assertFalse(summary.isActive)
        assertEquals(3, summary.version)
        assertFalse(storedIsActive(serviceId))
    }

    @Test
    fun `a first save that leaves the service scriptless leaves it switched off`() {
        // Renaming a service is a save, and it bumps the version — but a service
        // with no script cannot run, and the toggle endpoint would refuse to
        // enable it. This must not be the one place that does.
        val serviceId = seedService(script = "", version = 1)

        val summary = ServiceController.update(
            orgId, serviceId, UpdateServiceRequest(name = "renamed", version = 1), ownerId,
        )

        assertFalse(summary.isActive)
        assertEquals(2, summary.version)
        assertFalse(storedIsActive(serviceId))
    }

    @Test
    fun `a service already switched on stays on`() {
        val serviceId = seedService(script = SCRIPT, version = 3, isActive = true)

        val summary = ServiceController.update(
            orgId, serviceId, UpdateServiceRequest(schedule = "*/10 * * * *", version = 3), ownerId,
        )

        assertTrue(summary.isActive)
        assertEquals("*/10 * * * *", summary.schedule)
    }
}
