package dev.tracedown.worker.jobs

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeStepAggregates
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.util.EndpointKeySql
import dev.tracedown.common.util.EndpointKeys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
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
 * The per-endpoint rollups: how they are built, that rebuilding a window is a
 * no-op, and that they age out and are purged exactly like the per-service
 * rollups beside them.
 *
 * Also the parity proof for the one rule that exists twice — the key a step
 * without one is filed under, written as a Kotlin function for the read path
 * and as a SQL expression for the aggregation.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StepAggregationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_step_aggregation_test")
            .withUsername("test")
            .withPassword("test")

        private val ZONE: ZoneId = ZoneId.systemDefault()

        private const val KEY_A = "GET https://api.example.com/a"
        private const val KEY_B = "GET https://api.example.com/b/{id}"
        private const val KEY_LEGACY = "* https://api.example.com/legacy/{id}"
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

    /** The service the aggregation runs read. */
    private val serviceId: UUID = UUID.randomUUID()

    /** A service of its own for the retention and purge cases. */
    private val lifecycleServiceId: UUID = UUID.randomUUID()

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
                it[email] = "step-agg-$userId@tracedown.test"
                it[passwordHash] = "x"
                it[displayName] = "Step Aggregation"
                it[createdAt] = base
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Step Aggregation Org"
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
                it[Projects.workspaceId] = this@StepAggregationTest.workspaceId
                it[name] = "Project"
                it[createdAt] = base
            }
            for ((id, name) in listOf(serviceId to "Aggregated", lifecycleServiceId to "Lifecycle")) {
                Services.insert {
                    it[Services.id] = id
                    it[Services.projectId] = this@StepAggregationTest.projectId
                    it[Services.name] = name
                    it[createdAt] = base
                }
            }

            // Every run is attributed to an agent, as a dispatched run is. The
            // per-service rollup beside this one upserts on an index that
            // includes the agent, and Postgres treats NULLs there as distinct.
            agentId = ProbeAgents.insert {
                it[slug] = "step-agg-agent"
                it[label] = "Step Aggregation Agent"
                it[agentUri] = "https://step-agg-agent.example.test:8443"
                it[publicKey] = "x"
                it[lastPing] = base
                it[lastStatus] = "success"
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = 0
                it[createdAt] = base
            }[ProbeAgents.id]

            // Hour 10 of the first day: one run, four calls.
            val firstRun = result(serviceId, base.plus(10, ChronoUnit.HOURS), "success")
            // Two healthy endpoints, one of them answering 503.
            step(firstRun, 1, "https://api.example.com/a", KEY_A, 200, 100, 10, 20, 30, 40, 50, 1000)
            step(firstRun, 2, "https://api.example.com/b/7", KEY_B, 503, 200, 11, 21, 31, 41, 51, 2000)
            // The same endpoint, this time with no response at all: a DNS or
            // connect failure, which carries neither a status code nor timings.
            step(firstRun, 3, "https://api.example.com/b/9", KEY_B, null, null, null, null, null, null, null, null)
            // A step from before endpoint keys existed — named from its URL.
            step(firstRun, 4, "https://api.example.com/legacy/42?trace=1", null, 200, 70, 1, 2, 3, 4, 5, null)

            // Hour 11 of the first day: one more call to the same endpoint.
            val secondRun = result(serviceId, base.plus(11, ChronoUnit.HOURS), "success")
            step(secondRun, 1, "https://api.example.com/a", KEY_A, 200, 300, 30, 30, 30, 30, 30, 3000)

            // The next day, so the daily rollup has two buckets to build.
            val thirdRun = result(serviceId, base.plus(34, ChronoUnit.HOURS), "success")
            step(thirdRun, 1, "https://api.example.com/a", KEY_A, 200, 400, 40, 40, 40, 40, 40, 4000)

            // A tick that never ran. It is history, not a call that was made,
            // and it must not reach an aggregate.
            val shed = result(serviceId, base.plus(11, ChronoUnit.HOURS), "skipped")
            step(shed, 1, "https://api.example.com/a", KEY_A, null, null, null, null, null, null, null, null)
        }
    }

    // ── The parity proof ────────────────────────────────────────────────

    @Test
    fun `the SQL and the Kotlin spelling of the fallback key agree`() {
        // One rule, two languages, because the read path needs it in Kotlin and
        // the aggregation cannot afford to leave the database to apply it. The
        // corpus is every shape the unit tests pin, plus the ones a POSIX regex
        // and a Java one are most likely to disagree about.
        val corpus = listOf(
            "https://api.example.com/orders",
            "https://api.example.com/orders/42",
            "https://api.example.com/orders/42?page=2",
            "https://api.example.com/orders/42#top",
            "https://api.example.com/orders/42/items/7",
            "https://api.example.com/u/2f1c9b0e-1f2a-4c3d-9e8f-0a1b2c3d4e5f",
            "https://api.example.com/b/deadbeefdeadbeef",
            "https://api.example.com/b/deadbeefdeadbee",
            "https://api.example.com/t/abcdefghijklmnopqrstuvwx",
            "https://api.example.com/t/abcdefghijklmnopqrstuvw",
            "https://api.example.com/t/a-b_c-d_e-f_g-h_i-j_k-l",
            "https://api.example.com/v1/orders/",
            "https://API.EXAMPLE.com/Orders/42",
            "https://abcdefghijklmnopqrstuvwxyz/x",
            "https://deadbeefdeadbeef",
            "https://api.example.com",
            "https://api.example.com/",
            "https://api.example.com//double//slash",
            "https://api.example.com/12ab/1234",
            "https://api.example.com/0/00/000",
            "https://api.example.com/caf%C3%A9/42",
            "https://api.example.com/商品/1",
            "https://user:pass@api.example.com/42",
            "https://api.example.com:8443/42",
            "http://127.0.0.1:8080/42",
            "/relative/42",
            "relative/42",
            "42",
            "42/relative",
            "",
            "   ",
            "   https://api.example.com/42   ",
            "?only-a-query",
            "#only-a-fragment",
            "https://api.example.com/" + "a".repeat(400),
            "https://api.example.com/" + "1".repeat(400),
            "https://api.example.com/a{b}c",
            "https://api.example.com/back\\slash/42",
            "https://api.example.com/plus+sign/42",
            "https://api.example.com/dot.segment/42",
        )

        val values = corpus.joinToString(",") { "('${it.replace("'", "''")}')" }
        val sql = """
            SELECT u, ${EndpointKeySql.fallbackKey("u")} AS k
            FROM (VALUES $values) AS t(u)
        """.trimIndent()

        val fromSql = linkedMapOf<String, String>()
        transaction {
            TransactionManager.current().exec(sql) { rs ->
                while (rs.next()) fromSql[rs.getString("u")] = rs.getString("k")
            }
        }

        assertEquals(corpus.size, fromSql.size, "every URL in the corpus should come back exactly once")
        for (url in corpus) {
            assertEquals(
                EndpointKeys.fallbackKey(url),
                fromSql[url],
                "the two spellings of the fallback key disagree about <$url>",
            )
        }
    }

    // ── Hourly ──────────────────────────────────────────────────────────

    @Test
    fun `the hourly run files every call under its endpoint and its status code`() {
        runHourly()

        val hourly = rows("hourly", serviceId)
        assertEquals(5, hourly.size, "four endpoint/code pairs in the first hour, one in the second")

        val firstHour = hourly.filter { it.bucketStart == truncated(base.plus(10, ChronoUnit.HOURS), "hour") }
        assertEquals(4, firstHour.size)

        val a = firstHour.single { it.key == KEY_A && it.code == 200 }
        assertEquals(1L, a.calls)
        assertEquals(1L, a.timed)
        assertEquals(100L, a.sumResponseMs)
        assertEquals(10L, a.sumDnsMs)
        assertEquals(20L, a.sumConnectMs)
        assertEquals(30L, a.sumTlsMs)
        assertEquals(40L, a.sumTtfbMs)
        assertEquals(50L, a.sumTransferMs)
        assertEquals(1000L, a.sumSizeBytes)
        assertEquals(1L, a.sized)

        val failing = firstHour.single { it.key == KEY_B && it.code == 503 }
        assertEquals(1L, failing.calls)
        assertEquals(200L, failing.sumResponseMs)

        // The call that never got a response: counted, timed by nothing.
        val noResponse = firstHour.single { it.key == KEY_B && it.code == 0 }
        assertEquals(1L, noResponse.calls)
        assertEquals(0L, noResponse.timed)
        assertEquals(0L, noResponse.sumResponseMs)
        assertEquals(0L, noResponse.sized)

        // The key-less step, named from its resolved URL with the query gone
        // and the identifier masked.
        val legacy = firstHour.single { it.key == KEY_LEGACY }
        assertEquals(200, legacy.code)
        assertEquals(1L, legacy.calls)
        assertEquals(70L, legacy.sumResponseMs)

        val secondHour = hourly.single { it.bucketStart == truncated(base.plus(11, ChronoUnit.HOURS), "hour") }
        assertEquals(KEY_A, secondHour.key)
        assertEquals(1L, secondHour.calls, "the shed tick in the same hour must not be counted")
        assertEquals(300L, secondHour.sumResponseMs)
    }

    @Test
    fun `re-running the same window recomputes rather than accumulates`() {
        runHourly()
        val first = rows("hourly", serviceId).sortedBy { it.key + it.code }
        runHourly()
        val second = rows("hourly", serviceId).sortedBy { it.key + it.code }

        assertEquals(first.size, second.size)
        assertEquals(first.map { it.calls }, second.map { it.calls })
        assertEquals(first.map { it.sumResponseMs }, second.map { it.sumResponseMs })
        assertEquals(first.map { it.timed }, second.map { it.timed })
    }

    // ── Daily ───────────────────────────────────────────────────────────

    @Test
    fun `the daily run rolls the same calls up per day`() {
        runDaily()

        val daily = rows("daily", serviceId)
        assertEquals(5, daily.size, "four endpoint/code pairs on the first day, one on the second")

        val firstDay = truncated(base.plus(10, ChronoUnit.HOURS), "day")
        val a = daily.single { it.bucketStart == firstDay && it.key == KEY_A && it.code == 200 }
        // Both of that day's hours, in one bucket.
        assertEquals(2L, a.calls)
        assertEquals(400L, a.sumResponseMs)
        assertEquals(4000L, a.sumSizeBytes)

        val secondDay = truncated(base.plus(34, ChronoUnit.HOURS), "day")
        val next = daily.single { it.bucketStart == secondDay }
        assertEquals(KEY_A, next.key)
        assertEquals(1L, next.calls)
        assertEquals(400L, next.sumResponseMs)

        // Idempotent for the same reason the hourly run is.
        runDaily()
        assertEquals(5, rows("daily", serviceId).size)
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    @Test
    fun `retention drops hourly endpoint rows past the window and keeps the daily ones`() {
        transaction {
            ProbeStepAggregates.deleteWhere { ProbeStepAggregates.serviceId eq lifecycleServiceId }
            insertAggregate(lifecycleServiceId, "hourly", Instant.now().minus(30, ChronoUnit.DAYS))
            insertAggregate(lifecycleServiceId, "hourly", Instant.now().minus(1, ChronoUnit.HOURS))
            insertAggregate(lifecycleServiceId, "daily", Instant.now().minus(30, ChronoUnit.DAYS))
        }

        runBlocking { AggregateRetentionJob(hourlyRetentionDays = 7).execute() }

        val remaining = rows("hourly", lifecycleServiceId) + rows("daily", lifecycleServiceId)
        assertEquals(2, remaining.size)
        assertTrue(remaining.none { it.bucketType == "hourly" && it.bucketStart.isBefore(Instant.now().minus(7, ChronoUnit.DAYS)) })
        assertEquals(1, rows("daily", lifecycleServiceId).size, "a daily bucket outlives the hours that built it")
    }

    @Test
    fun `retention that is switched off keeps every endpoint row`() {
        transaction {
            ProbeStepAggregates.deleteWhere { ProbeStepAggregates.serviceId eq lifecycleServiceId }
            insertAggregate(lifecycleServiceId, "hourly", Instant.now().minus(400, ChronoUnit.DAYS))
        }

        runBlocking { AggregateRetentionJob(hourlyRetentionDays = -1).execute() }

        assertEquals(1, rows("hourly", lifecycleServiceId).size)
    }

    @Test
    fun `purging a service takes its endpoint rows with it`() {
        val purgedService = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = purgedService
                it[Services.projectId] = this@StepAggregationTest.projectId
                it[name] = "Purged ${purgedService.toString().take(8)}"
                it[createdAt] = base
            }
            insertAggregate(purgedService, "hourly", Instant.now().minus(1, ChronoUnit.HOURS))
            insertAggregate(purgedService, "daily", Instant.now().minus(1, ChronoUnit.DAYS))
            Services.update({ Services.id eq purgedService }) {
                it[deleted] = true
                it[deletedAt] = Instant.now().minus(2, ChronoUnit.DAYS)
                it[purgeAfter] = Instant.now().minus(1, ChronoUnit.DAYS)
            }
        }

        assertEquals(2, rows(null, purgedService).size)
        runBlocking { PurgeJob().execute() }
        assertEquals(0, rows(null, purgedService).size, "a purged service leaves no endpoint statistics behind")
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

    private class Row(
        val bucketType: String,
        val bucketStart: Instant,
        val key: String,
        val code: Int,
        val calls: Long,
        val timed: Long,
        val sumDnsMs: Long,
        val sumConnectMs: Long,
        val sumTlsMs: Long,
        val sumTtfbMs: Long,
        val sumTransferMs: Long,
        val sumResponseMs: Long,
        val sumSizeBytes: Long,
        val sized: Long,
    )

    /** Every aggregate row of a service, optionally of one granularity. */
    private fun rows(bucketType: String?, service: UUID): List<Row> = transaction {
        ProbeStepAggregates.selectAll()
            .where { ProbeStepAggregates.serviceId eq service }
            .filter { bucketType == null || it[ProbeStepAggregates.bucketType] == bucketType }
            .map {
                Row(
                    bucketType = it[ProbeStepAggregates.bucketType],
                    bucketStart = it[ProbeStepAggregates.bucketStart],
                    key = it[ProbeStepAggregates.endpointKey],
                    code = it[ProbeStepAggregates.statusCode].toInt(),
                    calls = it[ProbeStepAggregates.callCount].toLong(),
                    timed = it[ProbeStepAggregates.timedCount].toLong(),
                    sumDnsMs = it[ProbeStepAggregates.sumDnsMs],
                    sumConnectMs = it[ProbeStepAggregates.sumConnectMs],
                    sumTlsMs = it[ProbeStepAggregates.sumTlsMs],
                    sumTtfbMs = it[ProbeStepAggregates.sumTtfbMs],
                    sumTransferMs = it[ProbeStepAggregates.sumTransferMs],
                    sumResponseMs = it[ProbeStepAggregates.sumResponseMs],
                    sumSizeBytes = it[ProbeStepAggregates.sumSizeBytes],
                    sized = it[ProbeStepAggregates.sizedCount].toLong(),
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

    private fun result(service: UUID, at: Instant, status: String): UUID {
        val id = UUID.randomUUID()
        ProbeResults.insert {
            it[ProbeResults.id] = id
            it[serviceId] = service
            it[probeAgentId] = agentId
            it[startedAt] = at
            it[ProbeResults.status] = status
            it[runDurationMs] = 10
            it[rawResult] = JsonObject(emptyMap())
            it[projectId] = this@StepAggregationTest.projectId
            it[workspaceId] = this@StepAggregationTest.workspaceId
            it[organizationId] = orgId
        }
        return id
    }

    @Suppress("LongParameterList")
    private fun step(
        result: UUID,
        num: Int,
        url: String,
        key: String?,
        status: Int?,
        responseMs: Int?,
        dns: Int?,
        connect: Int?,
        tls: Int?,
        ttfb: Int?,
        transfer: Int?,
        size: Int?,
    ) {
        ProbeSteps.insert {
            it[id] = UUID.randomUUID()
            it[probeResultId] = result
            it[stepNum] = num.toShort()
            it[requestUrl] = url
            it[endpointKey] = key
            it[statusCode] = status?.toShort()
            it[responseTimeMs] = responseMs
            it[dnsMs] = dns
            it[connectMs] = connect
            it[tlsMs] = tls
            it[ttfbMs] = ttfb
            it[transferMs] = transfer
            it[responseSizeBytes] = size
            it[createdAt] = base
        }
    }

    private fun insertAggregate(service: UUID, bucketType: String, bucketStart: Instant) {
        ProbeStepAggregates.insert {
            it[id] = UUID.randomUUID()
            it[serviceId] = service
            it[ProbeStepAggregates.bucketStart] = bucketStart
            it[ProbeStepAggregates.bucketType] = bucketType
            it[endpointKey] = KEY_A
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
    }
}
