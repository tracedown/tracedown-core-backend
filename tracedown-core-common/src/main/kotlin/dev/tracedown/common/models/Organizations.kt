package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object Organizations : Table("organizations") {
    val id = javaUUID("id")
    val name = varchar("name", 128)
    val ownerId = javaUUID("owner_id").references(Users.id)
    val totpRequired = bool("totp_required").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val defaultTimezone = varchar("default_timezone", 64).default("UTC")
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
