package dev.tracedown.ingestor.consumers

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.ingestor.services.ResultPersistenceService
import io.lettuce.core.KeyScanCursor
import io.lettuce.core.LMoveArgs
import io.lettuce.core.ScanArgs
import io.lettuce.core.ScanCursor
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Consumes probe results from the Redis queue and records them.
 *
 * ## Why this is not a plain pop
 *
 * A probe result is the only record that a check ran. Once the scheduler has
 * queued it, nothing will ever produce it again — the run is over, the target
 * has moved on. A pop that removes the message and *then* persists it is
 * therefore a window in which a database failover, an exhausted pool or a
 * stalled object store destroys monitoring history permanently, including the
 * failure result that should have woken someone.
 *
 * So the message is not removed on the way out. It is **moved**, atomically,
 * from the queue to this consumer's own processing list ([ProcessingListReclaim]),
 * and removed from there only once the result is in the database. Delivery is
 * at-least-once, which is safe because persistence is idempotent by the
 * envelope's `resultId` — see [ResultPersistenceService].
 *
 * ## What happens when things go wrong
 *
 * - **A transient fault** (failover, pool exhaustion, storage stall) is retried
 *   in place, riding out about half a minute without the message leaving the
 *   consumer, and then handed back to the *back* of the queue so the pipeline
 *   keeps moving while the fault persists. It is never discarded.
 * - **A poison message** — one that cannot be parsed or cannot satisfy the
 *   schema — is dead-lettered on the first failure. Retrying it forever would
 *   stall every result behind it, which is a worse outage than one lost result.
 * - **A hard kill mid-processing** leaves the message in this consumer's
 *   processing list. Its heartbeat expires within
 *   [ProcessingListReclaim.HEARTBEAT_TTL_SECONDS], and the next sweep — by any
 *   replica, or by this process after it restarts under a new id — moves it
 *   back onto the queue.
 *
 * [IngestFailurePolicy] holds the rules; this class holds the plumbing.
 *
 * @param redis a connection dedicated to this consumer — the pop is blocking,
 *   and a blocking command holds up everything else pipelined behind it
 */
class ProbeResultConsumer(
    private val redis: RedisCommands<String, String>,
    private val popTimeoutSeconds: Long,
    /** Identity of this consumer process. Never reused: a restart is a new consumer. */
    private val consumerId: String = UUID.randomUUID().toString(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private var job: Job? = null
    private var heartbeatJob: Job? = null
    private var reclaimJob: Job? = null

    private val processingKey = ProcessingListReclaim.processingKey(consumerId)
    private val heartbeatKey = ProcessingListReclaim.heartbeatKey(consumerId)

    companion object {
        const val QUEUE_KEY = "probe_results_queue"

        /**
         * Where a result that cannot be recorded is set aside. Nothing drains
         * it automatically: an entry here is a result the platform lost, and a
         * person has to decide what to do about it. [deadLetter] is what makes
         * sure they hear about it.
         */
        const val DLQ_KEY = "probe_results_dlq"

        /**
         * The dead-letter queue is capped: it is a diagnosis aid, not storage,
         * and a payload storm must not be able to push the operational queue
         * out of memory. Oldest entries are dropped first.
         */
        const val DLQ_MAX_ENTRIES = 10_000L

        /** How often abandoned processing lists are swept for. */
        const val RECLAIM_INTERVAL_SECONDS = 30L

        /** Safety stop on one reclaim pass, so a huge list cannot pin the sweeper. */
        private const val RECLAIM_BATCH_LIMIT = 10_000
    }

    /** Starts the consumer loop, its heartbeat, and the orphan sweep. */
    fun start(scope: CoroutineScope) {
        // Both before the first message is taken in hand: the heartbeat so no
        // other replica mistakes this consumer for a dead one, and the sweep so
        // a restart picks up whatever the previous process was holding when it
        // was killed.
        beat()
        reclaimOrphans()

        heartbeatJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(ProcessingListReclaim.HEARTBEAT_REFRESH_SECONDS * 1000)
                beat()
            }
        }
        reclaimJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(RECLAIM_INTERVAL_SECONDS * 1000)
                reclaimOrphans()
            }
        }
        job = scope.launch(Dispatchers.IO) {
            log.info("consumer {} started, pop timeout={}s", consumerId, popTimeoutSeconds)
            while (isActive) {
                try {
                    consumeOne()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("consumer error: {}", e.message, e)
                    delay(1000)
                }
            }
        }
    }

    /**
     * Stops the consumer loop.
     *
     * Anything still in hand is put back on the queue rather than left for the
     * heartbeat to expire, so a rolling restart hands its work over at once.
     * The heartbeat key goes last: until it does, no other replica will touch
     * this consumer's list.
     */
    fun stop() {
        job?.cancel()
        heartbeatJob?.cancel()
        reclaimJob?.cancel()
        try {
            runBlocking { withTimeoutOrNull((popTimeoutSeconds + 2) * 1000) { job?.join() } }
        } catch (e: Exception) {
            log.debug("consumer {} did not stop cleanly: {}", consumerId, e.message)
        }
        try {
            val moved = drainToQueue(processingKey)
            if (moved > 0) log.info("consumer {} handed back {} in-flight result(s) on shutdown", consumerId, moved)
            redis.del(heartbeatKey)
        } catch (e: Exception) {
            // The heartbeat expiring achieves the same thing, a minute later.
            log.warn("consumer {} could not hand back its in-flight work: {}", consumerId, e.message)
        }
    }

    /**
     * Blocks until a message is available, then records it.
     * Returns without action if the pop times out.
     */
    private suspend fun consumeOne() {
        // Atomic take: the message is in the processing list before it is out of
        // the queue, so there is no instant in which it exists nowhere.
        val raw = redis.blmove(
            QUEUE_KEY,
            processingKey,
            LMoveArgs.Builder.rightLeft(),
            popTimeoutSeconds.toDouble(),
        ) ?: return // timeout, no message

        handle(raw)
    }

    /**
     * Records one message, retrying and finally requeueing or dead-lettering it
     * per [IngestFailurePolicy]. Returns only once the message is no longer this
     * consumer's responsibility.
     */
    private suspend fun handle(raw: String) {
        val deliveriesMade = deliveriesOf(raw)
        var attemptsMade = 1
        while (true) {
            try {
                val envelope = Json.parseToJsonElement(raw).jsonObject
                val outcome = ResultPersistenceService.persist(envelope)
                // A redelivery must not nudge again: the live counters
                // downstream would count one run twice.
                if (outcome == ResultPersistenceService.PersistOutcome.PERSISTED) publishNudge(envelope)
                ack(raw)
                return
            } catch (e: CancellationException) {
                // Shutdown. The message stays in the processing list and is
                // handed back by stop(), or reclaimed if this process dies now.
                throw e
            } catch (e: Exception) {
                val verdict = IngestFailurePolicy.classify(e)
                if (IngestFailurePolicy.mayRetryInProcess(verdict, attemptsMade)) {
                    val backoff = IngestFailurePolicy.backoffMs(attemptsMade)
                    log.warn(
                        "failed to record result ({}, attempt {}/{}): {} — retrying in {}ms",
                        verdict, attemptsMade, IngestFailurePolicy.MAX_IN_PROCESS_ATTEMPTS,
                        e.message, backoff,
                    )
                    delay(backoff)
                    attemptsMade++
                    continue
                }
                if (IngestFailurePolicy.deadLetter(verdict, deliveriesMade)) {
                    deadLetter(raw, verdict, deliveriesMade, e)
                } else {
                    requeue(raw, deliveriesMade, verdict, e)
                }
                return
            }
        }
    }

    /** Removes a finished message from this consumer's processing list. */
    private fun ack(raw: String) {
        redis.lrem(processingKey, 1, raw)
    }

    /**
     * Hands a message back to the queue for a later attempt.
     *
     * It goes on the **producer's end**, behind everything already waiting, on
     * purpose: a fault that affects one message affects the whole backlog, and
     * putting it back at the front would spin on it while nothing else moved.
     * The push happens before the removal, so a crash in between duplicates the
     * message rather than losing it — which the `resultId` key then absorbs.
     */
    private suspend fun requeue(raw: String, deliveriesMade: Int, verdict: IngestFailurePolicy.Verdict, cause: Exception) {
        val next = deliveriesMade + 1
        val payload = withDeliveryCount(raw, next)
        try {
            redis.lpush(QUEUE_KEY, payload)
            redis.lrem(processingKey, 1, raw)
        } catch (e: Exception) {
            // Redis itself is unavailable. The message stays in the processing
            // list, which is exactly where an abandoned message belongs — the
            // reclaim sweep will find it.
            log.error("could not hand result back to the queue: {}", e.message)
            return
        }
        val pause = IngestFailurePolicy.requeueDelayMs(next)
        log.warn(
            "result could not be recorded ({}, delivery {}): {} — requeued, pausing {}ms",
            verdict, deliveriesMade, cause.message, pause,
        )
        delay(pause)
    }

    /**
     * Sets a message aside on the dead-letter queue and makes sure somebody
     * hears about it.
     *
     * Three ways, because a queue nobody looks at is the same as a discard:
     * an `error`-level log line naming the key and the depth; a system alert to
     * the owning org, which is a banner in the product, so the gap in the
     * service's history has a visible cause; and the entry itself, which keeps
     * the original payload verbatim under `payload` so an operator can put it
     * back on the queue with a single `LPUSH` once the cause is fixed.
     */
    private fun deadLetter(raw: String, verdict: IngestFailurePolicy.Verdict, deliveriesMade: Int, cause: Exception) {
        val envelope = runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
        val detail = (cause.message ?: cause::class.java.simpleName).take(DETAIL_MAX_CHARS)
        val entry = buildJsonObject {
            put("deadLetteredAt", Instant.now().toString())
            put("consumerId", consumerId)
            put("verdict", verdict.name)
            put("deliveries", deliveriesMade)
            put("error", detail)
            envelope?.get("resultId")?.jsonPrimitive?.contentOrNull?.let { put("resultId", it) }
            envelope?.get("serviceId")?.jsonPrimitive?.contentOrNull?.let { put("serviceId", it) }
            // Verbatim, so re-driving it is a copy back onto the queue.
            put("payload", raw)
        }

        val depth = try {
            redis.lpush(DLQ_KEY, entry.toString())
            redis.ltrim(DLQ_KEY, 0, DLQ_MAX_ENTRIES - 1)
            redis.lrem(processingKey, 1, raw)
            redis.llen(DLQ_KEY)
        } catch (e: Exception) {
            log.error("could not dead-letter an unrecordable result: {}", e.message)
            0L
        }

        log.error(
            "DEAD-LETTERED a probe result ({}, {} deliveries): {} — set aside on {} (depth {}); " +
                "the run happened and has no history row",
            verdict, deliveriesMade, detail, DLQ_KEY, depth,
        )

        val orgId = envelope?.get("organizationId")?.jsonPrimitive?.contentOrNull
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        if (orgId == null) {
            // A payload too damaged to name an org cannot raise a banner for
            // one. The log line above is the only signal in that case.
            log.error("the dead-lettered result names no organization — it can only be found on {}", DLQ_KEY)
            return
        }

        val data = buildJsonObject {
            put("reason", verdict.name.lowercase())
            put("error", detail)
            put("queue", DLQ_KEY)
            put("depth", depth)
            envelope["serviceId"]?.jsonPrimitive?.contentOrNull?.let { put("serviceId", it) }
        }
        val ctx = AlertContext(
            alertType = SystemAlertService.RESULT_INGEST_FAILED,
            subject = "",
            orgId = orgId,
            // The lost result is that org's own history, so it is theirs to be
            // told about even where a host redirects shared-infra alerts.
            orgScoped = true,
            severity = "error",
            data = data,
        )
        if (!SystemAlertRouting.handled(ctx)) {
            SystemAlertService.raise(
                orgId = orgId,
                alertType = SystemAlertService.RESULT_INGEST_FAILED,
                severity = "error",
                data = data,
            )
        }
    }

    /** Refreshes this consumer's presence key. Its absence is what declares death. */
    private fun beat() {
        try {
            redis.set(heartbeatKey, Instant.now().toString(), SetArgs.Builder.ex(ProcessingListReclaim.HEARTBEAT_TTL_SECONDS))
        } catch (e: Exception) {
            // A missed beat is survivable — the TTL allows several. What it must
            // not do is take the consumer down.
            log.warn("consumer {} could not refresh its heartbeat: {}", consumerId, e.message)
        }
    }

    /**
     * Moves messages abandoned by dead consumers back onto the queue.
     *
     * This is the path that makes a hard kill survivable: the killed process
     * left its in-flight message in a list nobody reads, and nothing else in the
     * system would ever look there.
     */
    private fun reclaimOrphans() {
        try {
            val processingKeys = scanProcessingKeys()
            if (processingKeys.isEmpty()) return
            val live = processingKeys
                .mapNotNull { ProcessingListReclaim.consumerIdOf(it) }
                .filter { redis.exists(ProcessingListReclaim.heartbeatKey(it)) > 0 }
                .toSet()
            for (orphan in ProcessingListReclaim.orphaned(processingKeys, live, consumerId)) {
                val moved = drainToQueue(orphan)
                if (moved > 0) {
                    log.warn(
                        "reclaimed {} in-flight probe result(s) from consumer {} — it is no longer alive; requeued",
                        moved, ProcessingListReclaim.consumerIdOf(orphan),
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("orphan sweep failed: {}", e.message)
        }
    }

    /**
     * Moves every message in a processing list back onto the queue, and returns
     * how many.
     *
     * These go on the **consumer's end** — unlike a requeue after a transient
     * fault. They are older than everything waiting, and there is no reason to
     * make them wait again. Taking the newest first and appending each in turn
     * leaves the oldest at the front of the line.
     */
    private fun drainToQueue(listKey: String): Int {
        var moved = 0
        while (moved < RECLAIM_BATCH_LIMIT) {
            redis.lmove(listKey, QUEUE_KEY, LMoveArgs.Builder.leftRight()) ?: break
            moved++
        }
        return moved
    }

    /** Every processing list currently in Redis, found without blocking on KEYS. */
    private fun scanProcessingKeys(): List<String> {
        val found = mutableListOf<String>()
        var cursor: KeyScanCursor<String> = redis.scan(
            ScanArgs.Builder.matches(ProcessingListReclaim.PROCESSING_PREFIX + "*").limit(SCAN_BATCH),
        )
        found += cursor.keys
        while (!cursor.isFinished) {
            cursor = redis.scan(
                ScanCursor.of(cursor.cursor),
                ScanArgs.Builder.matches(ProcessingListReclaim.PROCESSING_PREFIX + "*").limit(SCAN_BATCH),
            )
            found += cursor.keys
        }
        return found
    }

    /**
     * How many times this message has been delivered, this delivery included.
     *
     * Carried on the envelope so it survives the consumer that failed on it.
     * Never throws: a payload too broken to read a counter out of is a poison
     * message, and the classification path deals with it.
     */
    private fun deliveriesOf(raw: String): Int = runCatching {
        Json.parseToJsonElement(raw).jsonObject["ingestDeliveries"]?.jsonPrimitive?.intOrNull ?: 1
    }.getOrDefault(1)

    /** The same message with its delivery counter set, or unchanged if unreadable. */
    private fun withDeliveryCount(raw: String, deliveries: Int): String = runCatching {
        val envelope = Json.parseToJsonElement(raw).jsonObject
        JsonObject(envelope + ("ingestDeliveries" to JsonPrimitive(deliveries))).toString()
    }.getOrDefault(raw)

    /**
     * Best-effort low-latency signal to notification-dispatcher and
     * metrics-service. Unlike the outbox event written inside the persistence
     * transaction, losing one of these costs a delayed refresh, not a record.
     */
    private fun publishNudge(envelope: JsonObject) {
        val rawResult = envelope["rawResult"]?.jsonObject
        val outcome = rawResult?.get("outcome")?.jsonPrimitive?.content ?: "error"
        val status = when (outcome) {
            "success" -> "success"
            "failure" -> "failure"
            "timeout" -> "timeout"
            "skipped" -> "skipped"
            else -> "error"
        }

        // Skipped probes are history-only: a tick that never ran belongs in
        // neither the numerator nor the denominator of anything, so no
        // nudge, no metrics, no notifications.
        //
        // An errored run does get one. It is a real result row now, and the
        // DB metrics backfill counts it in the probe total (it just counts
        // toward no success bucket) — withholding the nudge would leave the
        // live Redis counters permanently disagreeing with that backfill
        // for any service whose script is broken.
        if (status == "skipped") return

        val calls = rawResult?.get("calls")?.jsonArray
        val nudgePayload = buildJsonObject {
            put("orgId", envelope["organizationId"]?.jsonPrimitive?.content ?: "")
            put("workspaceId", envelope["workspaceId"]?.jsonPrimitive?.content ?: "")
            put("projectId", envelope["projectId"]?.jsonPrimitive?.content ?: "")
            put("serviceId", envelope["serviceId"]?.jsonPrimitive?.content ?: "")
            put("status", status)
            put("totalResponseMs", calls
                ?.sumOf { call ->
                    val resp = call.jsonObject["response"]
                    if (resp is JsonObject) resp["responseTimeMs"]?.jsonPrimitive?.intOrNull ?: 0 else 0
                } ?: 0)
            put("callCount", calls?.size ?: 0)
            // Measured HTTP-layer usage (agent-supplied) for usage counters.
            put("ingressBytes", rawResult?.get("ingressBytes")?.jsonPrimitive?.longOrNull ?: 0L)
            put("egressBytes", rawResult?.get("egressBytes")?.jsonPrimitive?.longOrNull ?: 0L)
            // Bytes dispatched to the agent for this run — measured by the
            // scheduler and carried on the envelope, not inside rawResult.
            put("agentEgressBytes", envelope["agentEgressBytes"]?.jsonPrimitive?.longOrNull ?: 0L)
            // A call is failed when an assertion failed OR it errored
            // before assertions could run (DNS/connect/timeout).
            put("failedCalls", calls?.count { call ->
                val obj = call.jsonObject
                val errored = obj["error"] != null && obj["error"] !is JsonNull
                errored || obj["assertions"]?.jsonArray?.any { a ->
                    a.jsonObject["outcome"]?.jsonPrimitive?.content == "failed"
                } == true
            } ?: 0)
            // Failed-assertion details so live clients can update the
            // service's failure preview without a refetch (capped).
            val failedAssertions = buildJsonArray {
                var added = 0
                for (call in calls ?: emptyList()) {
                    val assertions = call.jsonObject["assertions"]?.jsonArray ?: continue
                    for (assertion in assertions) {
                        if (added >= 5) break
                        val obj = assertion.jsonObject
                        if (obj["outcome"]?.jsonPrimitive?.contentOrNull != "failed") continue
                        add(buildJsonObject {
                            put("scope", obj["scope"]?.jsonPrimitive?.contentOrNull ?: "unknown")
                            put("expected", obj["expected"]?.jsonPrimitive?.contentOrNull)
                            put("actual", obj["actual"]?.jsonPrimitive?.contentOrNull)
                        })
                        added++
                    }
                }
            }
            if (failedAssertions.isNotEmpty()) put("failedAssertions", failedAssertions)
        }
        try {
            redis.publish("notify:nudge", nudgePayload.toString())
        } catch (e: Exception) {
            // The result is already recorded and the outbox event already
            // written; the durable path is intact. Failing the message over a
            // best-effort signal would only redeliver a result that is in the
            // database.
            log.warn("could not publish the downstream nudge: {}", e.message)
        }
    }
}

/** Keys per SCAN round trip. */
private const val SCAN_BATCH = 200L

/** Enough of a failure to identify it, short enough to keep out of a banner's way. */
private const val DETAIL_MAX_CHARS = 300
