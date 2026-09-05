package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object Workspaces : Table("workspaces") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val name = varchar("name", 128)
    val coverImageUrl = varchar("cover_image_url", 128).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
