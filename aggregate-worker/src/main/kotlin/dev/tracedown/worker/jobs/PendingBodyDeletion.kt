package dev.tracedown.worker.jobs

import dev.tracedown.common.models.PendingBodyDeletions
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
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
     */
    fun record(uris: Collection<String>, error: String?) {
        if (uris.isEmpty()) return
        val now = Instant.now()
        for (uri in uris.distinct()) {
            try {
                val known = PendingBodyDeletions.selectAll()
                    .where { PendingBodyDeletions.storageUrl eq uri }
                    .limit(1)
                    .any()
                if (known) {
                    PendingBodyDeletions.update({ PendingBodyDeletions.storageUrl eq uri }) {
                        it[attempts] = attempts + 1
                        it[lastError] = error
                        it[lastAttemptAt] = now
                    }
                } else {
                    PendingBodyDeletions.insert {
                        it[id] = UUID.randomUUID()
                        it[storageUrl] = uri
                        it[attempts] = 1
                        it[lastError] = error
                        it[firstSeenAt] = now
                        it[lastAttemptAt] = now
                    }
                }
            } catch (e: Exception) {
                // Never let bookkeeping break the deletion it is bookkeeping for.
                log.error("Could not record pending deletion of {}: {}", uri, e.message)
            }
        }
    }

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
