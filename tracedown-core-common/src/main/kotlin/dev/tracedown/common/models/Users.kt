package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object Users : Table("users") {
    val id = javaUUID("id")
    val email = varchar("email", 256)

    /**
     * Three states, and the difference between the last two is load-bearing:
     *  - a bcrypt hash — the account signs in with a password;
     *  - `null` — a real account that has no password. Credential sign-in always
     *    fails for it and there is no current password to re-verify; it gets one
     *    through the emailed reset link;
     *  - `""` — the stub an invitation creates. Not an account yet: the invite
     *    link may claim it and set its first password, which is exactly what must
     *    never be possible for a real account.
     *
     * Ask [isUnclaimedStub] / [hasPassword] rather than comparing by hand.
     */
    val passwordHash = varchar("password_hash", 256).nullable()
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
    val selectedOrgId = javaUUID("selected_org_id").references(Organizations.id).nullable()
    val isActive = bool("is_active").default(true)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    /** True for the stub an invitation created and nobody has claimed yet. */
    fun isUnclaimedStub(user: ResultRow): Boolean = user[passwordHash]?.isBlank() == true

    /** True when the account has a password to sign in with, and to re-verify. */
    fun hasPassword(user: ResultRow): Boolean = !user[passwordHash].isNullOrBlank()
}
