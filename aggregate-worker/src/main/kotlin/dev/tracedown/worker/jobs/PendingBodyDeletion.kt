package dev.tracedown.worker.jobs

import dev.tracedown.common.models.PendingBodyDeletions
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.PendingBodyDeletion")

/**
 * Bookkeeping for stored response bodies whose object-storage delete failed.
 *
 * Both deletion paths — [PurgeJob] (erasure) and [RetentionJob] (ageing out) —
 * delete the object first and the `probe_steps` row that names it second, and
 * both delete the row whether or not the object actually went. That trade-off
 * is right (a broken bucket must never stall erasure) but it destroys the only
 * reference to a live object: nothing afterwards knows the object exists, there
 * is no sweeper, and body storage carries no lifecycle rule. Since bodies can
 * hold personal data captured from the probed endpoint, an unreferenced object
 * is a retention and erasure failure, not wasted bytes.
 *
 * Writing the URI here before the rows go keeps it referenced, and therefore
 * recoverable by [BodyDeletionRetryJob].
 *
 * Recording is best-effort by design: if this write fails, the caller's deletion
 * still proceeds. Losing the note is no worse than the behaviour it replaces,
 * and letting it abort a purge would be much worse.
 *
 * Every function here runs in the caller's transaction.
 */
object PendingBodyDeletion {

    /**
     * Records [uris] as still needing deletion, with the failure message that
     * put them there. Re-recording a URI already listed bumps its attempt count
     * rather than duplicating it.
     *
     * One `INSERT … ON CONFLICT (storage_url) DO UPDATE` per URI, and no
     * `catch` around it. The select-then-insert this replaces raced two workers
     * (or one worker and the retry job) straight into a unique violation, and a
     * unique violation inside a PostgreSQL transaction poisons the *whole*
     * transaction: catching it here logged one line and then silently lost every
     * other note in the same batch, which is exactly the reference-keeping this
     * object exists for. The conflict is now the database's to resolve.
     *
     * A failure that reaches the caller is a doomed transaction either way. The
     * retention passes therefore record in a transaction of their own, so the
     * page they are working on survives; the purge records inside its unit's
     * transaction, whose failure is already caught and retried on the next run.
     */
    fun record(uris: Collection<String>, error: String?) {
        if (uris.isEmpty()) return
        val now = java.sql.Timestamp.from(Instant.now())
        val conn = TransactionManager.current().connection.connection as java.sql.Connection
        conn.prepareStatement(UPSERT_SQL).use { stmt ->
            for (uri in uris.distinct()) {
                stmt.setObject(1, UUID.randomUUID())
                stmt.setString(2, uri)
                stmt.setString(3, error)
                stmt.setTimestamp(4, now)
                stmt.setTimestamp(5, now)
                stmt.addBatch()
            }
            stmt.executeBatch()
        }
    }

    private val UPSERT_SQL = """
        INSERT INTO pending_body_deletions
            (id, storage_url, attempts, last_error, first_seen_at, last_attempt_at)
        VALUES (?, ?, 1, ?, ?, ?)
        ON CONFLICT (storage_url) DO UPDATE SET
            attempts = pending_body_deletions.attempts + 1,
            last_error = EXCLUDED.last_error,
            last_attempt_at = EXCLUDED.last_attempt_at
    """.trimIndent()

    /** Drops [uris] from the pending list — their objects are confirmed gone. */
    fun clear(uris: Collection<String>) {
        if (uris.isEmpty()) return
        try {
            PendingBodyDeletions.deleteWhere { storageUrl inList uris.distinct() }
        } catch (e: Exception) {
            log.error("Could not clear {} settled body deletion(s): {}", uris.size, e.message)
        }
    }
}
