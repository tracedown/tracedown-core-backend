package dev.tracedown.scheduler.dispatch

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.net.ProbeTargetPolicy
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.QuartzManager
import dev.tracedown.scheduler.variables.VariableResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A variable whose value will not decrypt is absent at dispatch, as it is at
 * save — not an error that fails the tick.
 *
 * It used to throw out of [VariableResolver], and since every variable of every
 * scope a service sits in is loaded, one unreadable row (a crypto-shredded org
 * key, a value moved under another key) errored every tick of every service
 * beneath it, whether its script used the variable or not. Now the reference
 * stays unresolved and the tick takes that path: under the public-only policy
 * it is skipped for the same reason the save was refused.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UndecryptableVariableDispatchTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val D = "$"

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

    /** Records what the pipeline asked for; runs nothing. */
    private class CapturingBackend : ProbeExecutionBackend {
        val requests = CopyOnWriteArrayList<ProbeExecutionBackend.Request>()

        override suspend fun execute(
            request: ProbeExecutionBackend.Request,
        ): List<ProbeExecutionBackend.Execution> {
            requests.add(request)
            return emptyList()
        }
    }

    private lateinit var redisSync: io.lettuce.core.api.sync.RedisCommands<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

    private val orgId: UUID = UUID.randomUUID()
    private val workspaceId: UUID = UUID.randomUUID()

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
                it[email] = "undecryptable-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Undecryptable Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Undecryptable Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Undecryptable Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @Test
    fun `an undecryptable host variable is skipped as unresolved, like a missing one`() {
        val projectId = seedProject()
        storeUndecryptable(projectId, "baseUrl")
        val broken = seedService(projectId, """get("${D}p.baseUrl/health")""")
        val missing = seedService(projectId, """get("${D}p.neverSet/health")""")

        val brokenOutcome = dispatch(broken, ProbeTargetPolicy.Mode.PUBLIC_ONLY)
        val missingOutcome = dispatch(missing, ProbeTargetPolicy.Mode.PUBLIC_ONLY)

        assertTrue(brokenOutcome.requests.isEmpty(), "nothing may be dispatched")
        val skipped = brokenOutcome.skipped
        assertNotNull(skipped, "a skipped row, not an error")
        // The reason the save-time check gives the same script.
        assertEquals(ProbeTargetPolicy.REASON_MALFORMED, skipped!!["reason"]!!.jsonPrimitive.content)
        assertEquals(
            missingOutcome.skipped!!["reason"]!!.jsonPrimitive.content,
            skipped["reason"]!!.jsonPrimitive.content,
            "the same path a variable that was never set takes",
        )
    }

    @Test
    fun `an unreadable variable the script does not use no longer stops its neighbours`() {
        val projectId = seedProject()
        storeUndecryptable(projectId, "unrelated")
        storeVariable(projectId, "baseUrl", "https://example.org")
        val serviceId = seedService(projectId, """get("${D}p.baseUrl/health")""")

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.PUBLIC_ONLY)

        assertEquals(1, outcome.requests.size, "the tick runs")
        val vars = outcome.requests.single().variables
        assertEquals("https://example.org", vars["p_baseUrl"]!!.jsonPrimitive.content)
        assertFalse(vars.containsKey("p_unrelated"))
    }

    @Test
    fun `where private targets are allowed the tick runs with the variable absent`() {
        val projectId = seedProject()
        storeUndecryptable(projectId, "baseUrl")
        val serviceId = seedService(projectId, """get("${D}p.baseUrl/health")""")

        val outcome = dispatch(serviceId, ProbeTargetPolicy.Mode.ALLOW_PRIVATE)

        // As for a variable never set: the executor gets the reference with no
        // value and reports the call's failure itself.
        assertEquals(1, outcome.requests.size)
        assertFalse(outcome.requests.single().variables.containsKey("p_baseUrl"))
        assertEquals(
            false,
            VariableResolver.resolve(serviceId, """get("${D}p.baseUrl/health")""").variables.containsKey("p_baseUrl"),
        )
    }

    // ── Harness ──

    private data class Outcome(
        val requests: List<ProbeExecutionBackend.Request>,
        val skipped: JsonObject?,
    )

    private fun seedProject(): UUID {
        val id = UUID.randomUUID()
        transaction {
            Projects.insert {
                it[Projects.id] = id
                it[Projects.workspaceId] = this@UndecryptableVariableDispatchTest.workspaceId
                it[name] = "proj-${id.toString().take(8)}"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return id
    }

    private fun seedService(projectId: UUID, script: String): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = projectId
                it[name] = "svc-${serviceId.toString().take(8)}"
                it[Services.script] = script
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    /** A secret that will not decrypt: encrypted for another key, so its AAD fails. */
    private fun storeUndecryptable(projectId: UUID, key: String) {
        val stored = transaction { VariableCrypto.encrypt(orgId, "http://10.0.0.5", "project", "$key-elsewhere") }
        insertVariable(projectId, key, stored, null, secret = true)
    }

    private fun storeVariable(projectId: UUID, key: String, value: String) {
        val (stored, iv) = VariableCrypto.encrypt(value)
        insertVariable(projectId, key, stored, iv, secret = false)
    }

    private fun insertVariable(projectId: UUID, key: String, value: String, iv: String?, secret: Boolean) {
        transaction {
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = projectId
                it[ProjectVariables.key] = key
                it[ProjectVariables.value] = value
                it[valueIv] = iv
                it[ProjectVariables.secret] = secret
                it[encrypted] = true
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }
        }
    }

    /** Runs one tick through the real pipeline and reports what came of it. */
    private fun dispatch(serviceId: UUID, mode: ProbeTargetPolicy.Mode): Outcome = runBlocking {
        val backend = CapturingBackend()
        redisSync.del(ResultPublisher.QUEUE_KEY)
        val queue = DispatchQueue(
            capacity = 4,
            workers = 1,
            quartzManager = quartzManager,
            executionBackend = backend,
            queuePolicy = queuePolicy,
            resultPublisher = resultPublisher,
            probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
            trustedDomainMode = true,
            targetPolicy = mode,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            assertTrue(queue.enqueue(serviceId), "service should enqueue")
            val deadline = System.currentTimeMillis() + 10_000
            var skipped: JsonObject? = null
            var other: JsonObject? = null
            while (System.currentTimeMillis() < deadline) {
                val raw = queuedResult()
                if (raw?.get("outcome")?.jsonPrimitive?.content == "skipped") skipped = raw else other = raw
                if (backend.requests.isNotEmpty() || raw != null) break
                delay(50)
            }
            assertEquals(null, other, "the tick must not produce an error result")
            Outcome(backend.requests.toList(), skipped)
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    private fun queuedResult(): JsonObject? {
        val envelope = redisSync.lindex(ResultPublisher.QUEUE_KEY, 0) ?: return null
        return Json.parseToJsonElement(envelope).jsonObject["rawResult"]?.jsonObject
    }
}
