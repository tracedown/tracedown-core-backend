package dev.tracedown.scheduler

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.*
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.crypto.SchedulerCertService
import dev.tracedown.scheduler.dispatch.AgentDispatchService
import dev.tracedown.scheduler.dispatch.AgentSelector
import dev.tracedown.scheduler.dispatch.QueuePolicyManager
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.*
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.lettuce.core.SetArgs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.*
import org.junit.jupiter.api.Assertions.*
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SchedulerIntegrationTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:7-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())

        /**
         * Probe agent container. Uses the pre-built ``test-agent`` image.
         * Build it before running tests:
         *   cd tracedown && docker build -f core/backend/probe-scheduler/src/test/resources/Dockerfile.agent -t test-agent .
         */
        @Container
        @JvmStatic
        val agent = GenericContainer("test-agent")
            .withExposedPorts(8443)
            // host.docker.internal is provided automatically by Docker Desktop but
            // not by Linux Docker; map it to the host gateway so the agent can reach
            // the host-side token server on both.
            .withExtraHost("host.docker.internal", "host-gateway")
            .waitingFor(Wait.forHttp("/health").forPort(8443).forStatusCode(200))
    }

    private lateinit var redisUrl: String
    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher
    private lateinit var agentSelector: AgentSelector

    private var agentId: Long = 0
    private var orgId: UUID = UUID.randomUUID()
    private var workspaceId: UUID = UUID.randomUUID()
    private var projectId: UUID = UUID.randomUUID()
    private var serviceId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        // Flyway migrations
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()

        // Database
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

        // Redis
        redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        val redisConn = RedisFactory.createConnection(redisUrl)
        redisSync = redisConn.sync()

        // Variable crypto
        VariableCrypto.init(AES_KEY)

        // Quartz
        quartzManager = QuartzManager(4)
        quartzManager.start()

        queuePolicy = QueuePolicyManager(redisSync)
        resultPublisher = ResultPublisher(redisSync)
        agentSelector = AgentSelector(redisSync)

        // Seed test data
        seedTestData()
    }

    @AfterAll
    fun teardown() {
        quartzManager.shutdown()
    }

    private fun seedTestData() {
        transaction {
            // Create a user
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "scheduler-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Scheduler Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }

            // Create org
            Organizations.insert {
                it[id] = orgId
                it[name] = "Test Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }

            // Create workspace
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Test Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }

            // Create project
            val wsId = this@SchedulerIntegrationTest.workspaceId
            val projId = this@SchedulerIntegrationTest.projectId
            Projects.insert {
                it[id] = projId
                it[Projects.workspaceId] = wsId
                it[name] = "Test Project"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }

            // Register the agent directly (no mTLS for test)
            val agentUri = "http://${agent.host}:${agent.getMappedPort(8443)}"
            agentId = ProbeAgents.insert {
                it[slug] = "test-agent"
                it[label] = "Test Agent"
                it[ProbeAgents.agentUri] = agentUri
                it[publicKey] = "test-key"
                it[isActive] = true
                it[lastPing] = Instant.now()
                it[lastStatus] = "success"
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = 0
                it[createdAt] = Instant.now()
            } get ProbeAgents.id

            // Create service with a simple probe script
            val svcId = this@SchedulerIntegrationTest.serviceId
            Services.insert {
                it[id] = svcId
                it[Services.projectId] = projId
                it[name] = "Test Service"
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    // ── Redis pub/sub and queue policy ──

    @Test
    @Order(1)
    fun `queue policy acquire and release works`() {
        val result = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ACQUIRED, result.result)
        assertNotNull(result.token)

        // Second acquire should be skipped
        val result2 = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.SKIPPED, result2.result)
        assertNull(result2.token)

        // Release with the owning token
        val hasPending = queuePolicy.release(serviceId, result.token!!)
        assertFalse(hasPending)

        // Can acquire again
        val result3 = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ACQUIRED, result3.result)
        queuePolicy.release(serviceId, result3.token!!)
    }

    @Test
    @Order(1)
    fun `release only deletes a lock this owner still holds`() {
        val acquired = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ACQUIRED, acquired.result)

        // A stale worker releasing with a foreign token must NOT free the lock.
        val stolen = queuePolicy.release(serviceId, "not-the-owning-token")
        assertFalse(stolen)

        // Lock is still held — a fresh acquire is skipped.
        val blocked = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.SKIPPED, blocked.result)

        // The real owner can still release it.
        assertFalse(queuePolicy.release(serviceId, acquired.token!!))
        val free = queuePolicy.tryAcquire(serviceId, "skip", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ACQUIRED, free.result)
        queuePolicy.release(serviceId, free.token!!)
    }

    @Test
    @Order(2)
    fun `queue policy enqueue_once stores pending`() {
        // Acquire lock
        val result1 = queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ACQUIRED, result1.result)

        // Second attempt should enqueue
        val result2 = queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.ENQUEUED, result2.result)

        // Third attempt should skip (already pending)
        val result3 = queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000)
        assertEquals(QueuePolicyManager.AcquireResult.SKIPPED, result3.result)

        // Release should report pending
        val hasPending = queuePolicy.release(serviceId, result1.token!!)
        assertTrue(hasPending)
    }

    @Test
    @Order(3)
    fun `result publisher pushes to Redis queue`() {
        // Clear any existing results
        redisSync.del(ResultPublisher.QUEUE_KEY)

        val rawResult = Json.decodeFromString<JsonObject>("""{"outcome":"success","elapsedMs":100}""")

        resultPublisher.publish(
            jobId = UUID.randomUUID(),
            serviceId = serviceId,
            agentId = agentId,
            projectId = projectId,
            workspaceId = workspaceId,
            organizationId = orgId,
            rawResult = rawResult,
            startedAt = java.time.Instant.now(),
        )

        val length = redisSync.llen(ResultPublisher.QUEUE_KEY)
        assertEquals(1, length)

        val item = redisSync.rpop(ResultPublisher.QUEUE_KEY)
        assertNotNull(item)
        val parsed = Json.decodeFromString<JsonObject>(item!!)
        assertEquals(serviceId.toString(), parsed["serviceId"]?.jsonPrimitive?.content)
        assertEquals("success", parsed["rawResult"]?.let {
            (it as JsonObject)["outcome"]?.jsonPrimitive?.content
        })
    }

    // ── Agent dispatch ──

    @Test
    @Order(4)
    fun `agent dispatch returns probe result`() {
        // Dispatch without mTLS (test agent runs plain HTTP)
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        val agentUri = "http://${agent.host}:${agent.getMappedPort(8443)}"

        kotlinx.coroutines.runBlocking {
            val response = client.post("$agentUri/probe") {
                contentType(ContentType.Application.Json)
                setBody("""{"script":"get(\"https://testbin.tracedown.dev/status/200\").expect(status: 200)","variables":{},"requestTimeoutMs":30000}""")
            }
            val body = response.bodyAsText()
            val result = Json.decodeFromString<JsonObject>(body)

            assertTrue(result.containsKey("outcome"), "Response should contain outcome. Body: $body")
            assertTrue(result.containsKey("calls"), "Response should contain calls")
        }

        client.close()
    }

    // ── Health challenge ──

    @Test
    @Order(5)
    fun `health challenge against agent succeeds`() {
        val challengeId = generateHex(32)
        val token = generateHex(32)

        // Store token in Redis (simulating what HealthChallengeJob does)
        redisSync.set("health:token:$challengeId", token, SetArgs().ex(30))

        // The tokenUrl would normally point to the gateway. For testing,
        // we serve the token via a simple embedded HTTP server.
        val tokenServer = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress(0), 0
        )
        val tokenServerPort = tokenServer.address.port
        tokenServer.createContext("/internal/health/token/$challengeId") { exchange ->
            val json = """{"token":"$token"}"""
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, json.length.toLong())
            exchange.responseBody.use { it.write(json.toByteArray()) }
        }
        tokenServer.start()

        try {
            val agentUri = "http://${agent.host}:${agent.getMappedPort(8443)}"
            // The token URL needs to be reachable from inside the agent container.
            // host.docker.internal resolves to the host machine from Docker containers.
            val tokenUrl = "http://host.docker.internal:$tokenServerPort/internal/health/token/$challengeId"

            val client = HttpClient(CIO) {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true })
                }
            }

            kotlinx.coroutines.runBlocking {
                val response = client.post("$agentUri/health/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"challenge_id":"$challengeId","token_url":"$tokenUrl"}""")
                }

                val body = Json.decodeFromString<JsonObject>(response.bodyAsText())
                assertEquals(challengeId, body["challenge_id"]?.jsonPrimitive?.content)
                assertTrue(body["success"]?.jsonPrimitive?.boolean ?: false, "Challenge should succeed. Body: $body")
                assertEquals(token, body["token"]?.jsonPrimitive?.content, "Token should match")
                assertTrue((body["elapsed_ms"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1) >= 0)
            }

            client.close()
        } finally {
            tokenServer.stop(0)
        }
    }

    // ── Schedule sync ──

    @Test
    @Order(6)
    fun `schedule sync bootstraps services from DB`() {
        val pubSubConn = RedisFactory.createPubSubConnection(redisUrl)
        val syncService = ScheduleSyncService(quartzManager, 300, pubSubConn)
        syncService.bootstrap()

        val scheduledIds = quartzManager.getScheduledServiceIds()
        assertTrue(scheduledIds.contains(serviceId), "Service should be scheduled. Found: $scheduledIds")
    }

    // ── Helpers ──

    private fun generateHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

}
