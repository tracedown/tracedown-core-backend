package dev.tracedown.worker.jobs

import dev.tracedown.common.models.Sessions
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.SessionCleanupJob")

/**
 * Cleans up expired and revoked sessions.
 *
 * Runs every 15 minutes. Deletes:
 * - Sessions past their expires_at timestamp
 * - Revoked sessions inactive for more than 24 hours
 */
class SessionCleanupJob(
    override val intervalSeconds: Long = 900L,
) : ScheduledJob {

    override val name = "SessionCleanupJob"

    override suspend fun execute() {
        val now = Instant.now()
        val revokedCutoff = now.minus(24, ChronoUnit.HOURS)

        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            val expired = Sessions.deleteWhere { expiresAt less now }
            val revoked = Sessions.deleteWhere {
                (Sessions.revoked eq true) and (Sessions.lastActiveAt less revokedCutoff)
            }
            expired + revoked
        }

        if (deleted > 0) {
            log.info("Session cleanup: {} sessions deleted", deleted)
        }
    }
}
