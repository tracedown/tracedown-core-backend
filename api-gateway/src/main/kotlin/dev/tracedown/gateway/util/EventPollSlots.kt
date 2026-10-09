package dev.tracedown.gateway.util

import io.lettuce.core.ScriptOutputType
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

/**
 * How many event reads may be open at once: [PER_KEY] for one key,
 * [PER_USER] for all the keys of one user, [PER_ORG] for all the keys of one
 * organization — each across every gateway process — and [PER_PROCESS] in
 * one process for everyone together, of which one organization may hold at
 * most [PER_ORG].
 *
 * A long-poll costs nothing while it waits, but it does keep a request open,
 * and a client that opened them in a loop (or minted keys to open more) would
 * hold an unbounded number. The shared bounds live in Redis A: a sorted set
 * per key, user and organization of the reads open there, each scored with
 * the moment it must have ended by, so a read whose process died stops
 * counting at that moment; one script checks and takes all three at once.
 * When Redis does not answer, each process holds a key to [PER_KEY] on its
 * own instead — never no bound at all.
 */
object EventPollSlots {

    const val PER_KEY = 2
    const val PER_USER = 6
    const val PER_ORG = 24
    const val PER_PROCESS = 512

    private val log = LoggerFactory.getLogger(EventPollSlots::class.java)

    private val process = Semaphore(PER_PROCESS)

    /** Reads open per key in this process, for when Redis is not there to count them. */
    private val local = ConcurrentHashMap<UUID, Int>()

    /** Reads open per organization in this process. */
    private val localOrgs = ConcurrentHashMap<UUID, Int>()

    /** Which bound refused a read: what `details.bound` names. */
    enum class Bound(val wire: String) { KEY("key"), USER("user"), ORG("org"), PROCESS("process") }

    /** A slot, or the bound that refused one. */
    sealed interface Outcome {
        class Held(val slot: Slot) : Outcome
        class Refused(val bound: Bound) : Outcome
    }

    private fun take(counts: ConcurrentHashMap<UUID, Int>, id: UUID, max: Int): Boolean {
        var taken = false
        counts.compute(id) { _, n ->
            val open = n ?: 0
            if (open >= max) open else (open + 1).also { taken = true }
        }
        return taken
    }

    private fun give(counts: ConcurrentHashMap<UUID, Int>, id: UUID) {
        counts.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } }
    }

    @Volatile
    private var redis: (() -> RedisCommands<String, String>)? = null

    /** Injects Redis A. Unset, the bounds are this process's own. */
    fun init(redis: (() -> RedisCommands<String, String>)?) {
        this.redis = redis
    }

    /** What [init] was given, for tests that take Redis away and give it back. */
    internal fun provider(): (() -> RedisCommands<String, String>)? = redis

    /**
     * For each of KEYS (key, user, organization) with its bound in ARGV[3..5]:
     * drop the reads past their deadline, and refuse when it is full. Only
     * when none is full is the read added to all three. Atomic in Redis.
     */
    private const val ACQUIRE = """
        local now = ARGV[1]
        for i = 1, 3 do
            redis.call('ZREMRANGEBYSCORE', KEYS[i], '-inf', now)
            if redis.call('ZCARD', KEYS[i]) >= tonumber(ARGV[2 + i]) then return -i end
        end
        for i = 1, 3 do
            redis.call('ZADD', KEYS[i], ARGV[2], ARGV[6])
            local last = redis.call('ZRANGE', KEYS[i], -1, -1, 'WITHSCORES')
            redis.call('PEXPIREAT', KEYS[i], last[2])
        end
        return 1
    """

    /** A held slot. [close] gives it back. */
    class Slot internal constructor(
        private val keys: List<String>,
        private val token: String?,
        private val localKey: UUID?,
        private val orgId: UUID,
    ) : AutoCloseable {
        override fun close() {
            process.release()
            give(localOrgs, orgId)
            localKey?.let { give(local, it) }
            val commands = redis ?: return
            if (token == null) return
            try {
                val sync = commands()
                keys.forEach { sync.zrem(it, token) }
            } catch (e: Exception) {
                // Each expires on its own at the score it was given.
                log.debug("event read slot not given back: {}", e.message)
            }
        }
    }

    /**
     * A slot for one read of [keyId] (acting as [userId] in [orgId]) that will
     * be over within [holdMillis], or the bound that refused it.
     */
    fun tryAcquire(keyId: UUID, userId: UUID, orgId: UUID, holdMillis: Long): Outcome {
        if (!process.tryAcquire()) return refused(Bound.PROCESS, keyId)
        if (!take(localOrgs, orgId, PER_ORG)) {
            process.release()
            return refused(Bound.ORG, keyId)
        }
        val commands = redis
        if (commands != null) {
            val keys = listOf("events:polls:key:$keyId", "events:polls:user:$userId", "events:polls:org:$orgId")
            val now = System.currentTimeMillis()
            val token = UUID.randomUUID().toString()
            val answer = try {
                commands().eval<Long>(
                    ACQUIRE, ScriptOutputType.INTEGER, keys.toTypedArray(),
                    now.toString(), (now + holdMillis).toString(),
                    PER_KEY.toString(), PER_USER.toString(), PER_ORG.toString(), token,
                )
            } catch (e: Exception) {
                log.debug("event read slots unavailable in Redis, bounding key {} in this process: {}", keyId, e.message)
                null
            }
            when (answer) {
                1L -> return Outcome.Held(Slot(keys, token, null, orgId))
                null -> Unit
                else -> {
                    process.release()
                    give(localOrgs, orgId)
                    return refused(listOf(Bound.KEY, Bound.USER, Bound.ORG)[(-answer - 1).toInt().coerceIn(0, 2)], keyId)
                }
            }
        }
        // Redis is not there to count: this process bounds the key alone.
        if (!take(local, keyId, PER_KEY)) {
            process.release()
            give(localOrgs, orgId)
            return refused(Bound.KEY, keyId)
        }
        return Outcome.Held(Slot(emptyList(), null, keyId, orgId))
    }

    private fun refused(bound: Bound, keyId: UUID): Outcome {
        log.info("event read of key {} refused: the {} bound is reached", keyId, bound.wire)
        return Outcome.Refused(bound)
    }
}
