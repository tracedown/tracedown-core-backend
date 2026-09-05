package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object ApiKeys : Table("api_keys") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    // Provenance only — cleared (ON DELETE SET NULL) when the creator's account is erased.
    val createdBy = javaUUID("created_by").references(Users.id).nullable()
    val name = varchar("name", 128)
    val keyHash = varchar("key_hash", 255)
    val lastUsedAt = timestamp("last_used_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val revoked = bool("revoked").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
