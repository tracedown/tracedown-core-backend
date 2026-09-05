package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object OrgUserGroups : Table("org_user_groups") {
    val id = javaUUID("id")
    val orgUserId = javaUUID("org_user_id").references(OrgUsers.id)
    val orgGroupId = javaUUID("org_group_id").references(OrgGroups.id)

    override val primaryKey = PrimaryKey(id)
}
