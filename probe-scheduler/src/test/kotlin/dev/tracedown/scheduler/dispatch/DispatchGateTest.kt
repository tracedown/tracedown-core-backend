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
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
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
 * The dispatch gate holds a service's ticks without writing them down.
 *
 * A refused tick must leave the service exactly as it found it: no result row
 * (a `skipped` row would blame the platform for a run nobody asked for), no
 * shed record, and the Quartz job still in place, because a hold is temporary
 * and the next tick has to ask again.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DispatchGateTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

        /**
         * The pristine provider, captured before any test registers one — the
         * only handle on the default, since [DispatchGate] has no unregister.
         */
        private val DEFAULT_PROVIDER = DispatchGate.provider

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

    /** A gate with a fixed answer that records who asked. */
    private class RecordingGate(private val answer: Boolean) : DispatchGate.Provider {
        val asked = CopyOnWriteArrayList<UUID>()

        override fun allows(serviceId: UUID): Boolean {
            asked.add(serviceId)
            return answer
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
                it[email] = "dispatch-gate-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Dispatch Gate Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Dispatch Gate Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Dispatch Gate Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@DispatchGateTest.workspaceId
                it[name] = "Dispatch Gate Project"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @AfterEach
    fun restoreDefaultGate() {
        DispatchGate.register(DEFAULT_PROVIDER)
    }

    @Test
    fun `the default gate lets every service through`() {
        assertTrue(DEFAULT_PROVIDER.allows(UUID.randomUUID()), "Core holds nothing back")
        assertTrue(DispatchGate.provider.allows(UUID.randomUUID()), "the registered provider is the default")
    }

    @Test
    fun `a refused tick writes nothing and stays scheduled`() {
        val serviceId = seedService("held-service")
        quartzManager.scheduleService(serviceId, "*/5 * * * *")
        val gate = RecordingGate(answer = false)
        DispatchGate.register(gate)

        val backend = runTick(serviceId) { gate.asked.isNotEmpty() }

        assertEquals(listOf(serviceId), gate.asked.toList(), "the gate should have been asked once")
        assertTrue(backend.requests.isEmpty(), "a held service must not reach the execution backend")
        assertEquals(0L, redisSync.llen(ResultPublisher.QUEUE_KEY), "a held tick must leave no result row")
        assertTrue(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "a hold is temporary — the service must stay scheduled so the next tick asks again",
        )
    }

    @Test
    fun `an allowing gate changes nothing`() {
        val serviceId = seedService("allowed-service")
        val gate = RecordingGate(answer = true)
        DispatchGate.register(gate)

        val backend = runTick(serviceId) { it.requests.isNotEmpty() }

        assertEquals(listOf(serviceId), gate.asked.toList(), "the gate should have been asked once")
        assertEquals(1, backend.requests.size, "an allowed tick dispatches as it always did")
    }

    private fun seedService(serviceName: String): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@DispatchGateTest.projectId
                it[name] = serviceName
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    /**
     * Runs one tick through the real pipeline and returns the backend it was
     * handed, once [done] reports the tick has run its course.
     */
    private fun runTick(
        serviceId: UUID,
        done: (CapturingBackend) -> Boolean,
    ): CapturingBackend = runBlocking {
        redisSync.del(ResultPublisher.QUEUE_KEY)
        val backend = CapturingBackend()
        val queue = DispatchQueue(
            capacity = 4,
            workers = 1,
            quartzManager = quartzManager,
            executionBackend = backend,
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
            while (!done(backend) && System.currentTimeMillis() < deadline) delay(50)
            assertTrue(done(backend), "the tick did not run within the deadline")
            // Give any row the tick would have written time to be recorded, so
            // "nothing was written" is a settled fact rather than a race.
            delay(500)
            backend
        } finally {
            queue.close()
            scope.cancel()
        }
    }
}
