package dev.tracedown.scheduler.variables

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * A deleted variable never reaches a probe, even when a live variable now
 * holds the same key: keys are unique among live rows only, so both rows
 * exist side by side.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedVariableResolveTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_deleted_variable_resolve_test")
            .withUsername("test")
            .withPassword("test")
    }

    private val projectId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

        val now = Instant.now()
        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "resolve-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Resolve"
                it[createdAt] = now
            }
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "Resolve Org"
                it[ownerId] = userId
                it[createdAt] = now
            }
            val workspaceId = UUID.randomUUID()
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Workspace"
                it[createdAt] = now
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = workspaceId
                it[name] = "Project"
                it[createdAt] = now
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@DeletedVariableResolveTest.projectId
                it[name] = "Service"
                it[createdAt] = now
            }

            fun serviceVar(key: String, value: String, deleted: Boolean, systemType: String? = null) {
                val id = UUID.randomUUID()
                ServiceVariables.insert {
                    it[ServiceVariables.id] = id
                    it[ServiceVariables.serviceId] = this@DeletedVariableResolveTest.serviceId
                    it[ServiceVariables.key] = key
                    it[ServiceVariables.value] = value
                    it[secret] = false
                    it[encrypted] = false
                    it[ServiceVariables.systemType] = systemType
                    it[ServiceVariables.deleted] = deleted
                    it[createdAt] = now
                    it[updatedAt] = now
                }
            }
            serviceVar("count", "deleted-value", deleted = true)
            serviceVar("count", "live-value", deleted = false)
            serviceVar("trackBaseline", "deleted-config", deleted = true, systemType = "config")
            serviceVar("trackBaseline", "false", deleted = false, systemType = "config")

            val goneId = UUID.randomUUID()
            ProjectVariables.insert {
                it[id] = goneId
                it[ProjectVariables.projectId] = this@DeletedVariableResolveTest.projectId
                it[key] = "gone"
                it[value] = "deleted-project-value"
                it[secret] = false
                it[encrypted] = false
                it[deleted] = true
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    @Test
    fun `only live variables are injected`() {
        val result = VariableResolver.resolve(serviceId, "get(\"https://example.com/\$s.count/\$p.gone\")")

        assertEquals("live-value", result.variables["s_count"]?.jsonPrimitive?.content)
        assertEquals("false", result.variables["trackBaseline"]?.jsonPrimitive?.content)
        assertFalse(result.variables.containsKey("p_gone"))
        assertFalse(result.variables.values.any { it.jsonPrimitive.content.startsWith("deleted-") }, result.variables.toString())
    }
}
