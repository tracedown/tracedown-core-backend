package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object NotificationLog : Table("notification_log") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val serviceId = javaUUID("service_id").references(Services.id).nullable()
    val probeResultId = javaUUID("probe_result_id").references(ProbeResults.id).nullable()
    val channel = varchar("channel", 8)
    val recipient = varchar("recipient", 255)
    val status = varchar("status", 16)
    val attemptCount = short("attempt_count").default(1)
    val error = text("error").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
