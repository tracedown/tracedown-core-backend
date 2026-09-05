package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb

object SystemAlerts : Table("system_alerts") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val alertType = varchar("alert_type", 64)
    val subject = varchar("subject", 128).default("")
    val severity = varchar("severity", 16).default("warning")
    val data = jsonb<JsonObject>("data", Json.Default).nullable()
    val createdAt = timestamp("created_at")
    val lastSeenAt = timestamp("last_seen_at")

    override val primaryKey = PrimaryKey(id)
}

object SystemAlertDismissals : Table("system_alert_dismissals") {
    val id = javaUUID("id")
    val alertId = javaUUID("alert_id").references(SystemAlerts.id)
    val userId = javaUUID("user_id").references(Users.id)
    val dismissedAt = timestamp("dismissed_at")

    override val primaryKey = PrimaryKey(id)
}
