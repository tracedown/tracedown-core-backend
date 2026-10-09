package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.QuartzManager
import dev.tracedown.scheduler.scheduling.ScheduleSyncService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
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
 * A run asked for under an id is filed under that id — its result, or the
 * skipped row that says why it was not made — and a scheduled tick is not.
 *
 * The id rides on the envelope as its `resultId`, which every ingestor, old or
 * new, takes as the row's primary key; `trigger` is new, and an ingestor that
 * predates it simply files the run as scheduled.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RunRequestDispatchTest {

    companion object {
        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_run_request_test")
            .withUsername("test")
            .withPassword("test")

        @Container
        @JvmStatic
        val redis = GenericContainer("redis:8-alpine")
            .withExposedPorts(6379)
            .waitingFor(Wait.forListeningPort())
    }

    /** Answers every request with one result per agent in [agents]; [hold] keeps it waiting first. */
    private class Backend(
        private val agents: Int = 1,
        val hold: CompletableDeferred<Unit>? = null,
        /** Executions (by index) that produce no result: the agent did not answer. */
        private val failing: Set<Int> = emptySet(),
    ) : ProbeExecutionBackend {
        val requests = CopyOnWriteArrayList<ProbeExecutionBackend.Request>()

        override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
            requests.add(request)
            hold?.await()
            return (0 until agents).map { i ->
                if (i in failing) {
                    ProbeExecutionBackend.Execution(agentId = null, result = null, failureReason = "agent_unreachable")
                } else {
                    ProbeExecutionBackend.Execution(
                        agentId = null,
                        result = buildJsonObject { put("outcome", "success"); put("elapsedMs", 5) },
                    )
                }
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
                it[email] = "run-request-test@tracedown.dev"
                it[passwordHash] = "x"
                it[displayName] = "Run Request Test"
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Run Request Org"
                it[ownerId] = userId
                it[createdAt] = Instant.now()
            }
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "Run Request Workspace"
                it[createdAt] = Instant.now()
            }
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = this@RunRequestDispatchTest.workspaceId
                it[name] = "Run Request Project"
                it[createdAt] = Instant.now()
            }
        }
    }

    @AfterAll
    fun shutdown() = quartzManager.shutdown()

    @BeforeEach
    fun clearQueue() {
        redisSync.del(ResultPublisher.QUEUE_KEY)
    }

    private fun seedService(queuePolicy: String = "skip", probeMode: String = "consecutive", active: Boolean = true): UUID {
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = this@RunRequestDispatchTest.projectId
                it[name] = "svc-${serviceId.toString().take(6)}"
                it[script] = """get("https://testbin.tracedown.dev/status/200").expect(status: 200)"""
                it[schedule] = "*/5 * * * *"
                it[isActive] = active
                it[Services.queuePolicy] = queuePolicy
                it[Services.probeMode] = probeMode
                it[createdAt] = Instant.now()
            }
        }
        return serviceId
    }

    private fun queue(backend: ProbeExecutionBackend, capacity: Int = 4, workers: Int = 1) = DispatchQueue(
        capacity = capacity,
        workers = workers,
        quartzManager = quartzManager,
        executionBackend = backend,
        queuePolicy = queuePolicy,
        resultPublisher = resultPublisher,
        probeConfig = SchedulerConfig.ProbeConfig(defaultTimeoutMs = 30_000, maxTimeoutMs = 30_000, maxRedirects = 5),
        trustedDomainMode = true,
    )

    /** The envelopes queued for the ingestor, oldest first. */
    private fun envelopes(): List<JsonObject> =
        redisSync.lrange(ResultPublisher.QUEUE_KEY, 0, -1).reversed().map { Json.parseToJsonElement(it).jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    /** Runs [items] through a started queue until [count] envelopes are queued, and returns them. */
    private fun run(backend: ProbeExecutionBackend, count: Int, vararg items: DispatchItem): List<JsonObject> = runBlocking {
        val queue = queue(backend)
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        try {
            queue.start(scope)
            items.forEach { queue.enqueue(it) }
            val deadline = System.currentTimeMillis() + 10_000
            while (envelopes().size < count && System.currentTimeMillis() < deadline) delay(50)
            // Long enough for a row that should not be there to show up.
            delay(300)
            envelopes()
        } finally {
            queue.close()
            scope.cancel()
        }
    }

    @Test
    fun `a run asked for under an id is filed under it, as a manual run`() {
        val serviceId = seedService()
        val runId = UUID.randomUUID()
        val queued = run(Backend(), 1, DispatchItem(serviceId, manual = true, runId = runId))
        val envelope = queued.single()
        assertEquals(runId.toString(), envelope.str("resultId"))
        assertEquals(RunTrigger.MANUAL, envelope.str("trigger"))
        assertEquals(runId.toString(), envelope.str(RunTrigger.ENVELOPE_RUN_ID))
        assertEquals("1", envelope.str(RunTrigger.ENVELOPE_RUN_SIZE))
        assertEquals("success", envelope["rawResult"]!!.jsonObject.str("outcome"))
    }

    @Test
    fun `a scheduled tick and a run with no id are filed under ids of their own`() {
        val serviceId = seedService()
        val scheduled = run(Backend(), 1, DispatchItem.scheduled(serviceId)).single()
        assertEquals(RunTrigger.SCHEDULE, scheduled.str("trigger"))
        redisSync.del(ResultPublisher.QUEUE_KEY)
        val bare = run(Backend(), 1, DispatchItem(serviceId, manual = true)).single()
        assertEquals(RunTrigger.MANUAL, bare.str("trigger"))
        assertNotEquals(scheduled.str("resultId"), bare.str("resultId"))
    }

    @Test
    fun `in simultaneous mode the run's id goes to one result, and its siblings share the job`() {
        val serviceId = seedService(probeMode = "simultaneous")
        val runId = UUID.randomUUID()
        val queued = run(Backend(agents = 2), 2, DispatchItem(serviceId, manual = true, runId = runId))
        assertEquals(2, queued.size)
        assertEquals(1, queued.count { it.str("resultId") == runId.toString() })
        assertEquals(1, queued.map { it.str("jobId") }.distinct().size)
        assertTrue(queued.all { it.str("trigger") == RunTrigger.MANUAL })
        // Every result names the run and its size, so the last one in completes it.
        assertTrue(queued.all { it.str(RunTrigger.ENVELOPE_RUN_ID) == runId.toString() && it.str(RunTrigger.ENVELOPE_RUN_SIZE) == "2" })
        assertEquals(1, queued.map { it.str("startedAt") }.distinct().size, "siblings share the run's start")
    }

    @Test
    fun `an agent of a run that produced nothing is a skipped result of the run`() {
        val serviceId = seedService(probeMode = "simultaneous")
        val runId = UUID.randomUUID()
        val queued = run(Backend(agents = 3, failing = setOf(1)), 3, DispatchItem(serviceId, manual = true, runId = runId))
        assertEquals(3, queued.size)
        assertTrue(queued.all { it.str(RunTrigger.ENVELOPE_RUN_ID) == runId.toString() && it.str(RunTrigger.ENVELOPE_RUN_SIZE) == "3" })
        val skipped = queued.single { it["rawResult"]!!.jsonObject.str("outcome") == "skipped" }
        assertEquals("agent_unreachable", skipped["rawResult"]!!.jsonObject.str("reason"))
        assertEquals(1, queued.count { it.str("resultId") == runId.toString() })
    }

    @Test
    fun `a run that is not made is answered under its id, where a scheduled tick leaves nothing`() {
        val inactive = seedService(active = false)
        val runId = UUID.randomUUID()
        val skipped = run(Backend(), 1, DispatchItem(inactive, manual = true, runId = runId)).single()
        assertEquals(runId.toString(), skipped.str("resultId"))
        assertEquals("skipped", skipped["rawResult"]!!.jsonObject.str("outcome"))
        assertEquals(RunTrigger.SKIP_SERVICE_INACTIVE, skipped["rawResult"]!!.jsonObject.str("reason"))
        redisSync.del(ResultPublisher.QUEUE_KEY)
        assertTrue(run(Backend(), 0, DispatchItem.scheduled(inactive)).isEmpty(), "a scheduled tick of a switched-off service writes nothing")

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val busy = seedService(queuePolicy = "skip")
        redisSync.set("probe_active:$busy", "someone-else")
        try {
            val busyRun = UUID.randomUUID()
            val answer = run(Backend(), 1, DispatchItem(busy, manual = true, runId = busyRun)).single()
            assertEquals(busyRun.toString(), answer.str("resultId"))
            assertEquals(RunTrigger.SKIP_ALREADY_RUNNING, answer["rawResult"]!!.jsonObject.str("reason"))
        } finally {
            redisSync.del("probe_active:$busy")
        }

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val windowed = seedService()
        transaction { Services.update({ Services.id eq windowed }) { it[serviceWindow] = "FREQ=MINUTELY/60/UTC" } }
        val windowRun = UUID.randomUUID()
        assertEquals(
            RunTrigger.SKIP_IN_SERVICE_WINDOW,
            run(Backend(), 1, DispatchItem(windowed, manual = true, runId = windowRun)).single()["rawResult"]!!.jsonObject.str("reason"),
        )

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val held = seedService()
        val gate = DispatchGate.provider
        DispatchGate.register(object : DispatchGate.Provider { override fun allows(serviceId: UUID) = serviceId != held })
        try {
            val heldRun = UUID.randomUUID()
            val answer = run(Backend(), 1, DispatchItem(held, manual = true, runId = heldRun)).single()
            assertEquals(heldRun.toString(), answer.str("resultId"))
            assertEquals(RunTrigger.SKIP_HELD, answer["rawResult"]!!.jsonObject.str("reason"))
        } finally {
            DispatchGate.register(gate)
        }

        redisSync.del(ResultPublisher.QUEUE_KEY)
        val noScript = seedService()
        transaction { Services.update({ Services.id eq noScript }) { it[script] = "" } }
        val noScriptRun = UUID.randomUUID()
        val missing = run(Backend(), 1, DispatchItem(noScript, manual = true, runId = noScriptRun)).single()
        assertEquals(RunTrigger.SKIP_SCRIPT_MISSING, missing["rawResult"]!!.jsonObject.str("reason"))
    }

    @Test
    fun `a run that has to wait for the running one is filed under its id when it runs`() {
        val serviceId = seedService(queuePolicy = "enqueue_once")
        redisSync.set("probe_active:$serviceId", "holder")
        val runId = UUID.randomUUID()
        val other = UUID.randomUUID()
        try {
            val acquired = queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000, runId)
            assertEquals(QueuePolicyManager.AcquireResult.ENQUEUED, acquired.result)
            // A second run cannot also be the one that follows.
            assertEquals(QueuePolicyManager.AcquireResult.SKIPPED, queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000, other).result)
            // The holder's release hands the pending run's id over, in the same step.
            assertEquals(QueuePolicyManager.Released(true, runId), queuePolicy.releaseWithPending(serviceId, "holder"))
            assertEquals(null, redisSync.get("probe_pending_run:$serviceId"), "no id left behind")
            // A pending run with no id is reported as one.
            redisSync.set("probe_active:$serviceId", "holder")
            assertEquals(QueuePolicyManager.AcquireResult.ENQUEUED, queuePolicy.tryAcquire(serviceId, "enqueue_once", 30_000).result)
            assertEquals(QueuePolicyManager.Released(true, null), queuePolicy.releaseWithPending(serviceId, "holder"))
            // Somebody else's lock is not released, and hands nothing over.
            redisSync.set("probe_active:$serviceId", "holder")
            assertEquals(QueuePolicyManager.Released(false, null), queuePolicy.releaseWithPending(serviceId, "not-the-holder"))
        } finally {
            redisSync.del("probe_active:$serviceId", "probe_pending:$serviceId", "probe_pending_run:$serviceId")
        }

        // Through the queue: a second worker finds the lock held and leaves the
        // run pending; the lock's owner re-enqueues it under its id.
        val hold = CompletableDeferred<Unit>()
        val backend = Backend(hold = hold)
        val queued = runBlocking {
            val queue = queue(backend, workers = 2)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                queue.start(scope)
                queue.enqueue(DispatchItem.scheduled(serviceId))
                val deadline = System.currentTimeMillis() + 10_000
                while (backend.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
                // The scheduled tick holds the lock; this run waits behind it.
                queue.enqueue(DispatchItem(serviceId, manual = true, runId = runId))
                delay(300)
                assertEquals(1, backend.requests.size, "the run waits behind the one holding the lock")
                assertEquals(runId.toString(), redisSync.get("probe_pending_run:$serviceId"))
                hold.complete(Unit)
                while (envelopes().size < 2 && System.currentTimeMillis() < deadline) delay(50)
                envelopes()
            } finally {
                queue.close()
                scope.cancel()
            }
        }
        assertEquals(listOf(RunTrigger.SCHEDULE, RunTrigger.MANUAL), queued.map { it.str("trigger") })
        assertEquals(runId.toString(), queued[1].str("resultId"))
    }

    @Test
    fun `a run that finds its service already waiting in the queue is answered as already queued`() {
        val busy = seedService()
        val other = seedService()
        val hold = CompletableDeferred<Unit>()
        val backend = Backend(hold = hold)
        val runId = UUID.randomUUID()
        val queued = runBlocking {
            val queue = queue(backend, workers = 1)
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            try {
                queue.start(scope)
                // The one worker is busy with another service; this one waits in the queue.
                queue.enqueue(DispatchItem.scheduled(other))
                val deadline = System.currentTimeMillis() + 10_000
                while (backend.requests.isEmpty() && System.currentTimeMillis() < deadline) delay(20)
                assertTrue(queue.enqueue(DispatchItem.scheduled(busy)))
                assertTrue(!queue.enqueue(DispatchItem(busy, manual = true, runId = runId)), "the run is shed")
                while (envelopes().none { it.str("resultId") == runId.toString() } && System.currentTimeMillis() < deadline) delay(50)
                hold.complete(Unit)
                // Both ticks finish too, before the queue closes: nothing of
                // this test may land in the next one's queue.
                while (envelopes().size < 3 && System.currentTimeMillis() < deadline) delay(50)
                envelopes()
            } finally {
                queue.close()
                scope.cancel()
            }
        }
        val answer = queued.single { it.str("resultId") == runId.toString() }
        assertEquals(RunTrigger.SKIP_ALREADY_QUEUED, answer["rawResult"]!!.jsonObject.str("reason"))
    }

    @Test
    fun `every replica hears a run, and one runs it`() {
        val serviceId = UUID.randomUUID()
        val runId = UUID.randomUUID()
        val enqueued = CopyOnWriteArrayList<DispatchItem>()
        val replicas = (1..3).map { ScheduleSyncService(quartzManager, 60, pubSubConnection = dev.tracedown.scheduler.scheduling.noPubSub(), claims = redisSync) { item -> enqueued.add(item) } }
        replicas.forEach { it.onMessage(RunTrigger.RUN_CHANNEL, RunTrigger.encodeRun(serviceId, runId)) }
        assertEquals(listOf(DispatchItem(serviceId, manual = true, runId = runId)), enqueued)
    }
}
