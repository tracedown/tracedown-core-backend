package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object Users : Table("users") {
    val id = uuid("id")
    val email = varchar("email", 256)
    val passwordHash = varchar("password_hash", 256)
    val displayName = varchar("display_name", 128)
    val totpSecretEncrypted = varchar("totp_secret_encrypted", 512).nullable()
    val totpSecretIv = varchar("totp_secret_iv", 32).nullable()
    val totpEnrolledAt = timestamp("totp_enrolled_at").nullable()
    val totpLastUsedAt = timestamp("totp_last_used_at").nullable()

    /**
     * The TOTP time-step index already consumed by this account. A code is
     * single-use: verification must present a strictly newer step, so accepting
     * one burns it and every earlier one. Null until the first code is accepted.
     */
    val totpLastStep = long("totp_last_step").nullable()

    /**
     * Consecutive failed second-factor attempts, counted per ACCOUNT. Counting
     * them per pending session made the limit meaningless — a fresh login
     * minted a fresh counter.
     */
    val totpFailedAttempts = integer("totp_failed_attempts").default(0)

    /** Set when the account trips the attempt limit; second factors are refused until it passes. */
    val totpLockedUntil = timestamp("totp_locked_until").nullable()
    val totpEnabled = bool("totp_enabled").default(false)
    val selectedOrgId = uuid("selected_org_id").references(Organizations.id).nullable()
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
