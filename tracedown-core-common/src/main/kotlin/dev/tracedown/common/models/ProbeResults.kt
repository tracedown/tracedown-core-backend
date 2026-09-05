package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

object ProbeResults : Table("probe_results") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    // Nullable: skipped probes (dispatch queue full) never reached an agent.
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id).nullable()
    val startedAt = timestamp("started_at")
    val status = varchar("status", 8)
    val runDurationMs = integer("run_duration_ms")
    val totalResponseMs = integer("total_response_ms").default(0)
    // Measured HTTP-layer usage for this run (agent-supplied).
    val ingressBytes = long("ingress_bytes").default(0)
    val egressBytes = long("egress_bytes").default(0)
    // Bytes the scheduler dispatched to the agent to run this probe. A neutral
    // per-run dispatch metric (the request body sent to the agent).
    val agentEgressBytes = long("agent_egress_bytes").default(0)
    // Number of HTTP calls this run made (chain length). Neutral per-run metric.
    val requestCount = integer("request_count").default(0)
    val rawResult = jsonb<JsonObject>("raw_result", Json.Default)
    val projectId = javaUUID("project_id").references(Projects.id)
    val workspaceId = javaUUID("workspace_id").references(Workspaces.id)
    val organizationId = javaUUID("organization_id").references(Organizations.id)

    override val primaryKey = PrimaryKey(id)
}
