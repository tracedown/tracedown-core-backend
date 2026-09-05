package dev.tracedown.worker.jobs

import dev.tracedown.common.models.NotificationLog
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Trims notification delivery history to the configured window (default 90
 * days; -1 keeps forever). The log is an operational record ("was the alert
 * sent?") whose usefulness fades on the same timescale as the raw results it
 * refers to, but it gets its own knob (`NOTIFICATION_LOG_RETENTION_DAYS`)
 * because operators may want delivery evidence kept longer than probe data —
 * the rows are tiny compared to results.
 */
class NotificationLogRetentionJob(
    private val retentionDays: Int,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "NotificationLogRetentionJob"

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute() {
        if (retentionDays <= 0) {
            log.debug("Notification log retention disabled (notificationLogRetentionDays={})", retentionDays)
            return
        }
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            NotificationLog.deleteWhere { createdAt less cutoff }
        }
        if (deleted > 0) {
            log.info("Notification log retention: deleted {} entries older than {}d", deleted, retentionDays)
        }
    }
}
