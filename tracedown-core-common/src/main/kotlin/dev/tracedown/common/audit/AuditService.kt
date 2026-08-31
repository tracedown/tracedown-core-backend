package dev.tracedown.common.audit

import dev.tracedown.common.models.OrgAuditLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.insert
import java.time.Instant
import java.util.UUID

/**
 * Records org-level audit log entries. Must be called within an Exposed transaction.
 *
 * Actions follow the pattern `verb.entity` (e.g. "create.workspace", "update.group",
 * "delete.service", "invite.user", "transfer.ownership").
 */
object AuditService {

    /**
     * Inserts an audit log entry.
     *
     * @param orgId              Organization this event belongs to.
     * @param userId             User who performed the action (null for system-initiated).
     * @param action             Short verb.entity string, e.g. "create.workspace".
     * @param entityType         Resource type affected, e.g. "workspace", "group".
     * @param entityId           ID of the affected resource.
     * @param entityDisplayName  What the entity was called at the time of the change,
     *                           so the log is readable without a join and stays correct
     *                           after the entity is renamed or deleted. Null for
     *                           system-wide actions that target no single named entity.
     * @param diff               JSON string describing what changed (old/new values).
     * @param comment            Optional human-readable note.
     *
     * The entry's *subject* — the person it is about, who on an invite is not
     * the actor — is not a column of its own: when the entity IS a user,
     * `entityType`/`entityId` already identify them, and that is what erasure
     * and the personal-data export match on. Everything else is found by the
     * address the row carries, so keep identifiers out of `comment` and `diff`
     * unless the entry genuinely needs them.
     */
    fun log(
        orgId: UUID,
        userId: UUID?,
        action: String,
        entityType: String? = null,
        entityId: String? = null,
        entityDisplayName: String? = null,
        diff: String? = null,
        comment: String? = null,
    ) {
        OrgAuditLog.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[OrgAuditLog.userId] = userId
            it[OrgAuditLog.action] = action
            it[OrgAuditLog.entityType] = entityType
            it[OrgAuditLog.entityId] = entityId
            it[OrgAuditLog.entityDisplayName] = entityDisplayName
            it[OrgAuditLog.diff] = diff?.let { d -> Json.parseToJsonElement(d) }
            it[OrgAuditLog.comment] = comment
            it[createdAt] = Instant.now()
        }
    }
}

/**
 * Builds the audit `diff` JSON from field changes: `{"field": {"from": a, "to": b}}`.
 * Unchanged pairs are dropped; returns null when nothing changed. Values are
 * rendered with toString — pass pre-masked values for anything sensitive.
 */
fun auditDiff(vararg changes: Triple<String, Any?, Any?>): String? {
    val changed = changes.filter { (_, from, to) -> from != to }
    if (changed.isEmpty()) return null
    return buildJsonObject {
        for ((field, from, to) in changed) {
            put(field, buildJsonObject {
                put("from", from?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive(null as String?))
                put("to", to?.let { JsonPrimitive(it.toString()) } ?: JsonPrimitive(null as String?))
            })
        }
    }.toString()
}
