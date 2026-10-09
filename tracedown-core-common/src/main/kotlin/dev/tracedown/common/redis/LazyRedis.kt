package dev.tracedown.common.redis

import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import java.time.Duration
import kotlin.reflect.KProperty

/**
 * A Redis connection made on first use, that never makes its caller wait for
 * a Redis that is not there.
 *
 * `by lazy { RedisFactory.createConnection(url) }` retries the connect for
 * half a minute, and — because a lazy that throws tries again next time —
 * does so on every touch while Redis is away. Touched inside a database
 * transaction (a cache put, a live update, a nudge after commit) that holds the
 * transaction, its connection and the caller for as long. This connects once
 * per attempt, and after a failure answers at once with an error for
 * [backoff] before it tries again. Callers already treat Redis as optional
 * there; they just stop being held by it.
 */
class LazyRedis(
    private val url: String,
    private val backoff: Duration = Duration.ofSeconds(30),
) {
    @Volatile
    private var connection: StatefulRedisConnection<String, String>? = null

    @Volatile
    private var failedAt: Long = 0

    /** The commands, connecting first if need be; throws at once while backing off. */
    fun commands(): RedisCommands<String, String> {
        connection?.let { return it.sync() }
        synchronized(this) {
            connection?.let { return it.sync() }
            val now = System.nanoTime()
            if (failedAt != 0L && now - failedAt < backoff.toNanos()) {
                throw IllegalStateException("redis at its last attempt was unreachable; not trying again yet")
            }
            return try {
                RedisFactory.connectOnce(url).also { connection = it }.sync()
            } catch (e: Exception) {
                failedAt = now
                throw e
            }
        }
    }

    /** As a delegate: `val redis by LazyRedis(url)` reads [commands] on every use. */
    operator fun getValue(thisRef: Any?, property: KProperty<*>): RedisCommands<String, String> = commands()

    /** Closes the connection, if one was made. */
    fun close() {
        connection?.let { runCatching { it.close() } }
        connection = null
    }
}
