package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

object WebhookDeliveries : Table("webhook_deliveries") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val name = varchar("name", 64)
    val label = varchar("label", 64).nullable()
    val url = varchar("url", 512)
    val method = varchar("method", 8).default("POST")
    val body = jsonb<JsonElement>("body", Json.Default).nullable()
    val config = jsonb<JsonElement>("config", Json.Default).nullable()
    val attemptCount = short("attempt_count").default(1)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
