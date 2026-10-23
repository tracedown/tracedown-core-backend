package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * How far the outbox purge has reached: one row, `id = 1`.
 *
 * [purgedXid] and [purgedSeq] are the last row the purge has deleted in the
 * order the event feed reads the log — by writing transaction ([Outbox.xid]),
 * then `seq`. The purge does not trim a clean prefix — it keeps a probe
 * result's row until that row is published, however old — so the first row
 * still present says nothing about what is gone. This does: a reader
 * positioned before it may have missed a deleted row, and one at or after it
 * has missed nothing. The purge only moves it forward, and never past the
 * oldest transaction still open. The event feed sets it to the present when it
 * finds the database was rewound or restored — positions taken before then
 * mean nothing after.
 */
object OutboxRetention : Table("outbox_retention") {
    val id = short("id")
    val purgedXid = long("purged_xid").default(0)
    val purgedSeq = long("purged_seq").default(0)
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
