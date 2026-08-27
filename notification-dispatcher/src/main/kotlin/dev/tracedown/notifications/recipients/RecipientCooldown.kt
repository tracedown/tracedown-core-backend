package dev.tracedown.notifications.recipients

import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Anti-storm cooldown for notification recipients.
 *
 * Same-run coalescing (one dispatch per channel per run) already collapses the
 * events of a single failing run. This adds a cross-run guard: once a recipient
 * has been notified on a channel for a service, further notifications of the
 * *same kind* for that service+channel are suppressed until the cooldown
 * expires — so a service that keeps failing run after run does not storm the
 * recipient.
 *
 * ## Why the kind is part of the key
 *
 * The window used to be keyed on recipient+service+channel alone. A service
 * that failed and recovered inside one window then sent the failure mail and
 * silently swallowed the recovery: the reader was left believing an outage was
 * still running when it had already ended, and suppression writes no
 * notification_log row, so nothing recorded that it happened. A recovery is not
 * a repeat of the failure that preceded it — it is the news that cancels it, and
 * it must never be suppressed by the failure's own window.
 *
 * So the key carries the kind of the dispatch ([FAILURE] or [RECOVERED]), and
 * the two never share a window. [markDispatched] additionally clears the
 * opposite kind's window: a state change ends the storm the previous state was
 * being throttled for, so the next transition back is always allowed through
 * (fail → recover → fail again inside 300s delivers all three).
 *
 * Backed by a Redis key with a TTL: `cooldown:{orgUserId}:{serviceId}:{channel}:{kind}`.
 * Suppression is silent (no notification_log row), matching silenced/quiet-hours.
 */
class RecipientCooldown(
    private val redis: RedisCommands<String, String>,
    private val ttlSeconds: Long = 300L,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** True if this recipient is still within cooldown for the service+channel+kind. */
    fun isOnCooldown(orgUserId: UUID, serviceId: UUID, channel: String, kind: String): Boolean =
        try {
            redis.exists(key(orgUserId, serviceId, channel, kind)) > 0
        } catch (e: Exception) {
            // Redis unavailable — fail open (deliver) rather than drop notifications.
            log.warn("cooldown check failed for {} / {} / {} / {}: {}", orgUserId, serviceId, channel, kind, e.message)
            false
        }

    /**
     * Records a successful dispatch, starting the cooldown window for [kind] and
     * clearing the opposite kind's window (see the class comment).
     */
    fun markDispatched(orgUserId: UUID, serviceId: UUID, channel: String, kind: String) {
        try {
            redis.set(key(orgUserId, serviceId, channel, kind), "1", SetArgs.Builder.ex(ttlSeconds))
            redis.del(key(orgUserId, serviceId, channel, opposite(kind)))
        } catch (e: Exception) {
            log.warn("cooldown set failed for {} / {} / {} / {}: {}", orgUserId, serviceId, channel, kind, e.message)
        }
    }

    companion object {
        /** A run that reported a problem. */
        const val FAILURE = "failure"

        /** A run that reported the service is healthy again. */
        const val RECOVERED = "recovered"

        /**
         * The cooldown dimension for one coalesced dispatch. A run raises a
         * single message per channel, and that message is either the recovery
         * or the failure — there is no third kind.
         */
        fun kindOf(recovered: Boolean): String = if (recovered) RECOVERED else FAILURE

        /** The other kind, whose window a dispatch of [kind] cancels. */
        fun opposite(kind: String): String = if (kind == RECOVERED) FAILURE else RECOVERED

        /**
         * The Redis key for one recipient's window. Kept pure and public so the
         * key shape — in particular that the kind is part of it — is testable
         * without a Redis.
         */
        fun key(orgUserId: UUID, serviceId: UUID, channel: String, kind: String): String =
            "cooldown:$orgUserId:$serviceId:$channel:$kind"
    }
}
