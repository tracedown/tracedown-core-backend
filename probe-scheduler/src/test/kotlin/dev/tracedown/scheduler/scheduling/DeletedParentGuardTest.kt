package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.scheduler.dispatch.DispatchQueue
import dev.tracedown.scheduler.dispatch.ProbeExecutionBackend
import dev.tracedown.scheduler.dispatch.QueuePolicyManager
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
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
 * A service whose project or workspace is gone must not probe, whatever its
 * own `deleted` flag says.
 *
 * Deleting a container carries its services down, but that is one cascade in
 * one transaction in another service — and until this version it did not
 * happen at all, which is how live services ended up under deleted projects in
 * databases that are still running. The scheduler therefore asks about the
 * whole ancestry rather than trusting `services.deleted` on its own, at both
 * points where it decides a service may run: the sync that builds the Quartz
 * schedule, and the dispatch that fires a tick.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DeletedParentGuardTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_deleted_parent_test")
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
    private lateinit var pubSubConnection: StatefulRedisPubSubConnection<String, String>
    private lateinit var quartzManager: QuartzManager
    private lateinit var queuePolicy: QueuePolicyManager
    private lateinit var resultPublisher: ResultPublisher

    private val orgId: UUID = UUID.randomUUID()

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()

        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        val redisUrl = "redis://${redis.host}:${redis.getMappedPort(6379)}"
        redisSync = RedisFactory.createConnection(redisUrl).sync()
        pubSubConnection = RedisFactory.createPubSubConnection(redisUrl)

        quartzManager = QuartzManager(1)
        queuePolicy = QueuePolicyManager(redisSync)
        resultPublisher = ResultPublisher(redisSync)

        transaction {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[email] = "deleted-parent-test@tracedown.dev"
                it[passwordHash] = "x"
                it[displayName] = "Deleted Parent Test"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Deleted Parent Org"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
    }

    @AfterAll
    fun tearDown() {
        runCatching { pubSubConnection.close() }
    }

    // ── Sync ──

    @Test
    fun `bootstrap loads a service whose containers are live`() {
        val (_, _, serviceId) = seedTree()

        val sync = ScheduleSyncService(quartzManager, 300, pubSubConnection)
        sync.bootstrap()

        assertTrue(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "the ancestry join must not cost the happy path",
        )
        quartzManager.unscheduleService(serviceId)
    }

    @Test
    fun `bootstrap skips a service whose project is deleted`() {
        val (_, projectId, serviceId) = seedTree()
        softDeleteProject(projectId)

        val sync = ScheduleSyncService(quartzManager, 300, pubSubConnection)
        sync.bootstrap()

        assertFalse(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "a service under a deleted project must never be scheduled",
        )
    }

    @Test
    fun `bootstrap skips a service whose workspace is deleted`() {
        val (workspaceId, _, serviceId) = seedTree()
        softDeleteWorkspace(workspaceId)

        val sync = ScheduleSyncService(quartzManager, 300, pubSubConnection)
        sync.bootstrap()

        assertFalse(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "a service under a deleted workspace must never be scheduled",
        )
    }

    @Test
    fun `a nudge unschedules a service whose project has been deleted`() {
        val (_, projectId, serviceId) = seedTree()
        quartzManager.scheduleService(serviceId, "*/5 * * * *")
        assertTrue(quartzManager.getScheduledServiceIds().contains(serviceId))

        softDeleteProject(projectId)

        val sync = ScheduleSyncService(quartzManager, 300, pubSubConnection)
        sync.handleNudge(serviceId)

        assertFalse(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "the nudge a container delete sends must drop the job, not refresh it",
        )
    }

    // ── Dispatch ──

    @Test
    fun `a tick for a service under a deleted project reaches no agent`() {
        val (_, projectId, serviceId) = seedTree()
        quartzManager.scheduleService(serviceId, "*/5 * * * *")
        softDeleteProject(projectId)

        val backend = runTick(serviceId)

        assertTrue(
            backend.requests.isEmpty(),
            "a deleted project's service must not be probed",
        )
        assertFalse(
            quartzManager.getScheduledServiceIds().contains(serviceId),
            "the tick should also drop the job it just refused",
        )
    }

    @Test
    fun `a tick for a service under a deleted workspace reaches no agent`() {
        val (workspaceId, _, serviceId) = seedTree()
        quartzManager.scheduleService(serviceId, "*/5 * * * *")
        softDeleteWorkspace(workspaceId)

        val backend = runTick(serviceId)

        assertTrue(backend.requests.isEmpty(), "a deleted workspace's service must not be probed")
    }

    @Test
    fun `a tick for a live service still dispatches`() {
        val (_, _, serviceId) = seedTree()

        val backend = runTick(serviceId) { it.requests.isNotEmpty() }

        assertTrue(backend.requests.isNotEmpty(), "the ancestry join must not cost the happy path")
    }

    // ── Fixtures ──

    /** A fresh workspace → project → active service, all live. */
    private fun seedTree(): Triple<UUID, UUID, UUID> {
        val workspaceId = UUID.randomUUID()
        val projectId = UUID.randomUUID()
        val serviceId = UUID.randomUUID()
        transaction {
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "ws-$workspaceId"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = workspaceId
                it[name] = "proj-$projectId"
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = projectId
                it[name] = "svc-$serviceId"
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }
        return Triple(workspaceId, projectId, serviceId)
    }

    /**
     * Flips the container only, leaving the service live — the state a database
     * upgraded from an older version is in, and the state a missed cascade
     * would leave.
     */
    private fun softDeleteProject(projectId: UUID) = transaction {
        Projects.update({ Projects.id eq projectId }) {
            it[deleted] = true
            it[deletedAt] = Instant.now()
        }
    }

    private fun softDeleteWorkspace(workspaceId: UUID) = transaction {
        Workspaces.update({ Workspaces.id eq workspaceId }) {
            it[deleted] = true
            it[deletedAt] = Instant.now()
        }
    }

    /**
     * Runs one tick through the real pipeline. [done] reports when the tick has
     * run its course; with no answer to wait for, the deadline is the answer.
     */
    private fun runTick(
        serviceId: UUID,
        done: (CapturingBackend) -> Boolean = { false },
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
            val deadline = System.currentTimeMillis() + 5_000
            while (!done(backend) && System.currentTimeMillis() < deadline) delay(50)
            // Settle: "nothing was dispatched" has to be a fact, not a race.
            delay(500)
            backend
        } finally {
            queue.close()
            scope.cancel()
        }
    }
}
