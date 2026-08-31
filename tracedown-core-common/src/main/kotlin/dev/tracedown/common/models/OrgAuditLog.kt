package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.sql.json.jsonb
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object OrgAuditLog : Table("org_audit_log") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    /** Who performed the action. Null for system-initiated events, and cleared when that account is erased. */
    val userId = uuid("user_id").references(Users.id).nullable()

    val action = varchar("action", 64)
    val entityType = varchar("entity_type", 64).nullable()

    /**
     * Id of the affected resource. When [entityType] is `"user"` this IS the
     * subject of the entry, which is how erasure and the personal-data export
     * find the rows about one person without a second link. Free-form: agent
     * slugs and other non-UUID identifiers appear here too.
     */
    val entityId = varchar("entity_id", 64).nullable()
    val entityDisplayName = varchar("entity_display_name", 256).nullable()
    val diff = jsonb<JsonElement>("diff", Json.Default).nullable()
    val comment = text("comment").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
