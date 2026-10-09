package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.RunRequests
import dev.tracedown.common.models.RunState
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.runs.RunTrigger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.flywaydb.core.Flyway
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
 * What started a run is recorded with it, and a run somebody asked for under
 * an id settles their request in the same transaction as its result.
 *
 * An envelope from a scheduler that predates the `trigger` field is filed as
 * scheduled, which is what the column says of every row before it existed.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunTriggerIngestTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_run_trigger_ingest_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val userId: UUID = UUID.randomUUID()
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
            Users.insert {
                it[id] = userId
                it[email] = "run-trigger-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Run Trigger"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Run Trigger Org"
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
                it[Projects.workspaceId] = this@RunTriggerIngestTest.workspaceId
                it[name] = "Project"
                it[createdAt] = NOW
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@RunTriggerIngestTest.projectId
                it[name] = "Service"
                it[createdAt] = NOW
            }
        }
    }

    @Test
    fun `the trigger an envelope names is recorded, and one that names none is a scheduled run`() {
        val manual = persist(trigger = RunTrigger.MANUAL)
        val scheduled = persist(trigger = RunTrigger.SCHEDULE)
        val unnamed = persist(trigger = null)
        val unknown = persist(trigger = "cron")
        assertEquals(RunTrigger.MANUAL, triggerOf(manual))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(scheduled))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(unnamed))
        assertEquals(RunTrigger.SCHEDULE, triggerOf(unknown))
    }

    @Test
    fun `a manual run filed under a request's id settles it, done or skipped`() {
        val done = request()
        persist(id = done, trigger = RunTrigger.MANUAL)
        assertEquals(RunState.DONE, stateOf(done))

        val skipped = request()
        persist(id = skipped, trigger = RunTrigger.MANUAL, outcome = "skipped", reason = RunTrigger.SKIP_ALREADY_RUNNING)
        assertEquals(RunState.SKIPPED, stateOf(skipped))
    }

    @Test
    fun `a scheduled run under a request's id does not settle it, and a redelivery changes nothing`() {
        val request = request()
        persist(id = request, trigger = RunTrigger.SCHEDULE)
        assertEquals(RunState.PENDING, stateOf(request))

        val settled = request()
        persist(id = settled, trigger = RunTrigger.MANUAL)
        assertEquals(
            ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED,
            ResultPersistenceService.persist(envelope(settled, RunTrigger.MANUAL, "skipped", RunTrigger.SKIP_ALREADY_RUNNING)),
        )
        assertEquals(RunState.DONE, stateOf(settled))
    }

    @Test
    fun `a run on several agents is settled by the last of its results`() {
        val run = request()
        val startedAt = NOW.plusMillis(123)
        persist(id = run, trigger = RunTrigger.MANUAL, run = run, runSize = 2, startedAt = startedAt)
        assertEquals(RunState.PENDING, stateOf(run), "one of two results is not the run")
        persist(trigger = RunTrigger.MANUAL, outcome = "failure", run = run, runSize = 2, startedAt = startedAt)
        assertEquals(RunState.DONE, stateOf(run))
    }

    @Test
    fun `twelve results of one run ingested at once settle it with every row`() {
        val run = request()
        val startedAt = NOW.plusMillis(456)
        val ids = (0 until 12).map { if (it == 0) run else UUID.randomUUID() }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(12)
        try {
            val start = java.util.concurrent.CountDownLatch(1)
            val outcomes = ids.map { id ->
                pool.submit<ResultPersistenceService.PersistOutcome> {
                    start.await()
                    ResultPersistenceService.persist(
                        envelope(id, RunTrigger.MANUAL, "success", null, run = run, runSize = 12, startedAt = startedAt),
                    )
                }
            }
            start.countDown()
            outcomes.forEach { assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, it.get(60, java.util.concurrent.TimeUnit.SECONDS)) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(RunState.DONE, stateOf(run))
        assertEquals(12L, transaction { ProbeResults.selectAll().where { ProbeResults.runId eq run }.count() })
    }

    @Test
    fun `a real result replaces the skip its run was first answered with`() {
        val run = request()
        persist(id = run, trigger = RunTrigger.MANUAL, outcome = "skipped", reason = RunTrigger.SKIP_ALREADY_QUEUED)
        assertEquals(RunState.SKIPPED, stateOf(run))
        persist(id = run, trigger = RunTrigger.MANUAL)
        assertEquals(RunState.DONE, stateOf(run))
        assertEquals("success", transaction { ProbeResults.selectAll().where { ProbeResults.id eq run }.single()[ProbeResults.status] })
        // Any skip: a result filed under the id is what happened.
        val shed = request()
        persist(id = shed, trigger = RunTrigger.MANUAL, outcome = "skipped", reason = "dispatch_queue_full")
        persist(id = shed, trigger = RunTrigger.MANUAL)
        assertEquals(RunState.DONE, stateOf(shed))
        // A skip never replaces a result, and a result never replaces a result.
        assertEquals(
            ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED,
            ResultPersistenceService.persist(envelope(shed, RunTrigger.MANUAL, "skipped", RunTrigger.SKIP_ALREADY_RUNNING)),
        )
        assertEquals(
            ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED,
            ResultPersistenceService.persist(envelope(shed, RunTrigger.MANUAL, "failure", null)),
        )
    }

    @Test
    fun `a result settles only a request of its own service`() {
        val run = request()
        val other = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = other
                it[Services.projectId] = this@RunTriggerIngestTest.projectId
                it[name] = "Other"
                it[createdAt] = NOW
            }
        }
        persist(trigger = RunTrigger.MANUAL, run = run, service = other)
        assertEquals(RunState.PENDING, stateOf(run))
    }

    @Test
    fun `a skip answering a request raises nothing`() {
        for (reason in listOf(
            RunTrigger.SKIP_SERVICE_INACTIVE, RunTrigger.SKIP_SCRIPT_MISSING, RunTrigger.SKIP_IN_SERVICE_WINDOW,
            RunTrigger.SKIP_HELD, RunTrigger.SKIP_ALREADY_RUNNING, RunTrigger.SKIP_ALREADY_QUEUED, RunTrigger.SKIP_NOT_DELIVERED,
        )) {
            assertNull(SkippedProbeAlert.alertType(reason), reason)
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun request(): UUID {
        val id = UUID.randomUUID()
        transaction {
            RunRequests.insert {
                it[RunRequests.id] = id
                it[RunRequests.serviceId] = this@RunTriggerIngestTest.serviceId
                it[organizationId] = orgId
                it[requestedBy] = userId
                it[requestedAt] = NOW
            }
        }
        return id
    }

    private fun persist(
        id: UUID = UUID.randomUUID(),
        trigger: String?,
        outcome: String = "success",
        reason: String? = null,
        run: UUID? = if (trigger == RunTrigger.MANUAL) id else null,
        runSize: Int = 1,
        startedAt: Instant = NOW,
        service: UUID = serviceId,
    ): UUID {
        assertEquals(
            ResultPersistenceService.PersistOutcome.PERSISTED,
            ResultPersistenceService.persist(envelope(id, trigger, outcome, reason, run, runSize, startedAt, service)),
        )
        return id
    }

    private fun triggerOf(id: UUID): String = transaction {
        ProbeResults.selectAll().where { ProbeResults.id eq id }.single()[ProbeResults.trigger]
    }

    private fun stateOf(id: UUID): String = transaction {
        RunRequests.selectAll().where { RunRequests.id eq id }.single()[RunRequests.state]
    }

    private fun envelope(
        id: UUID,
        trigger: String?,
        outcome: String,
        reason: String?,
        run: UUID? = if (trigger == RunTrigger.MANUAL) id else null,
        runSize: Int = 1,
        startedAt: Instant = NOW,
        service: UUID = serviceId,
    ) = Json.parseToJsonElement(
        """
        {
          "resultId": "$id",
          "serviceId": "$service",
          "projectId": "$projectId",
          "workspaceId": "$workspaceId",
          "organizationId": "$orgId",
          "startedAt": "$startedAt",
          ${if (trigger != null) "\"trigger\": \"$trigger\"," else ""}
          ${if (run != null) "\"runId\": \"$run\", \"runSize\": $runSize," else ""}
          "rawResult": {"outcome": "$outcome", "elapsedMs": 0${if (reason != null) ", \"reason\": \"$reason\"" else ""}}
        }
        """.trimIndent(),
    ).jsonObject
}
