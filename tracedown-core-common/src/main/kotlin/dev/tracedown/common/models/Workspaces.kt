package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Workspaces : Table("workspaces") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val name = varchar("name", 128)
    val coverImageUrl = varchar("cover_image_url", 128).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
