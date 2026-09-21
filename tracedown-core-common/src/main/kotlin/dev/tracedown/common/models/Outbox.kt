package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object Outbox : Table("outbox") {
    val id = javaUUID("id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = javaUUID("aggregate_id")
    val eventType = varchar("event_type", 64)
    val payload = jsonb<JsonObject>("payload", Json.Default)
    val published = bool("published").default(false)
    val createdAt = timestamp("created_at")

    /**
     * Which consumer process currently holds a delivery lease on this row, and
     * when it took it. Both null until a flag consumer claims the row.
     *
     * Only the flag-style consumer (the one that flips [published]) uses these.
     * Cursor consumers track their own offset in `outbox_cursors` and never
     * compete for a row, so they neither read nor write the claim. The lease is
     * what lets the flag consumer run more than one replica: the claim and the
     * read happen in one statement, so only one replica ever sees a given row
     * as work. See `OutboxConsumer` in notification-dispatcher.
     *
     * [claimedBy] is diagnostic only — the mutual exclusion comes from the
     * claiming statement, not from the value. The columns are left in place
     * when the row is published, so a delivered row still records who
     * delivered it.
     */
    val claimedBy = varchar("claimed_by", 128).nullable()
    val claimedAt = timestamp("claimed_at").nullable()

    override val primaryKey = PrimaryKey(id)
}
