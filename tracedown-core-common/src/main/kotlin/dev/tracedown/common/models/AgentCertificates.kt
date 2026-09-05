package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object AgentCertificates : Table("agent_certificates") {
    val id = javaUUID("id")
    val probeAgentId = long("probe_agent_id").references(ProbeAgents.id)
    val certificatePem = text("certificate_pem")
    val fingerprint = varchar("fingerprint", 128).uniqueIndex()
    val issuedAt = timestamp("issued_at")
    val expiresAt = timestamp("expires_at")
    val revoked = bool("revoked").default(false)
    val revokedAt = timestamp("revoked_at").nullable()
    val revokedReason = text("revoked_reason").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
