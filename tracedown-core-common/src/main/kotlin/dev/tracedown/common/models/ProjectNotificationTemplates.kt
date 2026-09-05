package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object ProjectNotificationTemplates : Table("project_notification_templates") {
    val id = javaUUID("id")
    val notificationTemplateId = javaUUID("notification_template_id").references(NotificationTemplates.id)
    val projectId = javaUUID("project_id").references(Projects.id)

    override val primaryKey = PrimaryKey(id)
}
