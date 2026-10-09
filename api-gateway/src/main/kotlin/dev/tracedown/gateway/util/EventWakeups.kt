package dev.tracedown.gateway.util

import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.redis.RedisFactory
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Wakes the event reads that are waiting, when rows they may want have
 * been written — so a long-poll waits on a signal, holding neither a database
 * connection nor a thread, instead of asking the database over and over.
 *
 * Two existing nudges on Redis A say so, each naming the organization:
 * [OutboxEmit.NUDGE_CHANNEL], sent after a transaction that emitted has
 * committed, and `notify:nudge`, which the result ingestor sends once a
 * result is recorded. A waiter is woken only by its own organization's.
 *
 * Nothing here is relied on for correctness. A nudge that is lost, a process
 * that writes to the outbox without sending one, a Redis that is away: the
 * reads look again on their own every few seconds (see `EventFeedController`),
 * and find the rows then.
 */
object EventWakeups {

    private val log = LoggerFactory.getLogger(EventWakeups::class.java)

    /** The result ingestor's nudge. Its message is JSON carrying `orgId`. */
    const val RESULT_NUDGE_CHANNEL = "notify:nudge"

    private val waiters = ConcurrentHashMap<UUID, MutableSet<CompletableDeferred<Unit>>>()

    @Volatile
    private var connection: StatefulRedisPubSubConnection<String, String>? = null

    /**
     * Subscribes to the nudges on [redisUrl], on a thread of its own: the
     * connect retries for a while when Redis is not there, and nothing about
     * starting the gateway should wait for that. Until it connects — or if it
     * never does — reads fall back to looking on their own.
     */
    fun start(redisUrl: String) {
        Thread({
            try {
                val conn = RedisFactory.createPubSubConnection(redisUrl)
                conn.addListener(object : RedisPubSubAdapter<String, String>() {
                    override fun message(channel: String?, message: String?) {
                        orgOf(message)?.let(::signal)
                    }
                })
                conn.sync().subscribe(OutboxEmit.NUDGE_CHANNEL, RESULT_NUDGE_CHANNEL)
                connection = conn
                log.info("event feed listening for outbox nudges")
            } catch (e: Exception) {
                log.warn("event feed is not listening for outbox nudges; reads will poll instead: {}", e.message)
            }
        }, "event-feed-wakeups").apply { isDaemon = true }.start()
    }

    /** Closes the pub/sub connection, if there is one. */
    fun stop() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }

    /**
     * A signal for [orgId], registered before the caller looks — so a nudge
     * that arrives while it is looking is not lost. [release] it when done.
     */
    fun register(orgId: UUID): CompletableDeferred<Unit> {
        val signal = CompletableDeferred<Unit>()
        // Added inside the map's own update: a release that empties the set
        // and drops it must not race an add into the set it is dropping.
        waiters.compute(orgId) { _, set -> (set ?: ConcurrentHashMap.newKeySet()).apply { add(signal) } }
        return signal
    }

    fun release(orgId: UUID, signal: CompletableDeferred<Unit>) {
        waiters.computeIfPresent(orgId) { _, set -> set.remove(signal); if (set.isEmpty()) null else set }
    }

    /** Wakes every read of [orgId] that is waiting. */
    fun signal(orgId: UUID) {
        waiters[orgId]?.forEach { it.complete(Unit) }
    }

    /** The organization a nudge names: a bare id, or JSON with `orgId`. */
    internal fun orgOf(message: String?): UUID? {
        val text = message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val raw = if (text.startsWith("{")) {
            runCatching { Json.parseToJsonElement(text).jsonObject["orgId"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        } else {
            text
        }
        return raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    }
}
