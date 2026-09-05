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
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.transaction
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
 * Whether a dispatched run is permitted to save response bodies.
 *
 * Two independent sources say no: the service's own `saveResponseBodies`
 * setting, and the unverified-domain anti-abuse rule (spec §18.4). Either one
 * alone is enough — they narrow, never widen.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BodySaveDispatchTest {

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
                it[email] = "body-save-test@tracedown.dev"
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, "Test1234!".toCharArray())
                it[displayName] = "Body Save Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Body Save Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Body Save Workspace"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@BodySaveDispatchTest.workspaceId
                it[name] = "Body Save Project"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @Test
    fun `a service that saves bodies dispatches with saving allowed`() {
        val serviceId = seedService("saves-bodies", saveBodies = true)
        assertEquals(true, dispatchAndCapture(serviceId, trustedDomainMode = true).allowBodySave)
    }

    @Test
    fun `a service with saving disabled dispatches with saving off`() {
        val serviceId = seedService("no-bodies", saveBodies = false)
        assertEquals(false, dispatchAndCapture(serviceId, trustedDomainMode = true).allowBodySave)
    }

    @Test
    fun `unverified domains force saving off even when the service saves bodies`() {
        val serviceId = seedService("unverified-target", saveBodies = true)
        assertEquals(false, dispatchAndCapture(serviceId, trustedDomainMode = false).allowBodySave)
    }

    private fun seedService(serviceName: String, saveBodies: Boolean): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@BodySaveDispatchTest.projectId
                it[name] = serviceName
                // The org verifies no domains, so this target is unverified.
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[saveResponseBodies] = saveBodies
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    /** Runs one dispatch through the real pipeline and returns what the backend was handed. */
    private fun dispatchAndCapture(
        serviceId: UUID,
        trustedDomainMode: Boolean,
    ): ProbeExecutionBackend.Request = runBlocking {
        val backend = CapturingBackend()
        val queue = DispatchQueue(
            capacity = 4,
            workers = 1,
            quartzManager = quartzManager,
            executionBackend = backend,
            queuePolicy = queuePolicy,
            resultPublisher = resultPublisher,
            probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
            trustedDomainMode = trustedDomainMode,
        )
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            assertTrue(queue.enqueue(serviceId), "service should enqueue")
            val deadline = System.currentTimeMillis() + 10_000
            while (backend.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(50)
            assertEquals(1, backend.requests.size, "one dispatch should have reached the backend")
            backend.requests.first()
        } finally {
            queue.close()
            scope.cancel()
        }
    }
}
