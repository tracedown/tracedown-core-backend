package dev.tracedown.gateway.util

import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Tells the scheduler about a service that just changed, over Redis A.
 *
 * `schedule:nudge` re-syncs one service's Quartz job immediately; without it
 * the change waits for the scheduler's consistency sweep. `probe:trigger` asks
 * for one immediate dispatch.
 *
 * Lifted out of the service controller because deleting a *container* stops
 * services too: a project or workspace delete has to nudge every service it
 * carried down, or those probes keep firing until the next sweep.
 *
 * Publishing is best-effort — the sweep is the backstop — so a Redis failure is
 * logged and swallowed, and an uninitialised publisher is silently a no-op.
 */
object ScheduleNudge {

    private val log = LoggerFactory.getLogger(ScheduleNudge::class.java)
    private var redisProvider: (() -> RedisCommands<String, String>)? = null

    /** Injects the Redis A connection provider. Call once at startup. */
    fun init(redis: () -> RedisCommands<String, String>) {
        this.redisProvider = redis
    }

    /** Publishes a schedule nudge so the scheduler picks up the change immediately. */
    fun publish(serviceId: UUID) = send("schedule:nudge", serviceId)

    /** Publishes each id in turn; one failure never stops the rest. */
    fun publishAll(serviceIds: Collection<UUID>) = serviceIds.forEach(::publish)

    /** Publishes a run-now trigger so the scheduler dispatches one immediate probe. */
    fun trigger(serviceId: UUID) = send("probe:trigger", serviceId)

    private fun send(channel: String, serviceId: UUID) {
        try {
            redisProvider?.invoke()?.publish(channel, serviceId.toString())
        } catch (e: Exception) {
            log.warn("failed to publish {} for {}: {}", channel, serviceId, e.message)
        }
    }
}
