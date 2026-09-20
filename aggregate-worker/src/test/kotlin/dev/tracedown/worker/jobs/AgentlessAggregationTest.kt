package dev.tracedown.worker.jobs

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeAggregates
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Aggregation of runs that carry no agent.
 *
 * A dispatched run is attributed to the agent that executed it, but not every
 * run is dispatched: the single-jar edition runs probes in its own process and
 * records them with no agent at all. Those rows are ordinary finished results —
 * they are not shed ticks, and the rollup has to count them.
 *
 * The per-agent rollup, however, groups by `probe_agent_id` and upserts on an
 * index that includes it, and Postgres treats two NULLs there as distinct. A
 * group whose agent is NULL therefore never matches its own earlier row and is
 * inserted afresh on every run — into the key space the all-agents rollup owns.
 * These cases pin that a service without agents aggregates, and keeps
 * aggregating on the second pass over the same window.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentlessAggregationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_agentless_aggregation_test")
            .withUsername("test")
            .withPassword("test")

        private val ZONE: ZoneId = ZoneId.systemDefault()
    }

    /**
     * Midnight, local time, two days ago. Local because `date_trunc` runs on
     * the wall clock the rows were written in — a fixture pinned to UTC would
     * land in a different day on a machine that is not.
     */
    private val base: Instant = LocalDate.now(ZONE).minusDays(2).atStartOfDay(ZONE).toInstant()

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()
    private val projectId: UUID = UUID.randomUUID()

    /** Every run on this service has no agent, as a single-jar install's do. */
    private val agentlessServiceId: UUID = UUID.randomUUID()

    /** Runs on this one are split between a real agent and no agent at all. */
    private val mixedServiceId: UUID = UUID.randomUUID()

    private var agentId: Long = 0

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
                it[email] = "agentless-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Agentless Aggregation"
                it[createdAt] = base
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Agentless Org"
                it[ownerId] = userId
                it[createdAt] = base
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Workspace"
                it[createdAt] = base
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@AgentlessAggregationTest.workspaceId
                it[name] = "Project"
                it[createdAt] = base
            }
            for ((id, name) in listOf(agentlessServiceId to "Agentless", mixedServiceId to "Mixed")) {
                Services.insert {
                    it[Services.id] = id
                    it[Services.projectId] = this@AgentlessAggregationTest.projectId
                    it[Services.name] = name
                    it[createdAt] = base
                }
            }

            agentId = ProbeAgents.insert {
                it[slug] = "agentless-test-agent"
                it[label] = "Agentless Test Agent"
                it[agentUri] = "https://agentless-test-agent.example.test:8443"
                it[publicKey] = "x"
                it[lastPing] = base
                it[lastStatus] = "success"
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = 0
                it[createdAt] = base
            }[ProbeAgents.id]

            // Hour 10, no agent: three finished runs, one of them a failure.
            result(agentlessServiceId, base.plus(10, ChronoUnit.HOURS), "success", null, 100)
            result(agentlessServiceId, base.plus(10, ChronoUnit.HOURS), "success", null, 200)
            result(agentlessServiceId, base.plus(10, ChronoUnit.HOURS), "failure", null, 300)
            // Hour 11, still no agent — a second bucket to build.
            result(agentlessServiceId, base.plus(11, ChronoUnit.HOURS), "timeout", null, 400)
            // A tick that never ran: history, not a call, and never aggregated.
            result(agentlessServiceId, base.plus(11, ChronoUnit.HOURS), "skipped", null, 0)

            // The mixed service: two runs on the agent, two without one, all in
            // the same hour, so the rollup and the per-agent row disagree on
            // purpose.
            result(mixedServiceId, base.plus(10, ChronoUnit.HOURS), "success", agentId, 100)
            result(mixedServiceId, base.plus(10, ChronoUnit.HOURS), "failure", agentId, 300)
            result(mixedServiceId, base.plus(10, ChronoUnit.HOURS), "success", null, 500)
            result(mixedServiceId, base.plus(10, ChronoUnit.HOURS), "success", null, 700)
        }
    }

    // ── Hourly ──────────────────────────────────────────────────────────

    @Test
    fun `a service whose runs carry no agent aggregates, and aggregates again`() {
        runHourly()
        assertAgentlessHourly()

        // The second pass over the same window is where the per-agent insert
        // collides with the rollup row it has no business writing.
        runHourly()
        assertAgentlessHourly()
    }

    private fun assertAgentlessHourly() {
        val rows = aggregates(agentlessServiceId, "hourly")
        assertEquals(2, rows.size, "one rollup row per hour, and nothing else")
        assertTrue(rows.all { it.agentId == null }, "a service with no agents has only rollup rows")

        val first = rows.single { it.bucketStart == truncated(base.plus(10, ChronoUnit.HOURS), "hour") }
        assertEquals(3, first.probeCount, "three finished runs in the hour")
        assertEquals(1f / 3f, first.errorRate!!, 0.001f)
        assertEquals(2f / 3f, first.uptimePct!!, 0.001f)
        assertEquals(200, first.p50Ms, "the median of 100, 200 and 300")
        assertEquals(290, first.p95Ms)

        val second = rows.single { it.bucketStart == truncated(base.plus(11, ChronoUnit.HOURS), "hour") }
        assertEquals(1, second.probeCount, "the shed tick in the same hour is not a run")
        assertEquals(1f, second.errorRate!!, 0.001f)
        assertEquals(0f, second.uptimePct!!, 0.001f)
        assertEquals(400, second.p50Ms)
    }

    @Test
    fun `a bucket holding both dispatched and in-process runs keeps them apart`() {
        runHourly()
        assertMixedHourly()

        runHourly()
        assertMixedHourly()
    }

    private fun assertMixedHourly() {
        val rows = aggregates(mixedServiceId, "hourly")
        assertEquals(2, rows.size, "one rollup row and one per-agent row")

        val rollup = rows.single { it.agentId == null }
        assertEquals(4, rollup.probeCount, "the rollup counts the runs without an agent too")
        assertEquals(1f / 4f, rollup.errorRate!!, 0.001f)
        assertEquals(400, rollup.p50Ms, "the median of 100, 300, 500 and 700")

        val perAgent = rows.single { it.agentId != null }
        assertEquals(agentId, perAgent.agentId)
        assertEquals(2, perAgent.probeCount, "only the runs that agent actually executed")
        assertEquals(200, perAgent.p50Ms, "the median of 100 and 300")
    }

    // ── Daily ───────────────────────────────────────────────────────────

    @Test
    fun `the daily rollup of agentless runs is idempotent too`() {
        runDaily()
        assertAgentlessDaily()

        runDaily()
        assertAgentlessDaily()
    }

    private fun assertAgentlessDaily() {
        val rows = aggregates(agentlessServiceId, "daily")
        assertEquals(1, rows.size, "both hours in one day, in one rollup row")
        val day = rows.single()
        assertNull(day.agentId)
        assertEquals(truncated(base.plus(10, ChronoUnit.HOURS), "day"), day.bucketStart)
        assertEquals(4, day.probeCount)
        assertEquals(0.5f, day.errorRate!!, 0.001f)
        assertEquals(0.5f, day.uptimePct!!, 0.001f)
        assertEquals(250, day.p50Ms, "the median of 100, 200, 300 and 400")
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun runHourly() = runBlocking {
        HourlyAggregationJob(
            redisB = { throw UnsupportedOperationException("no cache in this test") },
            clock = { base.plus(12, ChronoUnit.HOURS) },
        ).execute()
    }

    private fun runDaily() = runBlocking {
        DailyAggregationJob(clock = { Instant.now() }).execute()
    }

    private class Aggregate(
        val agentId: Long?,
        val bucketStart: Instant,
        val probeCount: Int,
        val p50Ms: Int?,
        val p95Ms: Int?,
        val errorRate: Float?,
        val uptimePct: Float?,
    )

    private fun aggregates(service: UUID, bucketType: String): List<Aggregate> = transaction {
        ProbeAggregates.selectAll()
            .where { ProbeAggregates.serviceId eq service }
            .filter { it[ProbeAggregates.bucketType] == bucketType }
            .map {
                Aggregate(
                    agentId = it[ProbeAggregates.probeAgentId],
                    bucketStart = it[ProbeAggregates.bucketStart],
                    probeCount = it[ProbeAggregates.probeCount],
                    p50Ms = it[ProbeAggregates.p50Ms],
                    p95Ms = it[ProbeAggregates.p95Ms],
                    errorRate = it[ProbeAggregates.errorRate],
                    uptimePct = it[ProbeAggregates.uptimePct],
                )
            }
    }

    /**
     * An instant truncated the way `date_trunc` truncates it — on the local
     * wall clock the row was written in, not on UTC.
     */
    private fun truncated(instant: Instant, unit: String): Instant {
        val local = instant.atZone(ZONE)
        val floored = when (unit) {
            "hour" -> local.truncatedTo(ChronoUnit.HOURS)
            else -> local.toLocalDate().atStartOfDay(ZONE)
        }
        return floored.toInstant()
    }

    private fun result(service: UUID, at: Instant, status: String, agent: Long?, durationMs: Int) {
        ProbeResults.insert {
            it[id] = UUID.randomUUID()
            it[serviceId] = service
            it[probeAgentId] = agent
            it[startedAt] = at
            it[ProbeResults.status] = status
            it[runDurationMs] = durationMs
            it[rawResult] = JsonObject(emptyMap())
            it[projectId] = this@AgentlessAggregationTest.projectId
            it[workspaceId] = this@AgentlessAggregationTest.workspaceId
            it[organizationId] = orgId
        }
    }
}
