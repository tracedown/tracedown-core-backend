package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Per-consumer read offsets into the [Outbox].
 *
 * The outbox carries a monotonic `seq` column (a BIGINT identity). Each
 * independent consumer records the highest `seq` it has fully processed here,
 * keyed by its own name. This lets any number of consumers walk the same log at
 * their own pace WITHOUT competing over a shared claim flag — a consumer only
 * ever reads/writes its own row. The retention job uses the minimum offset
 * across all rows to decide what is safe to delete.
 */
object OutboxCursors : Table("outbox_cursors") {
    val consumerName = varchar("consumer_name", 128)
    val lastId = long("last_id").default(0)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(consumerName)
}

/** One outbox event as seen by a cursor consumer. */
data class OutboxRecord(
    val seq: Long,
    val aggregateType: String,
    val aggregateId: UUID,
    val eventType: String,
    val payload: JsonObject,
    val createdAt: Instant,
)

/** One consumer's position in the log, and when it last moved. */
data class OutboxCursorState(
    val consumerName: String,
    val lastId: Long,
    /**
     * When the cursor last advanced. A consumer that is caught up does not
     * advance, so this is only evidence of a stall when read together with the
     * consumer's distance from the head.
     */
    val updatedAt: Instant,
)

/**
 * Cursor-based reader over the [Outbox] for independent consumers.
 *
 * Each method manages its own transaction, so a consumer can drive it from a
 * plain loop: read a batch, process it, then advance the cursor. Delivery is
 * at-least-once — advance only after processing succeeds, and a crash between
 * processing and advancing re-delivers the tail.
 */
object OutboxStream {

    /** Current committed offset for a consumer, or 0 if it has never advanced. */
    fun cursorOf(consumerName: String): Long = transaction {
        OutboxCursors.selectAll()
            .where { OutboxCursors.consumerName eq consumerName }
            .firstOrNull()
            ?.get(OutboxCursors.lastId)
            ?: 0L
    }

    /**
     * Reads up to [batchSize] events with `seq` greater than the consumer's
     * current offset, oldest first. Does NOT advance the cursor — call
     * [advance] after the batch is processed.
     */
    fun nextBatch(consumerName: String, batchSize: Int): List<OutboxRecord> = transaction {
        val cursor = OutboxCursors.selectAll()
            .where { OutboxCursors.consumerName eq consumerName }
            .firstOrNull()
            ?.get(OutboxCursors.lastId)
            ?: 0L

        // seq and batchSize are numeric values under our control — safe to inline.
        val sql = """
            SELECT seq, aggregate_type, aggregate_id, event_type, payload, created_at
            FROM outbox
            WHERE seq > $cursor
            ORDER BY seq
            LIMIT $batchSize
        """.trimIndent()

        val records = mutableListOf<OutboxRecord>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                records.add(
                    OutboxRecord(
                        seq = rs.getLong("seq"),
                        aggregateType = rs.getString("aggregate_type"),
                        aggregateId = rs.getObject("aggregate_id") as UUID,
                        eventType = rs.getString("event_type"),
                        payload = Json.parseToJsonElement(rs.getString("payload")).jsonObject,
                        createdAt = rs.getTimestamp("created_at").toInstant(),
                    ),
                )
            }
        }
        records
    }

    /**
     * Advances a consumer's offset to [seq], creating the cursor row on first
     * use. Monotonic — a lower value than the stored offset is ignored, so an
     * out-of-order or replayed advance never rewinds the consumer.
     */
    fun advance(consumerName: String, seq: Long): Unit = transaction {
        val existing = OutboxCursors.selectAll()
            .where { OutboxCursors.consumerName eq consumerName }
            .firstOrNull()

        if (existing == null) {
            OutboxCursors.insert {
                it[OutboxCursors.consumerName] = consumerName
                it[lastId] = seq
                it[updatedAt] = Instant.now()
            }
        } else if (seq > existing[OutboxCursors.lastId]) {
            OutboxCursors.update({ OutboxCursors.consumerName eq consumerName }) {
                it[lastId] = seq
                it[updatedAt] = Instant.now()
            }
        }
    }

    /**
     * Lowest offset across every registered cursor, or null when there are no
     * cursor consumers at all. Retention uses this as a floor: nothing above it
     * may be deleted, because some consumer has not yet read it.
     *
     * Callers that must tolerate a consumer which has stopped advancing should
     * use [states] instead and decide for themselves — an unconditional floor
     * lets one stalled consumer pin the whole log.
     */
    fun minCursor(): Long? = transaction {
        OutboxCursors.selectAll()
            .mapNotNull { it[OutboxCursors.lastId] }
            .minOrNull()
    }

    /** Every registered cursor, with the last time it advanced. */
    fun states(): List<OutboxCursorState> = transaction {
        OutboxCursors.selectAll().map {
            OutboxCursorState(
                consumerName = it[OutboxCursors.consumerName],
                lastId = it[OutboxCursors.lastId],
                updatedAt = it[OutboxCursors.updatedAt],
            )
        }
    }

    /**
     * Highest `seq` currently in the outbox, or 0 when it is empty.
     *
     * Paired with [states] this is what separates a consumer that is idle
     * because it is caught up from one that has stopped: the first sits at the
     * head, the second falls behind it.
     */
    fun headSeq(): Long = transaction {
        var head = 0L
        TransactionManager.current().exec("SELECT COALESCE(MAX(seq), 0) AS head FROM outbox") { rs ->
            if (rs.next()) head = rs.getLong("head")
        }
        head
    }
}
