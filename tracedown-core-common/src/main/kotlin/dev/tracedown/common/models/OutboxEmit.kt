package dev.tracedown.common.models

import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.util.UUID

/**
 * Emits durable events onto the transactional [Outbox].
 *
 * These helpers MUST be called inside the caller's existing `transaction { }`
 * so the event row is committed atomically with the write it describes — either
 * both land or neither does. The row is written with `published = false`; each
 * consumer decides independently whether it cares (the notification consumer
 * filters on its own event type, cursor consumers track their own offset), so
 * adding new event types never disturbs existing consumers.
 */
object OutboxEmit {

    /**
     * Inserts a single outbox row. [aggregateType] is a short kind tag (e.g.
     * "workspace"), [aggregateId] the id of the affected entity, [eventType] a
     * dotted name (e.g. "resource.workspace.created"), and [payload] a compact
     * JSON body. Call within an open transaction.
     */
    fun emitResourceEvent(
        eventType: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: JsonObject,
        createdAt: Instant = Instant.now(),
    ) {
        Outbox.insert {
            it[id] = UUID.randomUUID()
            it[Outbox.aggregateType] = aggregateType
            it[Outbox.aggregateId] = aggregateId
            it[Outbox.eventType] = eventType
            it[Outbox.payload] = payload
            it[published] = false
            it[Outbox.createdAt] = createdAt
        }
    }
}
