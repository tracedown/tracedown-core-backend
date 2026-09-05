package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object ServiceVariables : Table("service_variables") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    val createdBy = javaUUID("created_by").references(Users.id).nullable()
    val key = varchar("key", 193)
    val value = text("value")
    val secret = bool("secret")
    val encrypted = bool("encrypted").default(true)
    val valueIv = varchar("value_iv", 64).nullable()
    val systemType = varchar("system_type", 8).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
