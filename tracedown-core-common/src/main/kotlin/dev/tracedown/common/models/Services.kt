package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Services : Table("services") {
    val id = uuid("id")
    val projectId = uuid("project_id").references(Projects.id)
    val name = varchar("name", 128)
    val label = varchar("label", 32).nullable()
    val script = text("script").default("")
    val schedule = varchar("schedule", 16).default("*/5 * * * *")
    val probeMode = varchar("probe_mode", 16).default("consecutive")
    val queuePolicy = varchar("queue_policy", 16).default("skip")
    val serviceWindow = varchar("service_window", 256).nullable()
    /** Permits the executor to store response bodies; dispatch may still withhold it. */
    val saveResponseBodies = bool("save_response_bodies").default(true)
    val isActive = bool("is_active").default(true)
    val lastStatus = varchar("last_status", 8).nullable()
    val lastStatusSince = timestamp("last_status_since").nullable()
    val lastStatusConsecutive = integer("last_status_consecutive").default(0)
    val lastRunId = uuid("last_run_id").references(ProbeResults.id).nullable()
    val version = integer("version").default(1)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
