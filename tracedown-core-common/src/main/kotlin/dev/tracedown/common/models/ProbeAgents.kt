package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ProbeAgents : Table("probe_agents") {
    val id = long("id").autoIncrement()
    val slug = varchar("slug", 64).uniqueIndex()
    val label = varchar("label", 64)
    val agentUri = varchar("agent_uri", 255)
    val publicKey = text("public_key")
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val lastPing = timestamp("last_ping")
    val lastStatus = varchar("last_status", 8)
    val lastPingDelayMs = integer("last_ping_delay_ms")
    val lastPongDeltaMs = integer("last_pong_delta_ms")

    /**
     * Whether dispatches to this agent are sealed to its certificate on top of
     * mTLS. Per agent rather than platform-wide because the exposure is a
     * property of the path: an agent reached through something that terminates
     * TLS needs it, one on the same private network gains nothing and pays an
     * RSA wrap per run.
     */
    val encryptPayload = bool("encrypt_payload").default(false)

    /**
     * Whether the agent reported it can open a sealed dispatch. Learned from the
     * health challenge, so it relearns itself when an agent is upgraded — and
     * [encryptPayload] must never be honoured without it, or enabling the toggle
     * on an old agent would black-hole its probes.
     */
    val supportsEncryptedPayload = bool("supports_encrypted_payload").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
