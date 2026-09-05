package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object ResourcePermissions : Table("resource_permissions") {
    val id = javaUUID("id")
    val orgId = javaUUID("org_id").references(Organizations.id)
    val principalType = varchar("principal_type", 16)
    val principalId = javaUUID("principal_id")
    val resourceType = varchar("resource_type", 16)
    val resourceId = javaUUID("resource_id")
    val permissions = short("permissions").default(0)

    override val primaryKey = PrimaryKey(id)
}
