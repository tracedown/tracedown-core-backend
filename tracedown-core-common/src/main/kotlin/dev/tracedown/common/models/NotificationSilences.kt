package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.json.jsonb

object NotificationSilences : Table("notification_silences") {
    val id = javaUUID("id")
    val orgUserId = javaUUID("org_user_id").references(OrgUsers.id)
    val workspaceId = javaUUID("workspace_id").references(Workspaces.id).nullable()
    val projectId = javaUUID("project_id").references(Projects.id).nullable()
    val serviceId = javaUUID("service_id").references(Services.id).nullable()
    val channel = varchar("channel", 16)
    val config = jsonb<JsonElement>("config", Json.Default).nullable()
    val quietHours = varchar("quiet_hours", 256).nullable()

    override val primaryKey = PrimaryKey(id)
}
