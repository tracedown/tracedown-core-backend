package dev.tracedown.notifications.consumers

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.Outbox
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.v1.core.VarCharColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.ThreadLocalRandom

/**
 * Consumes outbox events for notification dispatch.
 *
 * Polls the outbox table on interval and subscribes to Redis pub/sub
 * "notify:nudge" for immediate pickup when new events are written.
 *
 * ## Why a claim, and why a lease rather than a held lock
 *
 * This consumer used to read `published = false` rows with no claim of any
 * kind — no `FOR UPDATE SKIP LOCKED`, no claim column, no cursor — and set
 * `published = true` only *after* delivery, so a row stayed visible to every
 * reader for the whole duration of the batch. The nudge that wakes the poller
 * is pub/sub, a broadcast, and the poll mutex only serialises polls within one
 * process. Two processes therefore both read the same rows and both delivered:
 * every recipient mailed twice, every bound webhook called twice. Nothing
 * errored and nothing in the data recorded it — the duplicate showed up only in
 * the inbox and in two `notification_log` rows.
 *
 * Two processes is not a hypothetical. A start-first rolling deploy runs the
 * old and new instance together for the seconds it takes the old one to drain,
 * on every release, and an operator scaling the service out gets the same thing
 * permanently.
 *
 * The obvious fix — `SELECT … FOR UPDATE SKIP LOCKED` held across delivery — is
 * the wrong shape here, because it would pin a database transaction open for
 * the whole of a delivery: a Redis push per recipient, DB work per webhook, and
 * whatever the driver is doing while a slow endpoint is resolved. A transaction
 * that lives as long as an outbound network path is a transaction that
 * eventually lives as long as an outbound network path that has stopped
 * answering.
 *
 * So the claim is a **lease**, taken and released without holding anything:
 *
 *  - [claimBatch] takes it in ONE statement that both selects and claims —
 *    `UPDATE outbox SET claimed_by, claimed_at WHERE id IN (SELECT … FOR UPDATE
 *    SKIP LOCKED) RETURNING …`. The row locks live only for that statement, and
 *    `SKIP LOCKED` is what makes two instances take disjoint sets instead of
 *    queueing behind each other.
 *  - [markPublished] releases it, by publishing the row.
 *  - A claim older than [claimLeaseSeconds] is treated as expired and the row
 *    is claimable again, so an instance that is killed mid-batch does not strand
 *    its rows.
 *
 * ## The guarantee is at-least-once. It always was.
 *
 * A crash between delivering and publishing re-delivers after the lease
 * expires; so does a lease that expires while a very slow batch is still
 * running. This is not a regression — the previous code had exactly the same
 * hole, minus the bound on how long it lasted. Nothing here claims
 * exactly-once, and nothing should be built on the assumption that it does.
 *
 * What makes at-least-once tolerable is downstream, not here: both channels are
 * idempotent per probe result. `notification_log` records every recipient and
 * webhook URL dispatched to for a result and is consulted before dispatching
 * again, so a redelivery completes what was missed and repeats nothing. What
 * that guard cannot survive is two deliveries *in flight at once*, because both
 * read the log before either writes it — which is precisely the case the claim
 * removes.
 *
 * ## Ordering: one service's events stay in sequence
 *
 * With one instance, a service's rows were handled in order because one process
 * walked them in order, and that order is load-bearing. A run's failure and the
 * recovery that follows it are two separate outbox rows;
 * [dev.tracedown.notifications.processing.NotificationProcessor] treats them
 * differently (recovery templates are platform-composed, the recovery carries
 * `downtimeSeconds`) and both pass through the same per-recipient cooldown key,
 * whose `kind` suffix and opposite-kind delete assume the failure was handled
 * before the recovery. Handle them concurrently, or backwards, and a subscriber
 * can be told a service recovered before being told it went down, or have one
 * of the two swallowed by the other's cooldown window.
 *
 * The claim therefore **never takes a row that has an older unpublished row for
 * the same service** (the `NOT EXISTS` in [claimBatch]). Consequences:
 *
 *  - At most one row per service is ever in flight, across all instances. The
 *    exclusion is on `published`, which a claim does not touch, so two
 *    instances claiming at the same instant cannot both win — the older row is
 *    unpublished either way.
 *  - A batch holds at most one row per service, so within one instance there is
 *    no same-service ordering question either.
 *  - Different services still run fully in parallel, which is where the
 *    throughput is: fan-out is across services, not within one.
 *
 * The service is read out of the payload because `aggregate_id` on a
 * `probe_result.created` row is the *result* id — unique per row, and useless
 * as an ordering key. `created_at` is the probe's start time, not the insert
 * time, so it orders rows by when the runs actually happened.
 *
 * The cost is head-of-line blocking: a row that keeps failing holds up its own
 * service's later rows. That is deliberate. Delivering a recovery while the
 * failure it recovers from is still undelivered is worse than delivering
 * neither, and it is bounded — every other service is unaffected, the stalled
 * row stays visible as an unpublished row that the outbox purge will not
 * remove, and failures on this path are almost always systemic (Redis or the
 * database unreachable) rather than specific to one row.
 *
 * ## Failure and poison rows
 *
 * A row whose processing throws is not published, exactly as before, so it is
 * retried. What is new is the spacing: its claim is deliberately **left in
 * place**, so it cannot be re-claimed until the lease expires. There is no
 * attempt counter on the outbox, and the lease is what replaces one — a row
 * that fails forever is retried every [claimLeaseSeconds] instead of every
 * [pollIntervalMs], which used to mean a hot loop every five seconds for as
 * long as the failure lasted.
 *
 * ## Everything else on the outbox is untouched
 *
 * The outbox has two consumer styles. This is the flag consumer: it filters to
 * its own event type and flips `published`. The others are cursor consumers,
 * which record an offset in `outbox_cursors` and never compete for a row — they
 * neither read nor write the claim. The outbox purge keys off `published` and
 * the cursor floor and is likewise unaffected; a claimed-but-unpublished row is
 * not eligible for deletion, which is the behaviour it already had.
 *
 * The one thing the claim must not do is look at rows of other event types:
 * `resource.*` rows are read by cursor consumers alone and are never published,
 * so a claim query that did not filter `event_type` on both sides of the
 * `NOT EXISTS` would find them permanently blocking.
 *
 * ## Scaling this service
 *
 * With the claim in place notification-dispatcher is safe to replicate. Two
 * caveats that are properties of the process, not of the outbox: each replica
 * runs its own webhook delivery pool, so [dev.tracedown.notifications.delivery.WebhookCircuitBreaker]
 * state and the queue bound are per replica; and a replica killed uncleanly
 * abandons whatever it had queued for webhook delivery, which shows up as
 * `notification_log` rows stuck at `queued`.
 */
class OutboxConsumer(
    private val processor: OutboxEventProcessor,
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val pollIntervalMs: Long,
    private val batchSize: Int,
    /** How long a claim is honoured before the row is considered abandoned. */
    private val claimLeaseSeconds: Long = DEFAULT_CLAIM_LEASE_SECONDS,
    /**
     * Value written to `claimed_by`. Stable for the life of the process.
     *
     * Diagnostic only — mutual exclusion comes from the claiming statement, not
     * from this value, so nothing breaks if two processes ever share one. It is
     * hostname plus a random suffix rather than the hostname alone because two
     * processes on one host is the ordinary case (a rolling deploy), and a
     * `claimed_by` that cannot tell them apart is a `claimed_by` that cannot
     * answer the only question it is there to answer.
     */
    private val instanceId: String = defaultInstanceId(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null
    private val pollMutex = kotlinx.coroutines.sync.Mutex()

    /** Starts the consumer loop and Redis nudge subscription. */
    fun start(scope: CoroutineScope) {
        // Subscribe to nudge channel for immediate pickup
        pubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String?, message: String?) {
                log.debug("received nudge on channel={}", channel)
                job?.let { scope.launch { pollAndProcess() } }
            }
        })
        pubSubConnection.sync().subscribe(NUDGE_CHANNEL)

        // Start poll loop
        job = scope.launch {
            log.info(
                "outbox consumer started (instance={}, poll={}ms, batch={}, lease={}s)",
                instanceId, pollIntervalMs, batchSize, claimLeaseSeconds,
            )
            while (isActive) {
                try {
                    pollAndProcess()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("outbox consumer error: {}", e.message, e)
                }
                delay(pollIntervalMs)
            }
        }
    }

    /**
     * Stops the consumer loop, unsubscribes from Redis, and hands back whatever
     * this instance had claimed but not delivered.
     *
     * Releasing on the way out is what keeps a rolling deploy quick: without it
     * the departing instance's last claims sit for a whole lease before the new
     * one may touch them, and those rows are alerts. It is only safe once the
     * poll loop has actually stopped — releasing a row that is still being
     * delivered is the double-delivery this class exists to prevent — so the
     * release is skipped unless the loop is confirmed finished. The join is
     * bounded because shutdown must not hang on a database that has already
     * gone away; on a timeout the claims simply expire on their own, which is
     * the case the lease is for.
     */
    fun stop() {
        try {
            pubSubConnection.sync().unsubscribe(NUDGE_CHANNEL)
        } catch (_: Exception) {
            // best-effort cleanup
        }

        val settled = runBlocking {
            val j = job ?: return@runBlocking true
            j.cancel()
            withTimeoutOrNull(STOP_JOIN_TIMEOUT_MS) { j.join() } != null
        }
        if (settled) releaseClaims() else log.warn("poll loop did not stop in time — leaving claims to expire")
    }

    private suspend fun pollAndProcess() {
        if (!pollMutex.tryLock()) return // skip if another poll is already running
        try {
            drain()
        } finally {
            pollMutex.unlock()
        }
    }

    /**
     * Claims and processes until there is nothing claimable left, or the round
     * budget runs out.
     *
     * One claim cannot be the whole of a poll any more: the ordering rule caps a
     * batch at one row per service, so a backlog — the dispatcher restarting
     * after being down, say — would otherwise drain at one row per service per
     * [pollIntervalMs]. Looping while there is work makes catch-up proportional
     * to the backlog instead of to the poll interval.
     *
     * The budget bounds one poll so the loop cannot monopolise the coroutine or
     * outrun cancellation. It cannot spin: a claimed row is excluded for the
     * length of its lease whether it succeeded (published) or failed (claim
     * retained), so every round either makes progress or comes back empty.
     */
    private suspend fun drain() {
        var round = 0
        while (round < MAX_ROUNDS_PER_POLL) {
            if (!claimAndProcessOnce()) return
            round++
        }
        log.debug("drain budget of {} rounds reached — continuing on the next poll", MAX_ROUNDS_PER_POLL)
    }

    /**
     * One claim → process → publish round. Returns false when nothing was
     * claimable. Internal so tests can step two instances against one database
     * without a Redis nudge or a poll interval in the way.
     */
    internal suspend fun claimAndProcessOnce(): Boolean {
        val events = claimBatch()
        if (events.isEmpty()) return false

        log.debug("processing {} outbox events", events.size)

        val processedIds = mutableListOf<UUID>()
        try {
            for ((id, payload) in events) {
                try {
                    processor.process(payload)
                    processedIds.add(id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Left claimed on purpose: the lease is the retry backoff.
                    log.error("failed to process outbox event {}: {}", id, e.message, e)
                }
            }
        } finally {
            // Publish what was delivered even when the batch is being
            // cancelled. A shutdown mid-batch would otherwise turn completed
            // deliveries into redeliveries for whoever picks the rows up next.
            if (processedIds.isNotEmpty()) {
                withContext(NonCancellable) { markPublished(processedIds) }
            }
        }
        return true
    }

    /**
     * Claims the next batch of unpublished events for this instance and returns
     * them, oldest first.
     *
     * One statement selects and claims, so no row is ever visible as work to a
     * second instance. `SKIP LOCKED` makes concurrent claimers take disjoint
     * sets. The `NOT EXISTS` is the ordering rule — see the class notes.
     *
     * `claimLeaseSeconds` and `batchSize` are numeric values under our control
     * and are inlined; the instance id is a parameter.
     */
    private suspend fun claimBatch(): List<Pair<UUID, JsonObject>> = ioTransaction {
        val sql = """
            WITH claimed AS (
                UPDATE outbox
                   SET claimed_by = ?, claimed_at = now()
                 WHERE id IN (
                       SELECT o.id
                         FROM outbox o
                        WHERE o.published = false
                          AND o.event_type = '$EVENT_TYPE'
                          AND (o.claimed_at IS NULL
                               OR o.claimed_at < now() - make_interval(secs => $claimLeaseSeconds))
                          AND NOT EXISTS (
                              SELECT 1
                                FROM outbox e
                               WHERE e.published = false
                                 AND e.event_type = '$EVENT_TYPE'
                                 AND e.payload ->> 'serviceId' = o.payload ->> 'serviceId'
                                 AND (e.created_at, e.seq) < (o.created_at, o.seq)
                          )
                        ORDER BY o.created_at, o.seq
                        LIMIT $batchSize
                        FOR UPDATE SKIP LOCKED
                       )
             RETURNING id, payload, created_at, seq
            )
            SELECT id, payload FROM claimed ORDER BY created_at, seq
        """.trimIndent()

        val claimed = mutableListOf<Pair<UUID, JsonObject>>()
        // The statement opens with a data-modifying CTE. Exposed reads the type
        // off the leading keyword and would run this through executeUpdate,
        // throwing on the rows it gets back.
        exec(
            sql,
            listOf(VarCharColumnType() to instanceId),
            explicitStatementType = StatementType.SELECT,
        ) { rs ->
            while (rs.next()) {
                claimed += (rs.getObject("id") as UUID) to
                    Json.parseToJsonElement(rs.getString("payload")).jsonObject
            }
        }
        claimed
    }

    /**
     * Marks delivered rows published, which is also what releases their claim.
     *
     * Not conditioned on still holding the claim: if the lease expired while
     * this instance was delivering, the delivery still happened, and a delivered
     * row belongs published. The `claimed_by`/`claimed_at` values are left as
     * they are, so a published row still records who delivered it.
     */
    private suspend fun markPublished(ids: List<UUID>) {
        ioTransaction {
            Outbox.update({ Outbox.id inList ids }) {
                it[published] = true
            }
        }
        log.debug("marked {} outbox events as published", ids.size)
    }

    /** Clears this instance's claims on rows it never delivered. Best effort. */
    private fun releaseClaims() {
        try {
            val released = transaction {
                Outbox.update({
                    (Outbox.claimedBy eq instanceId) and (Outbox.published eq false)
                }) {
                    it[claimedBy] = null
                    it[claimedAt] = null
                }
            }
            if (released > 0) log.info("released {} unfinished outbox claims on shutdown", released)
        } catch (e: Exception) {
            log.warn("could not release outbox claims on shutdown: {}", e.message)
        }
    }

    internal companion object {
        /** The only event type this consumer handles. */
        const val EVENT_TYPE = "probe_result.created"

        const val NUDGE_CHANNEL = "notify:nudge"

        /**
         * Default lease, in seconds.
         *
         * It has to outlast the longest legitimate hold, which is one whole
         * batch: rows are claimed together and published together, so a claim
         * lives until the last event of its batch is done. Nothing on that path
         * sleeps — webhook retry backoff (2s/8s/32s on the default ladder) runs
         * on the delivery workers, *after* `deliver` returns, and never inside
         * the claim — so a batch is a few database round trips and a Redis push
         * per recipient, per event. At the default batch size of 50 this allows
         * well over two seconds an event, which is a large multiple of what it
         * costs in practice.
         *
         * Longer is not free: it is also how long a killed instance's rows wait
         * before another one may deliver them, and how long a permanently
         * failing row blocks its own service. Two minutes keeps both within the
         * window an alert can absorb. Raise it if `batchSize` is raised.
         */
        const val DEFAULT_CLAIM_LEASE_SECONDS = 120L

        /** Claim rounds one poll may run before yielding to the interval. */
        private const val MAX_ROUNDS_PER_POLL = 10

        /** How long [stop] waits for the poll loop before giving up on it. */
        private const val STOP_JOIN_TIMEOUT_MS = 5_000L

        /** Hostname plus a random per-process suffix; see the constructor. */
        fun defaultInstanceId(): String {
            val host = runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: System.getenv("HOSTNAME")?.takeIf { it.isNotBlank() }
                ?: "unknown"
            return "%s-%08x".format(host.take(110), ThreadLocalRandom.current().nextInt())
        }
    }
}
