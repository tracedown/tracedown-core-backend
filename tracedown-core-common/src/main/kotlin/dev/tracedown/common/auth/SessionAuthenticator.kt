package dev.tracedown.common.auth

import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/** Shared fields a validated session yields. */
data class SessionContext(
    val userId: UUID,
    val sessionId: UUID,
    val organizationId: UUID?,
    val email: String,
    val totpEnabled: Boolean,
)

/**
 * Outcome of validating a bearer token. The distinct [Invalid.Reason]s let each
 * caller map to its own error model (the gateway emits per-reason error codes;
 * realtime collapses anything-but-[Valid] to null).
 */
sealed interface SessionResult {
    data class Valid(val context: SessionContext) : SessionResult
    data class Invalid(val reason: Reason) : SessionResult

    enum class Reason { NOT_FOUND, REVOKED, EXPIRED, USER_DELETED, USER_INACTIVE }
}

/**
 * The single source of truth for "is this token a usable session?", shared by the
 * api-gateway and realtime-service so the two can never diverge.
 *
 * Looks up by token among `status = 'active'` rows only — a pending (tokenless)
 * row can't be matched here regardless — then evaluates revoked / expiry / user
 * state stepwise so the caller learns *why* a token failed, not just that it did.
 *
 * Policy that is NOT universal (e.g. the gateway's TOTP-enrollment guard) stays
 * in the caller; this answers only the core validity question.
 */
object SessionAuthenticator {

    fun authenticate(token: String): SessionResult = transaction {
        // Only the digest is stored at rest — hash the presented token and match.
        val tokenHash = TokenHasher.sha256Hex(token)
        val row = Sessions
            .join(Users, JoinType.INNER, Sessions.userId, Users.id)
            .selectAll()
            .where {
                (Sessions.sessionTokenHash eq tokenHash) and
                (Sessions.status eq SessionStatus.ACTIVE)
            }
            .firstOrNull()
            ?: return@transaction SessionResult.Invalid(SessionResult.Reason.NOT_FOUND)

        when {
            row[Sessions.revoked] ->
                SessionResult.Invalid(SessionResult.Reason.REVOKED)
            row[Sessions.expiresAt] < Instant.now() ->
                SessionResult.Invalid(SessionResult.Reason.EXPIRED)
            row[Users.deleted] ->
                SessionResult.Invalid(SessionResult.Reason.USER_DELETED)
            !row[Users.isActive] ->
                SessionResult.Invalid(SessionResult.Reason.USER_INACTIVE)
            else -> SessionResult.Valid(
                SessionContext(
                    userId = row[Sessions.userId],
                    sessionId = row[Sessions.id],
                    organizationId = row[Sessions.organizationId],
                    email = row[Users.email],
                    totpEnabled = row[Users.totpEnabled],
                ),
            )
        }
    }
}
