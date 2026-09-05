package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object ResourceWebhookAccess : Table("resource_webhook_access") {
    val id = javaUUID("id")
    val orgId = javaUUID("org_id").references(Organizations.id)
    val resourceType = varchar("resource_type", 16)
    val resourceId = javaUUID("resource_id")
    val webhookDeliveryId = javaUUID("webhook_delivery_id").references(WebhookDeliveries.id)
    val enabled = bool("enabled").default(true)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
