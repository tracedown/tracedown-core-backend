package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object Outbox : Table("outbox") {
    val id = javaUUID("id")
    val aggregateType = varchar("aggregate_type", 32)
    val aggregateId = javaUUID("aggregate_id")
    val eventType = varchar("event_type", 64)
    val payload = jsonb<JsonObject>("payload", Json.Default)
    val published = bool("published").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
