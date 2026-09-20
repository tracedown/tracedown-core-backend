package dev.tracedown.gateway

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.lettuce.core.RedisClient
import dev.tracedown.common.models.ProbeAggregates
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeStepAggregates
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.util.HourBuckets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.update
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Integration test for dashboard metrics endpoints.
 * Seeds Redis B with metric data and verifies the API returns it correctly.
 */
@Testcontainers
class DashboardMetricsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_metrics_api_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0
        private val client = OkHttpClient()
        private lateinit var redisClient: RedisClient

        private const val PASSWORD = "Down2trace!"
        private const val EMAIL = "admin@tracedown.dev"
        private lateinit var serviceId: UUID
        private lateinit var projectId: UUID
        private lateinit var workspaceId: UUID
        private lateinit var organizationId: UUID

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            serverPort = ServerSocket(0).use { it.localPort }
            redisClient = RedisClient.create(TestRedis.url)

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
                "rateLimit.enabled" to "false",
            ))
            val mergedConfig = overrides.withFallback(ConfigFactory.load())

            val env = applicationEnvironment {
                config = HoconApplicationConfig(mergedConfig)
            }

            server = embeddedServer(Netty, env, configure = {
                connector { port = serverPort }
            })

            server.start(wait = false)
            Thread.sleep(2000)

            // Seed user, org, workspace, project, service
            seedData()
        }

        private fun seedData() {
            // Use the demo user created by singleOrgMode bootstrap
            val token = login()

            val wsBody = post("/api/v1/workspaces", """{"name":"MetricsWs"}""", token)
            val wsId = Json.parseToJsonElement(wsBody).jsonObject["id"]!!.jsonPrimitive.content

            val projBody = post("/api/v1/projects", """{"workspaceId":"$wsId","name":"MetricsProj"}""", token)
            val projId = Json.parseToJsonElement(projBody).jsonObject["id"]!!.jsonPrimitive.content

            workspaceId = UUID.fromString(wsId)
            projectId = UUID.fromString(projId)
            organizationId = transaction {
                Workspaces.selectAll().where { Workspaces.id eq workspaceId }.single()[Workspaces.organizationId]
            }

            val svcBody = post("/api/v1/services", """{"projectId":"$projId","name":"Metrics Svc"}""", token)
            serviceId = UUID.fromString(Json.parseToJsonElement(svcBody).jsonObject["id"]!!.jsonPrimitive.content)
        }

        /** A service of its own, so probe_results seeded for it cannot reach another test. */
        private fun createService(name: String): UUID {
            val body = post("/api/v1/services", """{"projectId":"$projectId","name":"$name"}""", login())
            return UUID.fromString(Json.parseToJsonElement(body).jsonObject["id"]!!.jsonPrimitive.content)
        }

        /** Inserts [count] finished probes into the hour [hourKey] names. */
        private fun seedProbeResults(svcId: UUID, hourKey: String, count: Int, status: String) {
            val startedAt = HourBuckets.start(hourKey).plusSeconds(1800)
            val projId = projectId
            val wsId = workspaceId
            val orgId = organizationId
            transaction {
                repeat(count) {
                    ProbeResults.insert {
                        it[id] = UUID.randomUUID()
                        it[ProbeResults.serviceId] = svcId
                        it[ProbeResults.projectId] = projId
                        it[ProbeResults.workspaceId] = wsId
                        it[ProbeResults.organizationId] = orgId
                        it[ProbeResults.startedAt] = startedAt
                        it[ProbeResults.status] = status
                        it[runDurationMs] = 10
                        it[totalResponseMs] = 100
                        it[rawResult] = JsonObject(emptyMap())
                    }
                }
            }
        }

        private fun hourHash(svcId: UUID, hourKey: String): Map<String, String> =
            redisClient.connect().use { it.sync().hgetall("metrics:svc:$svcId:h:$hourKey") }

        private fun seedHourHash(svcId: UUID, hourKey: String, fields: Map<String, String>) {
            redisClient.connect().use { it.sync().hset("metrics:svc:$svcId:h:$hourKey", fields) }
        }

        /** The assembled-closed-series cache, which would otherwise outlive a test. */
        private fun clearClosedHistoryCache() {
            redisClient.connect().use { conn ->
                val keys = conn.sync().keys("metrics:agg:history:closed:*")
                if (keys.isNotEmpty()) conn.sync().del(*keys.toTypedArray())
            }
        }

        /**
         * One endpoint bucket. [unit] scales the phase sums so an average is
         * readable from the fixture: dns is 1x, connect 2x, tls 3x, ttfb 4x,
         * transfer 5x and the response time 6x the unit, per timed call.
         */
        private fun seedEndpointBucket(
            svcId: UUID,
            bucketAt: Instant,
            key: String,
            code: Int,
            calls: Int,
            timed: Int,
            unit: Long,
            size: Long,
            sized: Int,
        ) {
            ProbeStepAggregates.insert {
                it[id] = UUID.randomUUID()
                it[serviceId] = svcId
                it[bucketStart] = bucketAt
                it[bucketType] = "hourly"
                it[endpointKey] = key
                it[statusCode] = code.toShort()
                it[callCount] = calls
                it[timedCount] = timed
                it[sumDnsMs] = unit * timed
                it[sumConnectMs] = 2 * unit * timed
                it[sumTlsMs] = 3 * unit * timed
                it[sumTtfbMs] = 4 * unit * timed
                it[sumTransferMs] = 5 * unit * timed
                it[sumResponseMs] = 6 * unit * timed
                it[sumSizeBytes] = size
                it[sizedCount] = sized
            }
        }

        /** One finished run of [svcId], for tests that then hang steps off it. */
        private fun seedResult(svcId: UUID, at: Instant, resultStatus: String): UUID {
            val resultId = UUID.randomUUID()
            val projId = projectId
            val wsId = workspaceId
            val orgId = organizationId
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = svcId
                it[ProbeResults.projectId] = projId
                it[ProbeResults.workspaceId] = wsId
                it[ProbeResults.organizationId] = orgId
                it[startedAt] = at
                it[status] = resultStatus
                it[runDurationMs] = 10
                it[rawResult] = JsonObject(emptyMap())
            }
            return resultId
        }

        /** One step carrying the ProbeResult `calls[].assertions` array verbatim, as the ingestor stores it. */
        private fun seedStepWithAssertions(resultId: UUID, num: Int, url: String, key: String, assertions: String) {
            ProbeSteps.insert {
                it[id] = UUID.randomUUID()
                it[probeResultId] = resultId
                it[stepNum] = num.toShort()
                it[requestUrl] = url
                it[endpointKey] = key
                it[statusCode] = 200
                it[responseTimeMs] = 10
                it[assertionResults] = Json.parseToJsonElement(assertions)
                it[createdAt] = Instant.now()
            }
        }

        /** One all-agents hourly rollup row — what the heatmap counts runs from. */
        private fun seedHourlyAggregate(svcId: UUID, bucketAt: Instant, probeCount: Int, errorRate: Float) {
            ProbeAggregates.insert {
                it[id] = UUID.randomUUID()
                it[serviceId] = svcId
                it[probeAgentId] = null
                it[bucketStart] = bucketAt
                it[bucketType] = "hourly"
                it[ProbeAggregates.probeCount] = probeCount
                it[ProbeAggregates.errorRate] = errorRate
                it[uptimePct] = 1f - errorRate
            }
        }

        /** One step of a run, carrying the endpoint key the scheduler named. */
        private fun seedStep(resultId: UUID, num: Int, url: String, key: String) {
            ProbeSteps.insert {
                it[id] = UUID.randomUUID()
                it[probeResultId] = resultId
                it[stepNum] = num.toShort()
                it[requestUrl] = url
                it[endpointKey] = key
                it[statusCode] = 200
                it[responseTimeMs] = 10
                it[createdAt] = Instant.now()
            }
        }

        private fun login(): String {
            val body = post("/api/v1/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""", null)
            return Json.parseToJsonElement(body).jsonObject["token"]!!.jsonPrimitive.content
        }

        private fun post(path: String, json: String, token: String?): String {
            val builder = Request.Builder()
                .url("http://localhost:$serverPort$path")
                .post(json.toRequestBody("application/json".toMediaType()))
            if (token != null) builder.header("Authorization", "Bearer $token")
            val response = client.newCall(builder.build()).execute()
            return response.body!!.string()
        }

        private fun get(path: String, token: String): Pair<Int, String> {
            val request = Request.Builder()
                .url("http://localhost:$serverPort$path")
                .header("Authorization", "Bearer $token")
                .get()
                .build()
            val response = client.newCall(request).execute()
            return response.code to response.body!!.string()
        }

        private fun seedRedisMetrics() {
            redisClient.connect().use { conn ->
                val cmds = conn.sync()
                val counterKey = "metrics:svc:$serviceId:counters"
                val stateKey = "metrics:svc:$serviceId:state"

                cmds.hset(counterKey, mapOf(
                    "probes_total" to "100",
                    "probes_success" to "85",
                    "probes_failure" to "10",
                    "probes_timeout" to "5",
                ))

                cmds.hset(stateKey, mapOf(
                    "last_status" to "success",
                    "last_consecutive" to "12",
                    "last_response_ms" to "142",
                    "last_run_at" to "1746432000",
                ))

                // Seed current hour bucket
                val hourKey = "metrics:svc:$serviceId:h:${DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC).format(Instant.now())}"
                cmds.hset(hourKey, mapOf(
                    "total" to "10",
                    "success" to "9",
                    "failure" to "1",
                    "timeout" to "0",
                    "sum_ms" to "1200",
                ))
            }
        }

        private fun clearRedisMetrics() {
            redisClient.connect().use { conn ->
                val keys = conn.sync().keys("metrics:svc:$serviceId:*")
                if (keys.isNotEmpty()) {
                    conn.sync().del(*keys.toTypedArray())
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            redisClient.shutdown()
        }
    }

    @Test
    fun `metrics returns seeded counters and state`() {
        seedRedisMetrics()
        try {
            val token = login()
            val (status, body) = get("/api/v1/services/$serviceId/metrics", token)

            assertEquals(200, status)

            val json = Json.parseToJsonElement(body).jsonObject
            val counters = json["counters"]!!.jsonObject
            assertEquals(100, counters["probesTotal"]!!.jsonPrimitive.long)
            assertEquals(85, counters["probesSuccess"]!!.jsonPrimitive.long)
            assertEquals(10, counters["probesFailure"]!!.jsonPrimitive.long)
            assertEquals(5, counters["probesTimeout"]!!.jsonPrimitive.long)

            val state = json["state"]!!.jsonObject
            assertEquals("success", state["lastStatus"]!!.jsonPrimitive.content)
            assertEquals(12, state["lastConsecutive"]!!.jsonPrimitive.long)
            assertEquals(142, state["lastResponseMs"]!!.jsonPrimitive.long)
            assertEquals(1746432000, state["lastRunAt"]!!.jsonPrimitive.long)
        } finally {
            clearRedisMetrics()
        }
    }

    @Test
    fun `statistics returns overall trend and per-region breakdown from probe_aggregates`() {
        val token = login()
        val svcId = serviceId // capture: inside the insert lambda `serviceId` would bind the column
        val recent = Instant.now().minusSeconds(1800) // within the 24h hourly window

        transaction {
            // All-agents rollup bucket (probe_agent_id NULL).
            ProbeAggregates.insert {
                it[id] = UUID.randomUUID(); it[serviceId] = svcId
                it[probeAgentId] = null; it[bucketStart] = recent; it[bucketType] = "hourly"
                it[p50Ms] = 100; it[p95Ms] = 200; it[p99Ms] = 300
                it[errorRate] = 0.1f; it[uptimePct] = 0.9f; it[probeCount] = 10
            }
            val agentId = ProbeAgents.insert {
                it[slug] = "agent-stat-${UUID.randomUUID()}"; it[label] = "EU West"
                it[agentUri] = "https://a"; it[publicKey] = ""; it[isActive] = true; it[deleted] = false
                it[lastPing] = Instant.now(); it[lastStatus] = "success"
                it[lastPingDelayMs] = 5; it[lastPongDeltaMs] = 5; it[createdAt] = Instant.now()
            }[ProbeAgents.id]
            // Per-region bucket for that agent.
            ProbeAggregates.insert {
                it[id] = UUID.randomUUID(); it[serviceId] = svcId
                it[probeAgentId] = agentId; it[bucketStart] = recent; it[bucketType] = "hourly"
                it[p50Ms] = 110; it[p95Ms] = 210; it[p99Ms] = 310
                it[errorRate] = 0.2f; it[uptimePct] = 0.8f; it[probeCount] = 5
            }
        }

        val (code, body) = get("/api/v1/services/$serviceId/metrics/statistics?window=24h", token)
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("hourly", json["bucketType"]!!.jsonPrimitive.content)

        val overall = json["overall"]!!.jsonArray
        assertTrue(overall.isNotEmpty(), "overall series present")
        val last = overall.last().jsonObject
        assertEquals(90.0, last["uptimePct"]!!.jsonPrimitive.content.toDouble(), 0.01) // 0.9 fraction ×100
        assertEquals(200, last["p95Ms"]!!.jsonPrimitive.int)

        val regions = json["regions"]!!.jsonArray
        assertEquals(1, regions.size)
        assertEquals("EU West", regions[0].jsonObject["agentLabel"]!!.jsonPrimitive.content)

        // Unknown window is rejected.
        val (badCode, _) = get("/api/v1/services/$serviceId/metrics/statistics?window=nope", token)
        assertEquals(400, badCode)
    }

    @Test
    fun `a service whose runs carry no agent still has an overall trend and no regions`() {
        // What a single-jar install looks like: probes run in-process, nothing
        // is attributed to an agent, so the rollup rows are the only rows there
        // are. The trend must still be served, and the per-region breakdown
        // must come back empty rather than naming a region that does not exist.
        val svcId = createService("Agentless Svc ${UUID.randomUUID().toString().take(8)}")
        val recent = Instant.now().minusSeconds(1800)

        transaction {
            ProbeAggregates.insert {
                it[id] = UUID.randomUUID(); it[serviceId] = svcId
                it[probeAgentId] = null; it[bucketStart] = recent; it[bucketType] = "hourly"
                it[p50Ms] = 120; it[p95Ms] = 240; it[p99Ms] = 360
                it[errorRate] = 0.25f; it[uptimePct] = 0.75f; it[probeCount] = 4
            }
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject

        val overall = json["overall"]!!.jsonArray
        assertEquals(1, overall.size, "the rollup bucket is served even with no agent behind it")
        val bucket = overall.single().jsonObject
        assertEquals(75.0, bucket["uptimePct"]!!.jsonPrimitive.content.toDouble(), 0.01)
        assertEquals(240, bucket["p95Ms"]!!.jsonPrimitive.int)

        assertTrue(json["regions"]!!.jsonArray.isEmpty(), "no agent ran it, so there is no region")
    }

    @Test
    fun `history returns current hour bucket with seeded data`() {
        seedRedisMetrics()
        try {
            val token = login()
            val (status, body) = get("/api/v1/services/$serviceId/metrics/history?hours=1", token)

            assertEquals(200, status)

            val arr = Json.parseToJsonElement(body)
            assertTrue(arr is kotlinx.serialization.json.JsonArray)
            val list = (arr as kotlinx.serialization.json.JsonArray)
            assertEquals(1, list.size)

            val bucket = list[0].jsonObject
            assertEquals(10, bucket["total"]!!.jsonPrimitive.long)
            assertEquals(9, bucket["success"]!!.jsonPrimitive.long)
            assertEquals(1, bucket["failure"]!!.jsonPrimitive.long)
            assertEquals(0, bucket["timeout"]!!.jsonPrimitive.long)
            assertEquals(1200, bucket["sumMs"]!!.jsonPrimitive.long)
            assertNotNull(bucket["hour"]!!.jsonPrimitive.content)
        } finally {
            clearRedisMetrics()
        }
    }

    @Test
    fun `history returns zero-filled buckets for hours without data`() {
        clearRedisMetrics()
        val token = login()
        val (status, body) = get("/api/v1/services/$serviceId/metrics/history?hours=3", token)

        assertEquals(200, status)

        val arr = Json.parseToJsonElement(body)
        assertTrue(arr is kotlinx.serialization.json.JsonArray)
        val list = arr as kotlinx.serialization.json.JsonArray
        // One zero-filled bucket per requested hour, even with no data.
        assertEquals(3, list.size)
        list.forEach { element ->
            val bucket = element.jsonObject
            assertEquals(0, bucket["total"]!!.jsonPrimitive.int)
            assertEquals(0, bucket["success"]!!.jsonPrimitive.int)
            assertEquals(0, bucket["failure"]!!.jsonPrimitive.int)
            assertEquals(0, bucket["timeout"]!!.jsonPrimitive.int)
            assertEquals(0, bucket["sumMs"]!!.jsonPrimitive.int)
            assertNotNull(bucket["hour"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `a closed hour left half-counted by a Redis restart is recomputed and sealed`() {
        clearClosedHistoryCache()
        val svcId = createService("Sealing Svc")
        val closedHour = HourBuckets.key(Instant.now().minusSeconds(3600))

        // The durable truth: 24 probes ran in that hour.
        seedProbeResults(svcId, closedHour, 22, "success")
        seedProbeResults(svcId, closedHour, 2, "failure")

        // What Redis B kept after restarting mid-hour: half the count, no seal,
        // and nothing in the hash to say the difference.
        seedHourHash(svcId, closedHour, mapOf(
            "total" to "12", "success" to "11", "failure" to "1",
            "timeout" to "0", "sum_ms" to "1200", "call_count" to "12",
        ))

        val (status, body) = get("/api/v1/services/$svcId/metrics/history?hours=2", login())
        assertEquals(200, status)

        val list = Json.parseToJsonElement(body).jsonArray
        assertEquals(2, list.size)
        val bucket = list[0].jsonObject
        assertEquals(closedHour, bucket["hour"]!!.jsonPrimitive.content)
        assertEquals(24, bucket["total"]!!.jsonPrimitive.long)
        assertEquals(22, bucket["success"]!!.jsonPrimitive.long)
        assertEquals(2, bucket["failure"]!!.jsonPrimitive.long)
        assertEquals(2400, bucket["sumMs"]!!.jsonPrimitive.long)

        // ...and the hash is now sealed at the durable numbers, so the next
        // read is served from Redis without going near the DB.
        val hash = hourHash(svcId, closedHour)
        assertEquals("1", hash["sealed"])
        assertEquals("24", hash["total"])
        assertEquals("22", hash["success"])
        assertEquals("2", hash["failure"])
    }

    @Test
    fun `a sealed closed hour is served as it stands`() {
        clearClosedHistoryCache()
        val svcId = createService("Sealed Svc")
        val closedHour = HourBuckets.key(Instant.now().minusSeconds(2 * 3600))

        // Sealed values the DB cannot produce — probe_results holds nothing for
        // this service — so a recompute would answer 0 and only the seal can
        // answer 7.
        seedHourHash(svcId, closedHour, mapOf(
            "total" to "7", "success" to "6", "failure" to "1",
            "timeout" to "0", "sum_ms" to "700", "call_count" to "7",
            "sealed" to "1",
        ))

        val (status, body) = get("/api/v1/services/$svcId/metrics/history?hours=3", login())
        assertEquals(200, status)

        val list = Json.parseToJsonElement(body).jsonArray
        assertEquals(3, list.size)
        val bucket = list[0].jsonObject
        assertEquals(closedHour, bucket["hour"]!!.jsonPrimitive.content)
        assertEquals(7, bucket["total"]!!.jsonPrimitive.long)
        assertEquals(6, bucket["success"]!!.jsonPrimitive.long)
        assertEquals(1, bucket["failure"]!!.jsonPrimitive.long)
        assertEquals(700, bucket["sumMs"]!!.jsonPrimitive.long)

        // Untouched: nothing recomputed it, nothing re-sealed it.
        assertEquals("7", hourHash(svcId, closedHour)["total"])
    }


    // ── Per-endpoint statistics ──

    @Test
    fun `statistics name every endpoint with its codes, its phases and the period before`() {
        val svcId = createService("Endpoint Stats Svc")
        // The order endpoints are listed in comes from the service's current
        // script, so a table of them reads alongside the script itself.
        transaction {
            Services.update({ Services.id eq svcId }) {
                it[script] = """
                    get("${'$'}p.baseUrl/orders").expect(status: 200)
                    post("${'$'}p.baseUrl/orders").expect(status: 201)
                """.trimIndent()
            }
        }

        val inWindow = Instant.now().minusSeconds(1800)
        val inPreviousWindow = Instant.now().minusSeconds(25 * 3600L)
        val getOrders = "GET {p.baseUrl}/orders"
        val postOrders = "POST {p.baseUrl}/orders"
        val projId = projectId
        val wsId = workspaceId
        val orgId = organizationId

        transaction {
            // The window itself: one endpoint answering three different ways.
            seedEndpointBucket(svcId, inWindow, getOrders, 200, calls = 10, timed = 10, unit = 10, size = 5000, sized = 10)
            seedEndpointBucket(svcId, inWindow, getOrders, 503, calls = 2, timed = 2, unit = 10, size = 0, sized = 0)
            // A call that never got a response: counted, but timed by nothing.
            seedEndpointBucket(svcId, inWindow, getOrders, 0, calls = 1, timed = 0, unit = 0, size = 0, sized = 0)
            seedEndpointBucket(svcId, inWindow, postOrders, 201, calls = 5, timed = 5, unit = 10, size = 2500, sized = 5)
            // The equal-length window immediately before, for one of the two.
            seedEndpointBucket(svcId, inPreviousWindow, getOrders, 200, calls = 4, timed = 4, unit = 20, size = 0, sized = 0)

            // One recent run, so each endpoint has a resolved URL to show.
            // The ids are captured above: inside the insert lambda the table is
            // the receiver, so the bare names would bind its columns.
            val resultId = UUID.randomUUID()
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = svcId
                it[ProbeResults.projectId] = projId
                it[ProbeResults.workspaceId] = wsId
                it[ProbeResults.organizationId] = orgId
                it[startedAt] = Instant.now().minusSeconds(1200)
                it[status] = "success"
                it[runDurationMs] = 10
                it[rawResult] = JsonObject(emptyMap())
            }
            seedStep(resultId, 1, "https://api.example.com/orders?page=2", getOrders)
            seedStep(resultId, 2, "https://api.example.com/orders", postOrders)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(false, json["endpointsTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            Json.parseToJsonElement(
                """
                [
                  {
                    "key": "GET {p.baseUrl}/orders",
                    "method": "GET",
                    "template": "{p.baseUrl}/orders",
                    "exampleUrl": "https://api.example.com/orders",
                    "calls": 13,
                    "codes": [
                      { "code": 200, "count": 10 },
                      { "code": 503, "count": 2 },
                      { "code": 0, "count": 1 }
                    ],
                    "phases": {
                      "dnsMs": 10, "connectMs": 20, "tlsMs": 30,
                      "ttfbMs": 40, "transferMs": 50, "responseMs": 60
                    },
                    "previousPhases": {
                      "dnsMs": 20, "connectMs": 40, "tlsMs": 60,
                      "ttfbMs": 80, "transferMs": 100, "responseMs": 120
                    },
                    "avgSizeBytes": 500
                  },
                  {
                    "key": "POST {p.baseUrl}/orders",
                    "method": "POST",
                    "template": "{p.baseUrl}/orders",
                    "exampleUrl": "https://api.example.com/orders",
                    "calls": 5,
                    "codes": [ { "code": 201, "count": 5 } ],
                    "phases": {
                      "dnsMs": 10, "connectMs": 20, "tlsMs": 30,
                      "ttfbMs": 40, "transferMs": 50, "responseMs": 60
                    },
                    "previousPhases": null,
                    "avgSizeBytes": 500
                  }
                ]
                """.trimIndent(),
            ),
            json["endpoints"],
        )
    }

    @Test
    fun `a service with no endpoint history says so rather than omitting the field`() {
        val svcId = createService("No Endpoint Stats Svc")
        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(0, json["endpoints"]!!.jsonArray.size)
        assertEquals(false, json["endpointsTruncated"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `past the cap the busiest endpoints are kept and the response says it truncated`() {
        val svcId = createService("Many Endpoint Svc")
        val inWindow = Instant.now().minusSeconds(1800)
        transaction {
            // No script to order by, so the busiest come first.
            repeat(25) { i ->
                seedEndpointBucket(
                    svcId, inWindow, "GET https://api.example.com/e$i", 200,
                    calls = i + 1, timed = i + 1, unit = 1, size = 0, sized = 0,
                )
            }
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        val endpoints = json["endpoints"]!!.jsonArray

        assertEquals(20, endpoints.size)
        assertEquals(true, json["endpointsTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals("GET https://api.example.com/e24", endpoints[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(25, endpoints[0].jsonObject["calls"]!!.jsonPrimitive.long)
        assertEquals("GET https://api.example.com/e5", endpoints.last().jsonObject["key"]!!.jsonPrimitive.content)
    }

    // ── Per-endpoint time series ──

    @Test
    fun `the endpoint series carries the same buckets as the service trend`() {
        val svcId = createService("Series Svc")
        val newest = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600)
        val older = newest.minusSeconds(3600)
        val getOrders = "GET {p.baseUrl}/orders"
        val postOrders = "POST {p.baseUrl}/orders"

        transaction {
            // Two codes in one bucket for one endpoint: the series sums them
            // away, so its average must come out of the combined sums.
            seedEndpointBucket(svcId, older, getOrders, 200, calls = 8, timed = 8, unit = 10, size = 4000, sized = 8)
            seedEndpointBucket(svcId, older, getOrders, 503, calls = 2, timed = 2, unit = 10, size = 0, sized = 0)
            seedEndpointBucket(svcId, newest, getOrders, 200, calls = 4, timed = 4, unit = 20, size = 4000, sized = 4)
            // Present in the newest bucket only, and never timed or sized.
            seedEndpointBucket(svcId, newest, postOrders, 0, calls = 3, timed = 0, unit = 0, size = 0, sized = 0)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/endpoint-series?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("24h", json["window"]!!.jsonPrimitive.content)
        assertEquals("hourly", json["bucketType"]!!.jsonPrimitive.content)
        assertEquals(false, json["endpointsTruncated"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            listOf(older.toString(), newest.toString()),
            json["buckets"]!!.jsonArray.map { it.jsonPrimitive.content },
        )

        // The service-wide line is every endpoint of a bucket, and its average
        // is taken from the combined sums — not from the two endpoints' own
        // averages, which would weight three untimed calls like ten timed ones.
        assertEquals(
            Json.parseToJsonElement(
                """
                [
                  {
                    "bucketStart": "$older",
                    "calls": 10,
                    "phases": {
                      "dnsMs": 10, "connectMs": 20, "tlsMs": 30,
                      "ttfbMs": 40, "transferMs": 50, "responseMs": 60
                    },
                    "avgSizeBytes": 500
                  },
                  {
                    "bucketStart": "$newest",
                    "calls": 7,
                    "phases": {
                      "dnsMs": 20, "connectMs": 40, "tlsMs": 60,
                      "ttfbMs": 80, "transferMs": 100, "responseMs": 120
                    },
                    "avgSizeBytes": 1000
                  }
                ]
                """.trimIndent(),
            ),
            json["all"],
        )

        val endpoints = json["endpoints"]!!.jsonArray
        assertEquals(listOf(getOrders, postOrders), endpoints.map { it.jsonObject["key"]!!.jsonPrimitive.content })
        assertEquals("GET", endpoints[0].jsonObject["method"]!!.jsonPrimitive.content)
        assertEquals("{p.baseUrl}/orders", endpoints[0].jsonObject["template"]!!.jsonPrimitive.content)

        // Sparse: the endpoint that was only called in the newest bucket has
        // one point, not an invented zero for the bucket before it.
        val post = endpoints[1].jsonObject["points"]!!.jsonArray
        assertEquals(1, post.size)
        assertEquals(newest.toString(), post[0].jsonObject["bucketStart"]!!.jsonPrimitive.content)
        assertEquals(3, post[0].jsonObject["calls"]!!.jsonPrimitive.long)
        // Nothing to divide by is null, which is not the same as 0 ms.
        assertEquals(JsonNull, post[0].jsonObject["phases"])
        assertEquals(JsonNull, post[0].jsonObject["avgSizeBytes"])
    }

    @Test
    fun `a service with no endpoint history returns an empty series rather than nothing`() {
        val svcId = createService("No Series Svc")
        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/endpoint-series?window=7d", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals("hourly", json["bucketType"]!!.jsonPrimitive.content)
        assertEquals(0, json["buckets"]!!.jsonArray.size)
        assertEquals(0, json["all"]!!.jsonArray.size)
        assertEquals(0, json["endpoints"]!!.jsonArray.size)

        val (badCode, _) = get("/api/v1/services/$svcId/metrics/statistics/endpoint-series?window=nope", login())
        assertEquals(400, badCode)
    }

    // ── Most-failing assertions ──

    @Test
    fun `assertions are ranked by failures under the identity the script declared`() {
        val svcId = createService("Assertion Svc")
        val key = "GET {p.baseUrl}/orders"

        // Three runs: the status expectation holds every time, the body one
        // fails twice, and an assert condition fails once. The observed values
        // differ on every run and must not split a single assertion in three.
        transaction {
            for ((index, outcomes) in listOf(
                listOf("passed", "failed", "passed"),
                listOf("passed", "failed", "failed"),
                listOf("passed", "passed", "passed"),
            ).withIndex()) {
                val resultId = seedResult(svcId, Instant.now().minusSeconds(600L * (index + 1)), "failure")
                seedStepWithAssertions(
                    resultId, 1, "https://api.example.com/orders", key,
                    """
                    [
                      { "method": "expect", "scope": "status", "op": "eq", "expected": 200,
                        "actual": 200, "outcome": "${outcomes[0]}" },
                      { "method": "expect", "scope": "body.ok", "op": "eq", "expected": true,
                        "actual": $index, "outcome": "${outcomes[1]}" },
                      { "method": "assert", "kind": "expect", "index": 0,
                        "expression": "${'$'}${'$'}a eq ${'$'}${'$'}b", "actualLhs": $index, "actualRhs": 7,
                        "outcome": "${outcomes[2]}" }
                    ]
                    """.trimIndent(),
                )
            }
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/assertions?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("24h", json["window"]!!.jsonPrimitive.content)
        assertEquals(false, json["truncated"]!!.jsonPrimitive.content.toBoolean())
        // An assertion that never failed is not in a ranking of failures.
        assertEquals(
            Json.parseToJsonElement(
                """
                [
                  {
                    "endpointKey": "$key",
                    "method": "GET",
                    "template": "{p.baseUrl}/orders",
                    "assertionMethod": "expect",
                    "scope": "body.ok",
                    "op": "eq",
                    "expected": "true",
                    "kind": null,
                    "expression": null,
                    "failures": 2,
                    "evaluations": 3,
                    "failureRatePct": 66.67,
                    "lastFailedAt": ${'"'}${'"'}
                  },
                  {
                    "endpointKey": "$key",
                    "method": "GET",
                    "template": "{p.baseUrl}/orders",
                    "assertionMethod": "assert",
                    "scope": null,
                    "op": null,
                    "expected": null,
                    "kind": "expect",
                    "expression": "${'$'}${'$'}a eq ${'$'}${'$'}b",
                    "failures": 1,
                    "evaluations": 3,
                    "failureRatePct": 33.33,
                    "lastFailedAt": ${'"'}${'"'}
                  }
                ]
                """.trimIndent(),
            ),
            withoutLastFailedAt(json["assertions"]!!.jsonArray),
        )

        // The timestamp itself is the newest run that failed — the second one,
        // which is 20 minutes old, not the third, which passed.
        val newestFailure = json["assertions"]!!.jsonArray[0].jsonObject["lastFailedAt"]!!.jsonPrimitive.content
        assertTrue(Instant.parse(newestFailure).isAfter(Instant.now().minusSeconds(1500)))
    }

    @Test
    fun `a service that never failed an assertion ranks nothing`() {
        val svcId = createService("No Assertion Svc")
        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/assertions?window=24h", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(0, json["assertions"]!!.jsonArray.size)
        assertEquals(false, json["truncated"]!!.jsonPrimitive.content.toBoolean())
        // Nothing was read, so the window it reports is the one it was asked for.
        assertTrue(Instant.parse(json["since"]!!.jsonPrimitive.content).isBefore(Instant.now().minusSeconds(23 * 3600)))
    }

    @Test
    fun `a step whose assertions are missing or malformed does not fail the read`() {
        val svcId = createService("Odd Assertion Svc")
        transaction {
            val resultId = seedResult(svcId, Instant.now().minusSeconds(300), "failure")
            seedStep(resultId, 1, "https://api.example.com/x", "GET https://api.example.com/x")
            seedStepWithAssertions(resultId, 2, "https://api.example.com/y", "GET https://api.example.com/y", "{}")
            seedStepWithAssertions(
                resultId, 3, "https://api.example.com/z", "GET https://api.example.com/z",
                """[ { "method": "expect", "scope": "status", "op": "eq", "expected": 200, "outcome": "failed" } ]""",
            )
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/assertions?window=24h", login())
        assertEquals(200, code)
        val assertions = Json.parseToJsonElement(body).jsonObject["assertions"]!!.jsonArray
        assertEquals(1, assertions.size)
        assertEquals("GET https://api.example.com/z", assertions[0].jsonObject["endpointKey"]!!.jsonPrimitive.content)
    }

    // ── Failure heatmap ──

    @Test
    fun `the heatmap files each hour under its UTC weekday and hour`() {
        val svcId = createService("Heatmap Svc")
        val bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minus(2, ChronoUnit.DAYS)
        val earlier = bucket.minus(1, ChronoUnit.DAYS)
        val utc = bucket.atZone(ZoneOffset.UTC)
        val earlierUtc = earlier.atZone(ZoneOffset.UTC)

        transaction {
            seedHourlyAggregate(svcId, bucket, probeCount = 40, errorRate = 0.25f)
            seedHourlyAggregate(svcId, earlier, probeCount = 10, errorRate = 0f)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/failure-heatmap?days=30", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(30, json["days"]!!.jsonPrimitive.int)
        assertEquals("UTC", json["timezone"]!!.jsonPrimitive.content)
        assertEquals(50, json["totalRuns"]!!.jsonPrimitive.long)
        assertEquals(10, json["totalFailedRuns"]!!.jsonPrimitive.long)
        // "Based on the last N days" is read off the data, not off the request.
        assertEquals(earlier.toString(), json["coveredFrom"]!!.jsonPrimitive.content)
        assertEquals(bucket.toString(), json["coveredTo"]!!.jsonPrimitive.content)

        val cells = json["cells"]!!.jsonArray.map { it.jsonObject }
        assertEquals(2, cells.size)
        val cell = cells.single {
            it["weekday"]!!.jsonPrimitive.int == utc.dayOfWeek.value && it["hour"]!!.jsonPrimitive.int == utc.hour
        }
        assertEquals(40, cell["runs"]!!.jsonPrimitive.long)
        assertEquals(10, cell["failedRuns"]!!.jsonPrimitive.long)
        // An hour with runs and no failures is a cell, not an absence.
        val quiet = cells.single {
            it["weekday"]!!.jsonPrimitive.int == earlierUtc.dayOfWeek.value && it["hour"]!!.jsonPrimitive.int == earlierUtc.hour
        }
        assertEquals(10, quiet["runs"]!!.jsonPrimitive.long)
        assertEquals(0, quiet["failedRuns"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a heatmap of no history is empty and says how far it looked`() {
        val svcId = createService("No Heatmap Svc")
        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics/failure-heatmap", login())
        assertEquals(200, code)
        val json = Json.parseToJsonElement(body).jsonObject
        assertEquals(90, json["days"]!!.jsonPrimitive.int)
        assertEquals(0, json["cells"]!!.jsonArray.size)
        assertEquals(JsonNull, json["coveredFrom"])
        assertEquals(JsonNull, json["coveredTo"])

        assertEquals(400, get("/api/v1/services/$svcId/metrics/statistics/failure-heatmap?days=0", login()).first)
        assertEquals(400, get("/api/v1/services/$svcId/metrics/statistics/failure-heatmap?days=366", login()).first)
    }

    // ── Legacy `*` endpoints folded into the method that names them ──

    @Test
    fun `a legacy endpoint row is folded into the one method that shares its template`() {
        val svcId = createService("Fold Svc")
        val bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600)
        val url = "https://api.example.com/x"

        transaction {
            seedEndpointBucket(svcId, bucket, "GET $url", 200, calls = 6, timed = 6, unit = 10, size = 600, sized = 6)
            // The same calls, from before the scheduler named them: method
            // unknown, so the aggregation derived the key from the URL.
            seedEndpointBucket(svcId, bucket, "* $url", 200, calls = 4, timed = 4, unit = 10, size = 400, sized = 4)
            seedEndpointBucket(svcId, bucket, "* $url", 500, calls = 2, timed = 2, unit = 10, size = 0, sized = 0)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val endpoints = Json.parseToJsonElement(body).jsonObject["endpoints"]!!.jsonArray

        // One endpoint, not two, and its totals are the sum of both halves.
        assertEquals(1, endpoints.size)
        val only = endpoints[0].jsonObject
        assertEquals("GET $url", only["key"]!!.jsonPrimitive.content)
        assertEquals(12, only["calls"]!!.jsonPrimitive.long)
        assertEquals(100, only["avgSizeBytes"]!!.jsonPrimitive.long)
        assertEquals(
            Json.parseToJsonElement("""[ { "code": 200, "count": 10 }, { "code": 500, "count": 2 } ]"""),
            only["codes"],
        )

        // And the series tells the same story, on the same key.
        val (seriesCode, seriesBody) = get("/api/v1/services/$svcId/metrics/statistics/endpoint-series?window=24h", login())
        assertEquals(200, seriesCode)
        val series = Json.parseToJsonElement(seriesBody).jsonObject["endpoints"]!!.jsonArray
        assertEquals(1, series.size)
        assertEquals("GET $url", series[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(12, series[0].jsonObject["points"]!!.jsonArray[0].jsonObject["calls"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a legacy endpoint row two methods could claim is left standing`() {
        val svcId = createService("Ambiguous Fold Svc")
        val bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600)
        val url = "https://api.example.com/x"

        transaction {
            seedEndpointBucket(svcId, bucket, "GET $url", 200, calls = 6, timed = 6, unit = 10, size = 0, sized = 0)
            seedEndpointBucket(svcId, bucket, "DELETE $url", 204, calls = 5, timed = 5, unit = 10, size = 0, sized = 0)
            seedEndpointBucket(svcId, bucket, "* $url", 200, calls = 4, timed = 4, unit = 10, size = 0, sized = 0)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val endpoints = Json.parseToJsonElement(body).jsonObject["endpoints"]!!.jsonArray

        // Three rows: the legacy calls belong to one of the two methods and
        // there is nothing left to say which, so they are not given to either.
        assertEquals(
            listOf("GET $url", "DELETE $url", "* $url"),
            endpoints.map { it.jsonObject["key"]!!.jsonPrimitive.content },
        )
        assertEquals(4, endpoints[2].jsonObject["calls"]!!.jsonPrimitive.long)
    }

    @Test
    fun `a legacy endpoint row nothing else names keeps its own name`() {
        val svcId = createService("Lonely Fold Svc")
        val bucket = Instant.now().truncatedTo(ChronoUnit.HOURS).minusSeconds(3600)

        transaction {
            seedEndpointBucket(svcId, bucket, "GET https://api.example.com/a", 200, calls = 3, timed = 3, unit = 10, size = 0, sized = 0)
            seedEndpointBucket(svcId, bucket, "* https://api.example.com/b", 200, calls = 2, timed = 2, unit = 10, size = 0, sized = 0)
        }

        val (code, body) = get("/api/v1/services/$svcId/metrics/statistics?window=24h", login())
        assertEquals(200, code)
        val endpoints = Json.parseToJsonElement(body).jsonObject["endpoints"]!!.jsonArray
        assertEquals(
            listOf("GET https://api.example.com/a", "* https://api.example.com/b"),
            endpoints.map { it.jsonObject["key"]!!.jsonPrimitive.content },
        )
    }

    /** `lastFailedAt` is a wall clock the fixture cannot predict; blank it out. */
    private fun withoutLastFailedAt(assertions: JsonArray): JsonElement = JsonArray(
        assertions.map { entry ->
            JsonObject(entry.jsonObject.mapValues { (name, value) ->
                if (name == "lastFailedAt") JsonPrimitive("") else value
            })
        },
    )

}
