package dev.tracedown.gateway.controllers.audit

import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.Users
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.audit.AuditLogEntry
import dev.tracedown.gateway.util.requireOrgRead
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

object AuditController {

    /**
     * Lists audit log entries for the organization.
     * Requires org-level settings.read permission.
     * Filtering, sorting, and pagination are handled by PFS.
     */
    fun list(
        orgId: UUID,
        userId: UUID,
        pfs: PfsParams,
    ): Page<AuditLogEntry> {
        return transaction {
            requireOrgRead(orgId, userId) { it.settings }

            val query = OrgAuditLog.join(
                Users,
                org.jetbrains.exposed.v1.core.JoinType.LEFT,
                onColumn = OrgAuditLog.userId,
                otherColumn = Users.id,
            ).selectAll()
                .where { OrgAuditLog.organizationId eq orgId }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row ->
                AuditLogEntry(
                    id = row[OrgAuditLog.id].toString(),
                    userId = row[OrgAuditLog.userId]?.toString(),
                    // A null actor is a system-initiated action (e.g. an operator
                    // acting on the org) — surfaced as SYSTEM rather than blank.
                    actorName = if (row[OrgAuditLog.userId] == null) "SYSTEM" else row.getOrNull(Users.displayName),
                    actorEmail = row.getOrNull(Users.email),
                    action = row[OrgAuditLog.action],
                    entityType = row[OrgAuditLog.entityType],
                    entityId = row[OrgAuditLog.entityId],
                    entityDisplayName = row[OrgAuditLog.entityDisplayName],
                    diff = row[OrgAuditLog.diff]?.toString(),
                    comment = row[OrgAuditLog.comment],
                    createdAt = row[OrgAuditLog.createdAt].toString(),
                )
            }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }
}
