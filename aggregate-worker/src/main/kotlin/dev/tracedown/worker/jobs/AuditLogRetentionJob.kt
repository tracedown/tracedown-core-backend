package dev.tracedown.worker.jobs

import dev.tracedown.common.models.OrgAuditLog
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Trims organization audit-log entries to the configured window (default 90
 * days; -1 keeps forever). Reads the same `AUDIT_LOG_RETENTION_DAYS` knob the
 * gateway exposes, so one setting governs the audit window platform-wide.
 *
 * This ages out old entries wholesale; it is unrelated to user erasure, which
 * keeps audit rows and only anonymizes the actor link.
 */
class AuditLogRetentionJob(
    private val retentionDays: Int,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "AuditLogRetentionJob"

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute() {
        if (retentionDays <= 0) {
            log.debug("Audit log retention disabled (auditLogRetentionDays={})", retentionDays)
            return
        }
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            OrgAuditLog.deleteWhere { createdAt less cutoff }
        }
        if (deleted > 0) {
            log.info("Audit log retention: deleted {} entries older than {}d", deleted, retentionDays)
        }
    }
}
