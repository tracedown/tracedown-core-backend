package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object ProbeAggregates : Table("probe_aggregates") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id).nullable()
    val bucketStart = timestamp("bucket_start")
    val bucketType = varchar("bucket_type", 8)
    val p50Ms = integer("p50_ms").nullable()
    val p95Ms = integer("p95_ms").nullable()
    val p99Ms = integer("p99_ms").nullable()
    val errorRate = float("error_rate").nullable()
    val uptimePct = float("uptime_pct").nullable()
    val probeCount = integer("probe_count").default(0)

    override val primaryKey = PrimaryKey(id)
}
