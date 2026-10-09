package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.javatime.timestampWithTimeZone
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

    /** The row's place in the log (a BIGINT identity), assigned by the database at INSERT. */
    val seq = long("seq").databaseGenerated()

    /**
     * When the row was written, by the database's clock at the INSERT itself
     * (`DEFAULT clock_timestamp()`), never set from code — [createdAt] is not
     * that: a probe result's row carries the run's start, which can be long
     * before it is recorded. The purge ages rows by it. Null on rows older
     * than the column.
     */
    val insertedAt = timestampWithTimeZone("inserted_at").nullable().databaseGenerated()

    /**
     * The id of the transaction that wrote the row (`DEFAULT
     * pg_current_xact_id()`), never set from code. The event feed reads the log
     * in (xid, seq) order and only below the oldest transaction still open, so
     * a row that commits late is never passed over: `seq` is handed out at
     * INSERT and seen at COMMIT, so `seq` alone is not an order a reader can
     * trust. Null on rows older than the column.
     */
    val xid = long("xid").nullable().databaseGenerated()

    /**
     * The organization the row is about, for readers that want one
     * organization's rows (the event feed). Set by the emitters; null for
     * platform rows and rows older than the column.
     */
    val organizationId = javaUUID("organization_id").nullable()

    override val primaryKey = PrimaryKey(id)
}
