package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A script's writeback creates a metric whose key a deleted variable held.
 *
 * Service variables were unique on (service, key) over every row, deleted or
 * not — so writing back a metric under a deleted variable's key failed the
 * insert, and with it the whole result. The key is unique among live rows now.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WritebackDeletedKeyTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_writeback_deleted_key_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "writeback-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Writeback"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Writeback Org"
                it[ownerId] = userId
                it[createdAt] = NOW
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Workspace"
                it[createdAt] = NOW
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@WritebackDeletedKeyTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
        }
    }

    private fun newService(): UUID {
        val id = UUID.randomUUID()
        transaction {
            Services.insert {
                it[Services.id] = id
                it[Services.projectId] = this@WritebackDeletedKeyTest.projectId
                it[name] = "Service $id"
                it[createdAt] = NOW
            }
        }
        return id
    }

    /** A deleted metric, as a delete leaves it. */
    private fun deletedMetric(serviceId: UUID, key: String): UUID {
        val id = UUID.randomUUID()
        transaction {
            ServiceVariables.insert {
                it[ServiceVariables.id] = id
                it[ServiceVariables.serviceId] = serviceId
                it[ServiceVariables.key] = key
                it[value] = "old"
                it[secret] = false
                it[encrypted] = false
                it[deleted] = true
                it[deletedAt] = NOW
                it[purgeAfter] = NOW.plusSeconds(86_400)
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }
        return id
    }

    private fun persist(serviceId: UUID, variablesJson: String): UUID {
        val resultId = UUID.randomUUID()
        val envelope = Json.parseToJsonElement(
            """
            {
              "resultId": "$resultId",
              "serviceId": "$serviceId",
              "projectId": "$projectId",
              "workspaceId": "$workspaceId",
              "organizationId": "$orgId",
              "startedAt": "$NOW",
              "rawResult": {
                "outcome": "success",
                "elapsedMs": 40,
                "calls": [],
                "actions": { "variables": $variablesJson }
              }
            }
            """.trimIndent(),
        ).jsonObject
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))
        return resultId
    }

    private fun rows(serviceId: UUID): List<Triple<String, String, Boolean>> = transaction {
        ServiceVariables.selectAll().where { ServiceVariables.serviceId eq serviceId }
            .map { Triple(it[ServiceVariables.key], it[ServiceVariables.value], it[ServiceVariables.deleted]) }
    }

    @Test
    fun `writeback creates a metric whose key a deleted variable held`() {
        val service = newService()
        val gone = deletedMetric(service, "count")

        persist(service, """{"count": 5}""")
        persist(service, """{"count": 6}""")

        val all = rows(service)
        assertEquals(listOf(Triple("count", "6", false)), all.filter { !it.third })
        assertEquals(listOf(Triple("count", "old", true)), all.filter { it.third })
        transaction { assertEquals("count", ServiceVariables.selectAll().where { ServiceVariables.id eq gone }.single()[ServiceVariables.key]) }
    }
}
