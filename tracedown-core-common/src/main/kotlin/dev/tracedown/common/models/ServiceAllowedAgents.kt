package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID

object ServiceAllowedAgents : Table("service_allowed_agents") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id)

    override val primaryKey = PrimaryKey(id)
}
