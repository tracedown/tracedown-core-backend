package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Stored response bodies whose object-storage delete failed, kept so the delete
 * can be retried.
 *
 * Deleting monitoring data is two writes against two systems: remove the object
 * from the bucket, then remove the `probe_steps` row holding its URI. The row
 * has to go regardless (a broken bucket must never stall erasure), which means
 * that on a storage failure the only pointer to the object is destroyed. The
 * URI is written here first, so the object stays reachable — and therefore
 * still deletable — after the row that named it is gone.
 *
 * Bodies can contain personal data belonging to third parties, captured from
 * the probed endpoint, so an unreachable object is a retention and erasure
 * failure rather than wasted bytes.
 */
object PendingBodyDeletions : Table("pending_body_deletions") {
    val id = uuid("id")

    /** The `file://` or `s3://` URI still to be deleted. Unique. */
    val storageUrl = text("storage_url")

    /** How many delete attempts have been made, including the one that failed. */
    val attempts = integer("attempts").default(1)

    /** Message from the most recent failure, for operators chasing a stuck row. */
    val lastError = text("last_error").nullable()

    val firstSeenAt = timestamp("first_seen_at")
    val lastAttemptAt = timestamp("last_attempt_at")

    override val primaryKey = PrimaryKey(id)
}
