package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** Session lifecycle values for [Sessions.status]. */
object SessionStatus {
    /** Password verified, awaiting TOTP. Tokenless — not a usable session. */
    const val PENDING_TOTP = "pending_totp"
    /** Fully authenticated; carries a bearer token. */
    const val ACTIVE = "active"
}

object Sessions : Table("sessions") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(Users.id)
    val organizationId = javaUUID("organization_id").references(Organizations.id).nullable()
    // SHA-256 digest of the bearer token — never the raw token (see TokenHasher).
    // Tokenless until 'active' — see SessionStatus and the migration comment.
    val sessionTokenHash = varchar("session_token_hash", 255).nullable()
    val status = varchar("status", 20).default(SessionStatus.ACTIVE)
    val totpAttemptCount = integer("totp_attempt_count").default(0)
    val ipAddress = varchar("ip_address", 45).nullable()
    val userAgent = varchar("user_agent", 512).nullable()
    val expiresAt = timestamp("expires_at")
    val lastActiveAt = timestamp("last_active_at")
    val revoked = bool("revoked").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
