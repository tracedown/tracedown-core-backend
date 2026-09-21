package dev.tracedown.notifications.consumers

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.Outbox
import io.lettuce.core.pubsub.RedisPubSubListener
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import io.lettuce.core.pubsub.api.sync.RedisPubSubCommands
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency tests for [OutboxConsumer] against the real schema.
 *
 * The regression this file exists for is [`two instances deliver every event
 * exactly once`]: before the claim, two processes read the same
 * `published = false` rows and both delivered them, so every recipient was
 * mailed twice and every bound webhook called twice. That test fails — loudly,
 * with duplicate deliveries — against a consumer whose read takes no claim, and
 * it is the reason the rest of the file exists, so keep it.
 */
@Testcontainers
class OutboxClaimTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_outbox_claim_test")
            .withUsername("test")
            .withPassword("test")

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            Database.connect(
                HikariDataSource(
                    HikariConfig().apply {
                        jdbcUrl = postgres.jdbcUrl
                        username = postgres.username
                        password = postgres.password
                        driverClassName = "org.postgresql.Driver"
                        // Two consumers, each claiming and publishing, plus the
                        // test's own transactions.
                        maximumPoolSize = 12
                    },
                ),
            )
        }

        private const val EVENT = OutboxConsumer.EVENT_TYPE
    }

    @BeforeEach
    fun clean() {
        transaction { Outbox.deleteAll() }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Writes one `probe_result.created` row the way result-ingestor does:
     * `aggregate_id` is the RESULT id (not the service), the service lives in
     * the payload, and `created_at` is the probe's start time.
     */
    private fun insertEvent(
        serviceId: UUID,
        startedAt: Instant,
        eventType: String = EVENT,
    ): UUID {
        val resultId = UUID.randomUUID()
        transaction {
            Outbox.insert {
                it[id] = UUID.randomUUID()
                it[aggregateType] = "probe_result"
                it[aggregateId] = resultId
                it[Outbox.eventType] = eventType
                it[payload] = buildJsonObject {
                    put("resultId", resultId.toString())
                    put("serviceId", serviceId.toString())
                }
                it[published] = false
                it[createdAt] = startedAt
            }
        }
        return resultId
    }

    private fun publishedCount(): Long = transaction {
        Outbox.selectAll().where { Outbox.published eq true }.count()
    }

    private fun unpublishedCount(): Long = transaction {
        Outbox.selectAll().where { Outbox.published eq false }.count()
    }

    private fun claimOf(resultId: UUID): Pair<String?, Instant?> = transaction {
        val row = Outbox.selectAll().where { Outbox.aggregateId eq resultId }.single()
        row[Outbox.claimedBy] to row[Outbox.claimedAt]
    }

    /** Ages every live claim so the lease looks expired. */
    private fun expireAllClaims(bySeconds: Long) = transaction {
        TransactionManager.current().exec(
            "UPDATE outbox SET claimed_at = claimed_at - make_interval(secs => $bySeconds) " +
                "WHERE claimed_at IS NOT NULL",
        )
    }

    private fun resultIdOf(payload: JsonObject): UUID =
        UUID.fromString(payload["resultId"]!!.jsonPrimitive.content)

    private fun consumer(
        processor: OutboxEventProcessor,
        instanceId: String,
        batchSize: Int = 5,
        leaseSeconds: Long = 120L,
        pollIntervalMs: Long = 50L,
        pubSub: StatefulRedisPubSubConnection<String, String> = noopPubSub(),
    ) = OutboxConsumer(
        processor = processor,
        pubSubConnection = pubSub,
        pollIntervalMs = pollIntervalMs,
        batchSize = batchSize,
        claimLeaseSeconds = leaseSeconds,
        instanceId = instanceId,
    )

    /** Records every payload it is handed, and how long it held it. */
    private class Recorder(
        private val work: suspend (JsonObject) -> Unit = { delay(2) },
    ) : OutboxEventProcessor {
        val seen = java.util.Collections.synchronizedList(mutableListOf<UUID>())
        /** Services currently being processed — a second entry is an ordering violation. */
        val inFlight = ConcurrentHashMap<UUID, String>()
        val overlaps = AtomicInteger(0)

        override suspend fun process(payload: JsonObject) {
            val serviceId = UUID.fromString(payload["serviceId"]!!.jsonPrimitive.content)
            val resultId = UUID.fromString(payload["resultId"]!!.jsonPrimitive.content)
            if (inFlight.putIfAbsent(serviceId, resultId.toString()) != null) overlaps.incrementAndGet()
            try {
                seen.add(resultId)
                work(payload)
            } finally {
                inFlight.remove(serviceId)
            }
        }
    }

    // ── The regression ──────────────────────────────────────────────────────

    /**
     * Two instances, one database, every event delivered exactly once.
     *
     * Repeated because the failure is a race: the window is between one
     * instance reading a row and the other reading the same row, and a single
     * pass can miss it. Small batches and a processor that takes real time
     * widen it deliberately.
     */
    @RepeatedTest(10)
    fun `two instances deliver every event exactly once`() = runBlocking {
        val services = List(12) { UUID.randomUUID() }
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        val expected = mutableListOf<UUID>()
        services.forEach { svc ->
            repeat(5) { i -> expected += insertEvent(svc, base.plusSeconds(i.toLong())) }
        }

        val recorder = Recorder()
        val a = consumer(recorder, "instance-a")
        val b = consumer(recorder, "instance-b")

        val drained = withTimeoutOrNull(60_000) {
            listOf(a, b).map { c ->
                async(Dispatchers.Default) {
                    while (unpublishedCount() > 0) {
                        if (!c.claimAndProcessOnce()) delay(5)
                    }
                }
            }.awaitAll()
            true
        }
        assertTrue(drained == true, "the two instances did not drain the outbox")

        assertEquals(
            expected.size.toLong(), publishedCount(),
            "every row should be published exactly once",
        )
        assertEquals(
            expected.size, recorder.seen.size,
            "duplicate delivery: ${recorder.seen.size} deliveries for ${expected.size} events " +
                "(duplicates: ${recorder.seen.groupBy { it }.filterValues { it.size > 1 }.keys})",
        )
        assertEquals(expected.toSet(), recorder.seen.toSet(), "every event should have been delivered")
        assertEquals(0, recorder.overlaps.get(), "two rows of one service were processed concurrently")
    }

    /** Both instances took work — otherwise the test above proves nothing. */
    @Test
    fun `both instances take a share of the work`() = runBlocking {
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        repeat(40) { insertEvent(UUID.randomUUID(), base.plusSeconds(it.toLong())) }

        val byInstance = ConcurrentHashMap<String, AtomicInteger>()
        fun recorderFor(name: String) = OutboxEventProcessor {
            byInstance.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
            delay(5)
        }

        val a = consumer(recorderFor("a"), "instance-a", batchSize = 3)
        val b = consumer(recorderFor("b"), "instance-b", batchSize = 3)

        withTimeoutOrNull(60_000) {
            listOf(a, b).map { c ->
                async(Dispatchers.Default) {
                    while (unpublishedCount() > 0) {
                        if (!c.claimAndProcessOnce()) delay(5)
                    }
                }
            }.awaitAll()
        }

        assertEquals(40L, publishedCount())
        assertTrue(
            (byInstance["a"]?.get() ?: 0) > 0 && (byInstance["b"]?.get() ?: 0) > 0,
            "one instance did all the work, so the claim was never contended: $byInstance",
        )
    }

    // ── Ordering ────────────────────────────────────────────────────────────

    /**
     * A service's second row is not claimable while its first is still
     * unpublished, whoever holds it.
     *
     * This is what keeps a recovery mail behind the failure it recovers from.
     */
    @Test
    fun `an older unpublished row of the same service blocks the ones after it`() = runBlocking {
        val svc = UUID.randomUUID()
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        val failure = insertEvent(svc, base)
        val recovery = insertEvent(svc, base.plusSeconds(60))

        val gate = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val aSeen = mutableListOf<UUID>()
        val a = consumer(
            { payload ->
                aSeen += resultIdOf(payload)
                started.complete(Unit)
                gate.await()
            },
            "instance-a",
        )
        val bSeen = mutableListOf<UUID>()
        val b = consumer({ payload -> bSeen += resultIdOf(payload) }, "instance-b")

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            scope.launch { a.claimAndProcessOnce() }
            started.await()

            // A holds the failure row. B must find nothing at all — not the
            // recovery, which would land ahead of the failure.
            assertFalse(b.claimAndProcessOnce(), "the recovery row was claimable while the failure was in flight")
            assertTrue(bSeen.isEmpty())

            gate.complete(Unit)
            withTimeoutOrNull(5_000) { while (unpublishedCount() > 1L) delay(5) }

            // Only now is the recovery claimable.
            assertTrue(b.claimAndProcessOnce(), "the recovery row should be claimable once the failure is published")
            assertEquals(listOf(recovery), bSeen)
            assertEquals(listOf(failure), aSeen)
        } finally {
            scope.cancel()
        }
    }

    /** Different services are not serialised against each other. */
    @Test
    fun `rows of different services are claimed in parallel`() = runBlocking {
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        val a1 = insertEvent(UUID.randomUUID(), base)
        val b1 = insertEvent(UUID.randomUUID(), base.plusSeconds(1))

        val seen = mutableListOf<UUID>()
        val c = consumer({ payload -> seen += resultIdOf(payload) }, "instance-a", batchSize = 10)
        assertTrue(c.claimAndProcessOnce())
        assertEquals(setOf(a1, b1), seen.toSet(), "one batch should carry one row of each service")
    }

    /**
     * Event types this consumer does not handle never enter the ordering rule.
     *
     * `resource.*` rows are read by cursor consumers and are never published by
     * anyone, so a claim query that matched them on either side of the
     * exclusion would find them blocking their aggregate forever.
     */
    @Test
    fun `a foreign event type neither is claimed nor blocks`() = runBlocking {
        val svc = UUID.randomUUID()
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        insertEvent(svc, base, eventType = "resource.workspace.created")
        val mine = insertEvent(svc, base.plusSeconds(1))

        val seen = mutableListOf<UUID>()
        val c = consumer({ payload -> seen += resultIdOf(payload) }, "instance-a", batchSize = 10)

        assertTrue(c.claimAndProcessOnce())
        assertEquals(listOf(mine), seen, "only the probe_result event belongs to this consumer")
        assertFalse(c.claimAndProcessOnce(), "the resource event must not be claimable")
        assertEquals(1L, unpublishedCount(), "the resource event stays for its cursor consumer")
    }

    // ── Lease expiry ────────────────────────────────────────────────────────

    /**
     * A row left claimed by an instance that never came back is taken over once
     * the lease expires — and not before.
     */
    @Test
    fun `an expired lease is taken over by another instance`() = runBlocking {
        val svc = UUID.randomUUID()
        val resultId = insertEvent(svc, Instant.now().minus(1, ChronoUnit.HOURS))

        // A claims it and dies before publishing.
        val a = consumer({ throw IllegalStateException("killed mid-delivery") }, "instance-a")
        assertTrue(a.claimAndProcessOnce())
        val (claimedBy, claimedAt) = claimOf(resultId)
        assertEquals("instance-a", claimedBy)
        assertNotNull(claimedAt, "a failed row keeps its claim — the lease is its backoff")
        assertEquals(1L, unpublishedCount())

        val bSeen = mutableListOf<UUID>()
        val b = consumer({ payload -> bSeen += resultIdOf(payload) }, "instance-b")
        assertFalse(b.claimAndProcessOnce(), "the row is still leased to the dead instance")

        expireAllClaims(200)

        assertTrue(b.claimAndProcessOnce(), "the row should be claimable once the lease expires")
        assertEquals(listOf(resultId), bSeen)
        assertEquals("instance-b", claimOf(resultId).first)
        assertEquals(1L, publishedCount())
    }

    /**
     * A row that keeps throwing is retried once per lease, not once per poll.
     *
     * There is no attempt counter on the outbox; the lease is what stops a
     * permanently failing row from being re-claimed in a hot loop.
     */
    @Test
    fun `a poison row is not re-claimed until its lease expires`() = runBlocking {
        val svc = UUID.randomUUID()
        insertEvent(svc, Instant.now().minus(1, ChronoUnit.HOURS))

        val attempts = AtomicInteger()
        val c = consumer({ attempts.incrementAndGet(); throw RuntimeException("poison") }, "instance-a")

        assertTrue(c.claimAndProcessOnce())
        repeat(20) { assertFalse(c.claimAndProcessOnce(), "poison row was re-claimed inside its lease") }
        assertEquals(1, attempts.get())

        expireAllClaims(200)
        assertTrue(c.claimAndProcessOnce())
        assertEquals(2, attempts.get(), "the row is retried once the lease expires")
        assertEquals(1L, unpublishedCount(), "a row that never succeeds is never published")
    }

    /** A poison row holds up its own service and nothing else. */
    @Test
    fun `a poison row does not stall other services`() = runBlocking {
        val bad = UUID.randomUUID()
        val good = UUID.randomUUID()
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        val poison = insertEvent(bad, base)
        insertEvent(bad, base.plusSeconds(60))
        val healthy = insertEvent(good, base.plusSeconds(1))

        val seen = mutableListOf<UUID>()
        val c = consumer(
            { payload ->
                val id = resultIdOf(payload)
                if (id == poison) throw RuntimeException("poison")
                seen += id
            },
            "instance-a",
            batchSize = 10,
        )

        assertTrue(c.claimAndProcessOnce())
        assertFalse(c.claimAndProcessOnce())

        assertEquals(listOf(healthy), seen)
        assertEquals(2L, unpublishedCount(), "the poison row and the one queued behind it both wait")
    }

    // ── Single instance ─────────────────────────────────────────────────────

    @Test
    fun `one instance delivers everything once, oldest first`() = runBlocking {
        val svc = UUID.randomUUID()
        val base = Instant.now().minus(1, ChronoUnit.HOURS)
        val ordered = (0 until 6).map { insertEvent(svc, base.plusSeconds(it * 60L)) }

        val seen = mutableListOf<UUID>()
        val c = consumer({ payload -> seen += resultIdOf(payload) }, "instance-a", batchSize = 10)

        withTimeoutOrNull(20_000) {
            var more = true
            while (more) more = c.claimAndProcessOnce()
        }

        assertEquals(ordered, seen, "one service's rows are delivered in probe order")
        assertEquals(6L, publishedCount())
    }

    @Test
    fun `a published row records who delivered it`() = runBlocking {
        val resultId = insertEvent(UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.HOURS))
        val c = consumer({ }, "instance-a")
        assertTrue(c.claimAndProcessOnce())
        assertEquals("instance-a", claimOf(resultId).first)
    }

    // ── Wake-ups ────────────────────────────────────────────────────────────

    @Test
    fun `the interval poll picks up work without a nudge`() = runBlocking {
        val resultId = insertEvent(UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.HOURS))

        val seen = java.util.Collections.synchronizedList(mutableListOf<UUID>())
        val c = consumer({ payload -> seen += resultIdOf(payload) }, "instance-a", pollIntervalMs = 30L)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            c.start(scope)
            withTimeoutOrNull(10_000) { while (seen.isEmpty()) delay(10) }
            assertEquals(listOf(resultId), seen.toList())
        } finally {
            c.stop()
            scope.cancel()
        }
    }

    @Test
    fun `a nudge wakes the consumer immediately`() = runBlocking {
        val pubSub = CapturingPubSub()
        val seen = java.util.Collections.synchronizedList(mutableListOf<UUID>())
        // A poll interval far longer than the test: only the nudge can deliver.
        val c = consumer(
            { payload -> seen += resultIdOf(payload) },
            "instance-a",
            pollIntervalMs = 600_000L,
            pubSub = pubSub.connection,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            c.start(scope)
            // Let the startup poll run and find nothing.
            delay(200)

            val resultId = insertEvent(UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.HOURS))
            pubSub.deliver("""{"serviceId":"${UUID.randomUUID()}"}""")

            withTimeoutOrNull(10_000) { while (seen.isEmpty()) delay(10) }
            assertEquals(listOf(resultId), seen.toList())
        } finally {
            c.stop()
            scope.cancel()
        }
    }

    // ── Shutdown ────────────────────────────────────────────────────────────

    /**
     * Stopping hands back what this instance claimed but never delivered, so a
     * rolling deploy does not leave alerts waiting out a whole lease.
     */
    @Test
    fun `stopping releases this instance's unfinished claims`() = runBlocking {
        val resultId = insertEvent(UUID.randomUUID(), Instant.now().minus(1, ChronoUnit.HOURS))

        val entered = CompletableDeferred<Unit>()
        val c = consumer(
            { entered.complete(Unit); delay(600_000) },
            "instance-a",
            pollIntervalMs = 30L,
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            c.start(scope)
            entered.await()
            assertEquals("instance-a", claimOf(resultId).first, "the row should be claimed while in flight")

            c.stop()

            val (claimedBy, claimedAt) = claimOf(resultId)
            assertNull(claimedBy, "an undelivered claim should be handed back on shutdown")
            assertNull(claimedAt)
            assertEquals(1L, unpublishedCount())
        } finally {
            scope.cancel()
        }
    }

    // ── Fakes ───────────────────────────────────────────────────────────────

    /** Lettuce pub/sub stand-in that hands the registered listener back. */
    private class CapturingPubSub {
        private var listener: RedisPubSubListener<String, String>? = null

        fun deliver(message: String) = listener?.message(OutboxConsumer.NUDGE_CHANNEL, message)

        @Suppress("UNCHECKED_CAST")
        val connection: StatefulRedisPubSubConnection<String, String> = Proxy.newProxyInstance(
            StatefulRedisPubSubConnection::class.java.classLoader,
            arrayOf(StatefulRedisPubSubConnection::class.java),
        ) { _, method: Method, args: Array<Any>? ->
            when (method.name) {
                "addListener" -> {
                    listener = args!![0] as RedisPubSubListener<String, String>
                    null
                }
                "sync" -> syncCommands
                else -> null
            }
        } as StatefulRedisPubSubConnection<String, String>

        @Suppress("UNCHECKED_CAST")
        private val syncCommands: RedisPubSubCommands<String, String> = Proxy.newProxyInstance(
            RedisPubSubCommands::class.java.classLoader,
            arrayOf(RedisPubSubCommands::class.java),
        ) { _, _: Method, _ -> null } as RedisPubSubCommands<String, String>
    }

    private fun noopPubSub(): StatefulRedisPubSubConnection<String, String> = CapturingPubSub().connection
}
