package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
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
 * A step records the endpoint its call belongs to, as the envelope names it.
 *
 * The keys are the scheduler's word about the script it dispatched, so ingest
 * files them by index and invents nothing: an envelope that names fewer
 * endpoints than the run made calls leaves the rest unnamed, and one that names
 * none leaves every step unnamed. Those rows are keyed from their resolved URLs
 * at aggregation time instead.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointKeyIngestTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_endpoint_key_ingest_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
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

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "endpoint-key-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Endpoint Key"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Endpoint Key Org"
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
                it[Projects.workspaceId] = this@EndpointKeyIngestTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@EndpointKeyIngestTest.projectId
                it[name] = "Service"
                it[createdAt] = NOW
            }
        }
    }

    @Test
    fun `each step records the endpoint of the call at its index`() {
        val resultId = persist(
            calls = listOf("https://api.example.com/a", "https://api.example.com/b/7"),
            keysJson = """["GET https://api.example.com/a","GET https://api.example.com/b/{id}"]""",
        )
        assertEquals(
            listOf("GET https://api.example.com/a", "GET https://api.example.com/b/{id}"),
            keysOf(resultId),
        )
    }

    @Test
    fun `an envelope that names no endpoints leaves every step unnamed`() {
        val resultId = persist(calls = listOf("https://api.example.com/a"), keysJson = null)
        assertEquals(listOf(null), keysOf(resultId))
    }

    @Test
    fun `a run that made more calls than the envelope names leaves the rest unnamed`() {
        val resultId = persist(
            calls = listOf("https://api.example.com/a", "https://api.example.com/b"),
            keysJson = """["GET https://api.example.com/a"]""",
        )
        assertEquals(listOf("GET https://api.example.com/a", null), keysOf(resultId))
    }

    @Test
    fun `an entry that is not a usable key is dropped rather than guessed at`() {
        val resultId = persist(
            calls = listOf("https://api.example.com/a", "https://api.example.com/b", "https://api.example.com/c"),
            keysJson = """[null,"   ",17]""",
        )
        assertEquals(listOf(null, null, null), keysOf(resultId))
    }

    @Test
    fun `an over-long key is cut to what the column holds`() {
        val long = "GET https://api.example.com/" + "a".repeat(400)
        val resultId = persist(calls = listOf("https://api.example.com/a"), keysJson = """["$long"]""")
        assertEquals(listOf(long.take(210)), keysOf(resultId))
    }

    @Test
    fun `a redelivered envelope changes nothing`() {
        val resultId = UUID.randomUUID()
        val envelope = envelope(
            resultId,
            listOf("https://api.example.com/a", "https://api.example.com/b"),
            """["GET https://api.example.com/a","GET https://api.example.com/b"]""",
        )
        assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, ResultPersistenceService.persist(envelope))
        // Delivery from the queue is at-least-once: the same message arrives
        // again whenever a consumer died between committing and acking.
        assertEquals(ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED, ResultPersistenceService.persist(envelope))

        assertEquals(
            listOf("GET https://api.example.com/a", "GET https://api.example.com/b"),
            keysOf(resultId),
            "a second delivery must not add steps or relabel the ones there",
        )
    }

    @Test
    fun `a redelivery that names different endpoints does not overwrite the first`() {
        // Two deliveries of one run cannot disagree in practice — the keys are
        // a property of the message. If they ever did, the row already written
        // is the one the counters were built from, and it stands.
        val resultId = UUID.randomUUID()
        ResultPersistenceService.persist(
            envelope(resultId, listOf("https://api.example.com/a"), """["GET https://api.example.com/a"]"""),
        )
        ResultPersistenceService.persist(
            envelope(resultId, listOf("https://api.example.com/a"), """["GET /something-else"]"""),
        )
        assertEquals(listOf("GET https://api.example.com/a"), keysOf(resultId))
    }

    @Test
    fun `a run with no calls records no steps and no keys`() {
        val resultId = persist(calls = emptyList(), keysJson = """["GET https://api.example.com/a"]""")
        assertEquals(emptyList<String?>(), keysOf(resultId))
        assertNull(keysOf(resultId).firstOrNull())
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun persist(calls: List<String>, keysJson: String?): UUID {
        val resultId = UUID.randomUUID()
        assertEquals(
            ResultPersistenceService.PersistOutcome.PERSISTED,
            ResultPersistenceService.persist(envelope(resultId, calls, keysJson)),
        )
        return resultId
    }

    private fun envelope(resultId: UUID, calls: List<String>, keysJson: String?) = Json.parseToJsonElement(
        """
        {
          "resultId": "$resultId",
          "serviceId": "$serviceId",
          "projectId": "$projectId",
          "workspaceId": "$workspaceId",
          "organizationId": "$orgId",
          "startedAt": "$NOW",
          ${if (keysJson != null) "\"endpointKeys\": $keysJson," else ""}
          "rawResult": {
            "outcome": "success",
            "elapsedMs": 40,
            "calls": [${calls.joinToString(",") { call ->
            """{"request":{"url":"$call","method":"get"},"response":{"status":200,"responseTimeMs":30},"assertions":[]}"""
        }}]
          }
        }
        """.trimIndent(),
    ).jsonObject

    /** The endpoint key of every step of a result, in step order. */
    private fun keysOf(resultId: UUID): List<String?> = transaction {
        ProbeSteps.selectAll()
            .where { ProbeSteps.probeResultId eq resultId }
            .orderBy(ProbeSteps.stepNum, SortOrder.ASC)
            .map { it[ProbeSteps.endpointKey] }
    }
}
