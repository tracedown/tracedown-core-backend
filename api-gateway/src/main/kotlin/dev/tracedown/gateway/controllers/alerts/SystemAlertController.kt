package dev.tracedown.gateway.controllers.alerts

import dev.tracedown.common.models.SystemAlertDismissals
import dev.tracedown.common.models.SystemAlerts
import dev.tracedown.gateway.data.alerts.SystemAlertSummary
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Read/dismiss surface for platform-raised system alerts (capacity, agent
 * health). Gated by org settings WRITE — the banner targets people who can
 * act on it. Dismissals are per user and per episode.
 */
object SystemAlertController {

    /**
     * Banner alerts: latest undismissed episode PER TYPE. Concurrent
     * conditions of one kind (e.g. two degraded agents) collapse into the
     * most recent banner; the full record lives in the warning log.
     *
     * A banner persists until the user dismisses it — it does NOT auto-clear
     * when the condition stops recurring, since a past capacity/health
     * incident still warrants investigation. (A NEW episode after the quiet
     * window is a fresh row with dismissals cleared, so it reappears.)
     */
    fun listActive(orgId: UUID, userId: UUID): List<SystemAlertSummary> {
        return transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val dismissed = SystemAlertDismissals.selectAll()
                .where { SystemAlertDismissals.userId eq userId }
                .map { it[SystemAlertDismissals.alertId] }
                .toSet()

            SystemAlerts.selectAll()
                .where { SystemAlerts.organizationId eq orgId }
                .orderBy(SystemAlerts.lastSeenAt, SortOrder.DESC)
                .filterNot { it[SystemAlerts.id] in dismissed }
                .distinctBy { it[SystemAlerts.alertType] }
                .map { summaryFromRow(it) }
        }
    }

    /** Full episode history for the warning log (newest first, paged). */
    fun history(orgId: UUID, userId: UUID, pfs: PfsParams): Page<SystemAlertSummary> {
        return transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val query = SystemAlerts.selectAll()
                .where { SystemAlerts.organizationId eq orgId }
                .orderBy(SystemAlerts.createdAt, SortOrder.DESC)
            val total = query.count()
            val items = query
                .limit(pfs.pageSize).offset(((pfs.page - 1) * pfs.pageSize).toLong())
                .map { summaryFromRow(it) }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    private fun summaryFromRow(row: org.jetbrains.exposed.v1.core.ResultRow) = SystemAlertSummary(
        id = row[SystemAlerts.id].toString(),
        alertType = row[SystemAlerts.alertType],
        subject = row[SystemAlerts.subject],
        severity = row[SystemAlerts.severity],
        data = row[SystemAlerts.data],
        createdAt = row[SystemAlerts.createdAt].toString(),
        lastSeenAt = row[SystemAlerts.lastSeenAt].toString(),
    )

    /** Dismisses one alert for this user (idempotent). */
    fun dismiss(orgId: UUID, alertId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            SystemAlerts.selectAll()
                .where { (SystemAlerts.id eq alertId) and (SystemAlerts.organizationId eq orgId) }
                .firstOrNull() ?: throw NotFoundException()

            val exists = SystemAlertDismissals.selectAll()
                .where {
                    (SystemAlertDismissals.alertId eq alertId) and
                        (SystemAlertDismissals.userId eq userId)
                }
                .any()
            if (!exists) {
                SystemAlertDismissals.insert {
                    it[id] = UUID.randomUUID()
                    it[SystemAlertDismissals.alertId] = alertId
                    it[SystemAlertDismissals.userId] = userId
                    it[dismissedAt] = Instant.now()
                }
            }
        }
    }
}
