package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object Projects : Table("projects") {
    val id = javaUUID("id")
    val workspaceId = javaUUID("workspace_id").references(Workspaces.id)
    val name = varchar("name", 128)
    val coverImageUrl = varchar("cover_image_url", 128).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
