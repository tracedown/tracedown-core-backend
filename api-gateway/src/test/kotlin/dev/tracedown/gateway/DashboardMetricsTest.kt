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
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.util.HourBuckets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.long
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
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

}
