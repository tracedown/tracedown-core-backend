package dev.tracedown.ingestor.services

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.RunRequests
import dev.tracedown.common.models.RunState
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.like
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
 * What a recorded result writes to the outbox for the readers of the event
 * feed: whether the service's status moved and from what, a skipped run as an
 * event of its own type, a writeback's variable changes — each row carrying
 * its organization, and none a variable's value.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OutboxEventsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_outbox_events_test")
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
                it[email] = "events-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Events"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Events Org"
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
                it[Projects.workspaceId] = this@OutboxEventsTest.workspaceId
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
                it[Services.projectId] = this@OutboxEventsTest.projectId
                it[name] = "Service $id"
                it[createdAt] = NOW
            }
        }
        return id
    }

    private fun persist(serviceId: UUID, outcome: String, extra: String = "", runId: UUID? = null): UUID =
        persistRun(serviceId, outcome, extra, resultId = runId ?: UUID.randomUUID(), runId = runId).let {
            assertEquals(ResultPersistenceService.PersistOutcome.PERSISTED, it.second)
            it.first
        }

    /** Persists one result, of a run asked for when [runId] is given; answers its id and what the persist did. */
    private fun persistRun(
        serviceId: UUID,
        outcome: String,
        extra: String = "",
        resultId: UUID = UUID.randomUUID(),
        runId: UUID? = null,
        runSize: Int = 1,
    ): Pair<UUID, ResultPersistenceService.PersistOutcome> {
        val run = if (runId == null) "" else ""","trigger": "manual", "runId": "$runId", "runSize": $runSize"""
        val envelope = Json.parseToJsonElement(
            """
            {
              "resultId": "$resultId",
              "serviceId": "$serviceId",
              "projectId": "$projectId",
              "workspaceId": "$workspaceId",
              "organizationId": "$orgId",
              "startedAt": "$NOW"$run,
              "rawResult": { "outcome": "$outcome", "elapsedMs": 40, "calls": [] $extra }
            }
            """.trimIndent(),
        ).jsonObject
        return resultId to ResultPersistenceService.persist(envelope)
    }

    /** A pending request for a run of [service]. */
    private fun requestRun(service: UUID): UUID {
        val runId = UUID.randomUUID()
        transaction {
            RunRequests.insert {
                it[id] = runId
                it[serviceId] = service
                it[organizationId] = orgId
                it[requestedAt] = NOW
                it[state] = RunState.PENDING
            }
        }
        return runId
    }

    /** The settlements written for [runId], oldest first. */
    private fun settlements(runId: UUID): List<JsonObject> = transaction {
        Outbox.selectAll().where { Outbox.eventType eq RunState.SETTLED_EVENT }
            .orderBy(Outbox.seq, SortOrder.ASC)
            .map { it[Outbox.payload] }
            .filter { it["runId"]!!.jsonPrimitive.content == runId.toString() }
    }

    /** The outbox rows about [aggregateId], with their organization column. */
    private fun rowsOf(aggregateId: UUID): List<Triple<String, JsonObject, UUID?>> = transaction {
        Outbox.selectAll().where { Outbox.aggregateId eq aggregateId }
            .map { Triple(it[Outbox.eventType], it[Outbox.payload], it[Outbox.organizationId]) }
    }

    @Test
    fun `a result says whether it moved the service's status, and from what`() {
        val service = newService()
        val first = rowsOf(persist(service, "success")).single()
        assertEquals("probe_result.created", first.first)
        assertEquals(orgId, first.third)
        assertEquals("true", first.second["statusChanged"]!!.jsonPrimitive.content)
        assertNull(first.second["previousStatus"], "A first run has no previous status")

        val same = rowsOf(persist(service, "success")).single().second
        assertEquals("false", same["statusChanged"]!!.jsonPrimitive.content)
        assertEquals("success", same["previousStatus"]!!.jsonPrimitive.content)

        val changed = rowsOf(persist(service, "failure")).single().second
        assertEquals("true", changed["statusChanged"]!!.jsonPrimitive.content)
        assertEquals("success", changed["previousStatus"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a skipped run is an event of its own type, with its reason`() {
        val service = newService()
        val (type, payload, org) = rowsOf(persist(service, "skipped", """, "reason": "dispatch_queue_full"""")).single()
        assertEquals(dev.tracedown.common.models.OutboxEmit.PROBE_RESULT_SKIPPED, type)
        assertEquals(orgId, org)
        assertEquals("skipped", payload["status"]!!.jsonPrimitive.content)
        assertEquals("dispatch_queue_full", payload["reason"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a writeback announces the variables it creates and changes, never their values`() {
        val service = newService()
        persist(service, "success", """, "actions": { "variables": { "count": 5 } }""")
        persist(service, "success", """, "actions": { "variables": { "count": 6 } }""")
        val rows = transaction {
            Outbox.selectAll().where { Outbox.eventType like "resource.variable.%" }
                .orderBy(Outbox.seq, SortOrder.ASC)
                .filter { it[Outbox.payload]["parentId"]?.jsonPrimitive?.content == service.toString() }
                .map { Triple(it[Outbox.eventType], it[Outbox.payload], it[Outbox.organizationId]) }
        }
        assertEquals(listOf("resource.variable.created", "resource.variable.updated"), rows.map { it.first })
        rows.forEach { (_, payload, org) ->
            assertEquals(orgId, org)
            assertEquals("service", payload["scope"]!!.jsonPrimitive.content)
            assertEquals(setOf("id", "orgId", "scope", "parentId"), payload.keys)
            assertFalse(payload.toString().contains("\"6\"") || payload.toString().contains(":5"), "A value reached the outbox: $payload")
        }
    }

    @Test
    fun `a run asked for announces its settlement, with the run's status`() {
        val service = newService()
        val runId = UUID.randomUUID()
        transaction {
            RunRequests.insert {
                it[id] = runId
                it[serviceId] = service
                it[organizationId] = orgId
                it[requestedAt] = NOW
                it[state] = RunState.PENDING
            }
        }
        persist(service, "failure", runId = runId)
        val settled = transaction {
            Outbox.selectAll().where { Outbox.eventType eq RunState.SETTLED_EVENT }
                .map { it[Outbox.payload] to it[Outbox.organizationId] }
                .filter { it.first["runId"]!!.jsonPrimitive.content == runId.toString() }
        }.single()
        assertEquals(orgId, settled.second)
        assertEquals("done", settled.first["state"]!!.jsonPrimitive.content)
        assertEquals("failure", settled.first["status"]!!.jsonPrimitive.content)
        assertEquals(service.toString(), settled.first["serviceId"]!!.jsonPrimitive.content)
        assertNull(settled.first["reason"])
    }

    @Test
    fun `a result's event carries exactly its documented fields`() {
        val service = newService()
        val first = rowsOf(persist(service, "success")).single().second
        assertEquals(
            setOf("resultId", "serviceId", "projectId", "workspaceId", "organizationId", "status", "runDurationMs", "statusChanged"),
            first.keys,
        )
        val second = rowsOf(persist(service, "failure")).single().second
        assertEquals(first.keys + "previousStatus", second.keys)
    }

    @Test
    fun `a run on several agents settles once, on its last result, with the worst status`() {
        val service = newService()
        val runId = requestRun(service)
        persistRun(service, "success", resultId = runId, runId = runId, runSize = 2)
        assertEquals(emptyList<JsonObject>(), settlements(runId), "not settled on the first of two")
        persistRun(service, "failure", runId = runId, runSize = 2)
        val settled = settlements(runId).single()
        assertEquals("done", settled["state"]!!.jsonPrimitive.content)
        assertEquals("failure", settled["status"]!!.jsonPrimitive.content)
        assertNull(settled["superseded"])

        // A redelivery of a result already in changes nothing, and says nothing.
        assertEquals(
            ResultPersistenceService.PersistOutcome.ALREADY_PERSISTED,
            persistRun(service, "success", resultId = runId, runId = runId, runSize = 2).second,
        )
        assertEquals(1, settlements(runId).size)
    }

    @Test
    fun `a run skipped says why, and a result that replaces the skip settles it again as superseded`() {
        val service = newService()
        val runId = requestRun(service)
        persistRun(service, "skipped", ""","reason": "run_already_running"""", resultId = runId, runId = runId)
        val skipped = settlements(runId).single()
        assertEquals("skipped", skipped["state"]!!.jsonPrimitive.content)
        assertEquals("run_already_running", skipped["reason"]!!.jsonPrimitive.content)

        persistRun(service, "success", resultId = runId, runId = runId)
        val (first, second) = settlements(runId)
        assertEquals(skipped, first)
        assertEquals("done", second["state"]!!.jsonPrimitive.content)
        assertEquals("true", second["superseded"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a metric written back with the value it has is not a change`() {
        val service = newService()
        repeat(2) { persist(service, "success", """, "actions": { "variables": { "same": 1 } }""") }
        val changes = transaction {
            Outbox.selectAll().where { Outbox.eventType like "resource.variable.%" }
                .filter { it[Outbox.payload]["parentId"]?.jsonPrimitive?.content == service.toString() }
                .map { it[Outbox.eventType] }
        }
        assertEquals(listOf("resource.variable.created"), changes)
    }

    @Test
    fun `a writeback names at most as many keys as a service may hold, as configured`() {
        val limits = dev.tracedown.common.variables.VariableLimits
        try {
            limits.init(7)
            val service = newService()
            val keys = (1..12).joinToString(",") { "\"k$it\": $it" }
            persist(service, "success", """, "actions": { "variables": { $keys } }""")
            val written = transaction {
                dev.tracedown.common.models.ServiceVariables.selectAll()
                    .where { dev.tracedown.common.models.ServiceVariables.serviceId eq service }.count()
            }
            assertEquals(7L, written)
        } finally {
            limits.init(limits.DEFAULT_MAX_PER_RESOURCE)
        }
    }

    @Test
    fun `results of one service ingested at once agree on one status change, without deadlocking`() {
        val service = newService()
        persist(service, "success")
        val deadlocksBefore = deadlocks()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        val ids = try {
            (1..8).map {
                pool.submit<UUID> { retrying { persist(service, "failure") } }
            }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
        val changed = ids.map { rowsOf(it).single().second["statusChanged"]!!.jsonPrimitive.content }
        assertEquals(1, changed.count { it == "true" }, "$changed")
        assertEquals(deadlocksBefore, deadlocks(), "a deadlock was detected")
    }

    /** Runs [block] again on a failure — a serialization failure is what the consumer redelivers on. */
    private fun <T> retrying(block: () -> T): T {
        repeat(20) {
            try {
                return block()
            } catch (_: Exception) {
                Thread.sleep(20)
            }
        }
        return block()
    }

    private fun deadlocks(): Long = transaction {
        var n = 0L
        exec("SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()") { rs -> if (rs.next()) n = rs.getLong(1) }
        n
    }
}
