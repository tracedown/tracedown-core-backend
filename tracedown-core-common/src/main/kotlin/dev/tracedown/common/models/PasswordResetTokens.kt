package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object PasswordResetTokens : Table("password_reset_tokens") {
    val id = uuid("id")
    val userId = uuid("user_id").references(Users.id)
    val tokenHash = varchar("token_hash", 255)

    /**
     * SHA-256 of the raw token — the indexed locator that picks the ONE row to
     * bcrypt-verify. Confirmation is an unauthenticated endpoint, so a
     * scan-and-verify-every-row lookup let one junk request burn a bcrypt per
     * outstanding reset. Null on rows minted before the column existed; those
     * are never candidates.
     */
    val tokenLookup = varchar("token_lookup", 64).nullable()
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
