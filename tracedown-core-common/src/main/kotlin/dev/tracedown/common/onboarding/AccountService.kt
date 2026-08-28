package dev.tracedown.common.onboarding

import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.lowerCase
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Shared account (user) creation. Used by self-serve onboarding as well as any
 * other flow that needs to provision a user with a real password. Hashing goes
 * through [PasswordHasher] so it never drifts from the login verification path.
 *
 * Runs in its own transaction; when called inside an existing Exposed
 * transaction it joins that one, so callers can create a user and adjacent rows
 * (e.g. a verification token) atomically.
 */
object AccountService {

    /** True when a non-deleted user already exists for [email] (case-insensitive). */
    fun emailTaken(email: String): Boolean = transaction {
        Users.selectAll()
            .where { (Users.email.lowerCase() eq email.lowercase()) and (Users.deleted eq false) }
            .limit(1)
            .any()
    }

    /**
     * Reclaims a soft-deleted account for [email] as a brand-new account and
     * returns its id, or null when no soft-deleted account holds this email.
     *
     * The row (and its id) is reused only because `users.email` is globally
     * unique — a second row for the same address would collide. Everything of the
     * prior owner is wiped: credentials are replaced with a fresh [password] /
     * [displayName], every 2FA field and the selected-org preference are cleared,
     * `created_at` is reset (fresh purge-grace window), and the user-scoped 2FA
     * recovery codes and live sessions are hard-deleted — the latter is essential,
     * since the reused id would otherwise leave old session tokens valid for the
     * new owner. Notification silences were already dropped at soft-delete time
     * ([AccountLifecycle]).
     *
     * Joins the caller's transaction when invoked inside one.
     */
    fun reclaimSoftDeleted(
        email: String,
        password: String,
        displayName: String = email.substringBefore("@"),
        isActive: Boolean = true,
    ): UUID? = transaction {
        val normalized = email.lowercase()
        val existing = Users.selectAll()
            .where { (Users.email.lowerCase() eq normalized) and (Users.deleted eq true) }
            .limit(1)
            .firstOrNull() ?: return@transaction null
        val userId = existing[Users.id]

        Users.update({ Users.id eq userId }) {
            it[Users.email] = normalized               // normalise casing on reclaim
            it[passwordHash] = PasswordHasher.hash(password)
            it[Users.displayName] = displayName
            it[totpSecretEncrypted] = null
            it[totpSecretIv] = null
            it[totpEnrolledAt] = null
            it[totpLastUsedAt] = null
            it[totpLastStep] = null
            it[totpFailedAttempts] = 0
            it[totpLockedUntil] = null
            it[totpEnabled] = false
            it[selectedOrgId] = null
            it[Users.isActive] = isActive
            it[deleted] = false
            it[deletedAt] = null
            it[purgeAfter] = null
            it[createdAt] = Instant.now()              // fresh purge-grace window
        }
        TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }
        Sessions.deleteWhere { Sessions.userId eq userId }
        userId
    }

    /**
     * Creates a user account and returns its id. [displayName] defaults to the
     * local-part of the email.
     */
    fun createUser(
        email: String,
        password: String,
        displayName: String = email.substringBefore("@"),
        isActive: Boolean = true,
    ): UUID = transaction {
        val userId = UUID.randomUUID()
        Users.insert {
            it[id] = userId
            it[Users.email] = email
            it[passwordHash] = PasswordHasher.hash(password)
            it[Users.displayName] = displayName
            it[Users.isActive] = isActive
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
        userId
    }
}
