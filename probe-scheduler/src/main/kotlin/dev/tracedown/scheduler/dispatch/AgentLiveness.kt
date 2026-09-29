package dev.tracedown.scheduler.dispatch

import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * When each agent last handed back a probe result of its own.
 *
 * The health challenge is one narrow path — a cold mTLS handshake plus a fetch
 * back to the gateway — and it can time out for a far agent while that same
 * agent is running probes perfectly well. Convicting on the challenge alone
 * took Singapore agents out of rotation while their dispatched runs were
 * succeeding in the same hour. This is the other witness: a run the agent
 * answered is proof it is alive, whatever the challenge saw.
 *
 * Kept in Redis A rather than in memory so every scheduler replica sees the
 * same evidence, and so it survives a restart; an agent that has produced
 * nothing within [TTL_SECONDS] simply has no alibi, as before.
 */
class AgentLiveness(private val redis: RedisCommands<String, String>) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val KEY_PREFIX = "agent_seen:"

        /** Long enough to outlive any run budget plus the two rounds a conviction takes. */
        const val TTL_SECONDS = 3600L
    }

    /** Records that [agentId] answered a dispatch with a result of its own at [at]. */
    fun markSeen(agentId: Long, at: Instant = Instant.now()) {
        try {
            redis.set("$KEY_PREFIX$agentId", at.toEpochMilli().toString(), SetArgs().ex(TTL_SECONDS))
        } catch (e: Exception) {
            // Evidence, not a dependency: a dispatch never fails over this.
            log.debug("could not record liveness for agent {}: {}", agentId, e.message)
        }
    }

    /** When [agentId] last answered a dispatch, or null when nothing recent is known. */
    fun lastSeen(agentId: Long): Instant? = try {
        redis.get("$KEY_PREFIX$agentId")?.toLongOrNull()?.let(Instant::ofEpochMilli)
    } catch (e: Exception) {
        log.debug("could not read liveness for agent {}: {}", agentId, e.message)
        null
    }

    /** Whether [agentId] answered a dispatch strictly after [since]. */
    fun seenSince(agentId: Long, since: Instant): Boolean = lastSeen(agentId)?.isAfter(since) ?: false
}
