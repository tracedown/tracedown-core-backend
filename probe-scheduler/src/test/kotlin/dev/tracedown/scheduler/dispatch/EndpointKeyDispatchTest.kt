package dev.tracedown.scheduler.dispatch

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.QuartzManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * A dispatched run carries the endpoint each of its calls belongs to.
 *
 * Named here and nowhere else, because only the dispatcher holds the script a
 * given run was actually handed: someone editing the script while a probe is
 * in flight would otherwise have the keys describe calls that never ran.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EndpointKeyDispatchTest {

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
        val redis = GenericContainer("redis:8-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
    }

    /** Answers every dispatch with a fixed two-call result. */
    private class FixedResultBackend : ProbeExecutionBackend {
        override suspend fun execute(
            request: ProbeExecutionBackend.Request,
        ): List<ProbeExecutionBackend.Execution> = listOf(
            ProbeExecutionBackend.Execution(agentId = null, result = RESULT, egressBytes = 0L),
        )

        companion object {
            val RESULT: JsonObject = buildJsonObject {
                put("outcome", "success")
                put("elapsedMs", 12)
                put(
                    "calls",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("request", buildJsonObject { put("url", "https://api.example.com/a"); put("method", "GET") })
                                put("response", buildJsonObject { put("status", 200); put("responseTimeMs", 5) })
                            },
                        )
                        add(
                            buildJsonObject {
                                put("request", buildJsonObject { put("url", "https://api.example.com/b/7"); put("method", "GET") })
                                put("response", buildJsonObject { put("status", 200); put("responseTimeMs", 7) })
                            },
                        )
                    },
                )
            }
        }
    }

    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

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
        redisSync = RedisFactory.createConnection("redis://${redis.host}:${redis.getMappedPort(6379)}").sync()
        VariableCrypto.init(AES_KEY)

        quartzManager = QuartzManager(1)
        queuePolicy = QueuePolicyManager(redisSync)
        resultPublisher = ResultPublisher(redisSync)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "endpoint-key-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Endpoint Key Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Endpoint Key Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Endpoint Key Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@EndpointKeyDispatchTest.workspaceId
                it[name] = "Endpoint Key Project"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @Test
    fun `the envelope names one endpoint per call, in the script's order`() {
        val serviceId = seedService(
            "keyed",
            """
            get("https://API.example.com/a/").expect(status: 200)
            get("https://api.example.com/b/${'$'}${'$'}id?trace=1").expect(status: 200)
            """.trimIndent(),
        )

        val envelope = dispatchAndReadEnvelope(serviceId)
        val keys = envelope["endpointKeys"]?.jsonArray?.map { it.jsonPrimitive.content }
        assertEquals(
            listOf("GET https://api.example.com/a", "GET https://api.example.com/b/{id}"),
            keys,
        )
        // Index alignment with rawResult.calls is the whole contract: the
        // ingestor files step n under key n.
        assertEquals(envelope["rawResult"]!!.jsonObject["calls"]!!.jsonArray.size, keys!!.size)
    }

    @Test
    fun `a script that does not parse still dispatches, with no keys`() {
        // Not reachable through the API, which validates — but a script can
        // predate a grammar change, and losing the run would be far worse than
        // losing the statistic.
        val serviceId = seedService("unparseable", """get("https://api.example.com/a" .expect(status: 200)""")

        val envelope = dispatchAndReadEnvelope(serviceId)
        assertNull(envelope["endpointKeys"], "an unparseable script must publish no keys at all")
        assertNotNull(envelope["rawResult"], "the run itself must still be published")
    }

    private fun seedService(serviceName: String, script: String): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@EndpointKeyDispatchTest.projectId
                it[name] = serviceName
                it[Services.script] = script
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    /** Runs one dispatch through the real pipeline and returns what reached the queue. */
    private fun dispatchAndReadEnvelope(serviceId: UUID): JsonObject = runBlocking {
        redisSync.del(ResultPublisher.QUEUE_KEY)
        val queue = DispatchQueue(
            capacity = 4,
            workers = 1,
            quartzManager = quartzManager,
            executionBackend = FixedResultBackend(),
            queuePolicy = queuePolicy,
            resultPublisher = resultPublisher,
            probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
            trustedDomainMode = true,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            assertTrue(queue.enqueue(serviceId), "service should enqueue")
            val deadline = System.currentTimeMillis() + 10_000
            while (redisSync.llen(ResultPublisher.QUEUE_KEY) == 0L && System.currentTimeMillis() < deadline) delay(50)
            val raw = redisSync.rpop(ResultPublisher.QUEUE_KEY)
            assertNotNull(raw, "one result should have reached the queue")
            Json.parseToJsonElement(raw).jsonObject
        } finally {
            queue.close()
            scope.cancel()
        }
    }
}
