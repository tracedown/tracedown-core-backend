package dev.tracedown.gateway.util

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * The highest horizon the event feed has seen, kept outside the database (in
 * Redis A) so that a database that goes back in time is noticed.
 *
 * Transaction ids only grow in one database's life. A horizon below one seen
 * before means the database is not the one the feed was reading: restored from
 * a backup, rewound to a point in time, loaded from a dump — and every position
 * handed out since then names transactions that, here, never happened or have
 * not happened yet. Fails open: with Redis away, or the key lost, it notices
 * nothing.
 */
object FeedHighWater {

    private const val KEY = "feed:hw"

    private val log = LoggerFactory.getLogger(FeedHighWater::class.java)

    /** Raises the mark to ARGV[1] and answers what it was. */
    private const val OBSERVE = """
        local seen = tonumber(redis.call('GET', KEYS[1]) or '0')
        if tonumber(ARGV[1]) > seen then redis.call('SET', KEYS[1], ARGV[1]) end
        return seen
    """

    @Volatile
    private var redis: (() -> RedisCommands<String, String>)? = null

    fun init(redis: (() -> RedisCommands<String, String>)?) {
        this.redis = redis
    }

    /** Records [horizon], and says whether a higher one was seen before: the database went back. */
    fun wentBack(horizon: Long): Boolean {
        val commands = redis ?: return false
        return try {
            commands().eval<Long>(OBSERVE, ScriptOutputType.INTEGER, arrayOf(KEY), horizon.toString()) > horizon
        } catch (e: Exception) {
            log.debug("event feed high-water mark unavailable: {}", e.message)
            false
        }
    }

    /** Starts the mark again at [horizon], after the feed has moved to a new history. */
    fun reset(horizon: Long) {
        val commands = redis ?: return
        runCatching { commands().set(KEY, horizon.toString()) }
            .onFailure { log.debug("event feed high-water mark not reset: {}", it.message) }
    }
}
