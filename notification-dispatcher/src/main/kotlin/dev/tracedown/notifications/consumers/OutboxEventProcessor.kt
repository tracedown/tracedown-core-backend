package dev.tracedown.notifications.consumers

import kotlinx.serialization.json.JsonObject

/**
 * What [OutboxConsumer] does with a claimed outbox row.
 *
 * The consumer owns claiming, ordering and publication; this is the whole of
 * what it knows about the work itself. Production wires
 * [dev.tracedown.notifications.processing.NotificationProcessor] in; tests wire
 * a recorder, which is what makes the concurrency behaviour testable without an
 * email queue or an HTTP endpoint behind it.
 *
 * Throwing leaves the row unpublished and still claimed, so it is retried once
 * the lease expires — see the failure notes on [OutboxConsumer].
 */
fun interface OutboxEventProcessor {
    suspend fun process(payload: JsonObject)
}
