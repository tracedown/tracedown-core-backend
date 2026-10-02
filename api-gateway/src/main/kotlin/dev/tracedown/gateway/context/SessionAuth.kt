package dev.tracedown.gateway.context

import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.SessionAuthenticator
import dev.tracedown.common.auth.SessionResult
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Sessions
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.UnauthorizedException
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a presented session token into the caller it belongs to — the
 * session-side counterpart of [ApiKeyAuth], and the home of the TOTP
 * enrolment guard both kinds of caller meet.
 */
object SessionAuth {

    private const val SESSION_TOUCH_DEBOUNCE_SECONDS = 60L

    private val sessionTouchCache = ConcurrentHashMap<UUID, Long>()

    /**
     * Resolves a session token to the caller it belongs to. Says nothing about
     * TOTP enrolment — that is [requireTotpEnrollment], applied by whoever needs it.
     */
    fun authenticateSession(token: String): ResolvedCaller {
        // Validity is decided by the shared authenticator (one definition for
        // gateway + realtime). Per-reason mapping preserves the gateway's error codes.
        val ctx = when (val result = SessionAuthenticator.authenticate(token)) {
            is SessionResult.Valid -> result.context
            is SessionResult.Invalid -> throw when (result.reason) {
                SessionResult.Reason.EXPIRED -> UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
                SessionResult.Reason.USER_DELETED -> UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
                SessionResult.Reason.USER_INACTIVE -> UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)
                SessionResult.Reason.NOT_FOUND, SessionResult.Reason.REVOKED -> UnauthorizedException()
            }
        }

        touchSessionActivity(ctx.sessionId)

        return ResolvedCaller(
            principal = AuthPrincipal(
                userId = ctx.userId,
                sessionId = ctx.sessionId,
                email = ctx.email,
                organizationId = ctx.organizationId,
            ),
            totpEnabled = ctx.totpEnabled,
        )
    }

    /**
     * The TOTP enrolment guard — gateway policy, enforced for the caller's
     * organization only. A user whose org or group requires a second factor and
     * who has not enrolled one is refused, whichever credential they came with:
     * a key acting as that user is held to the same rule as their session.
     */
    fun requireTotpEnrollment(caller: ResolvedCaller) {
        val orgId = caller.principal.organizationId ?: return
        if (caller.totpEnabled) return
        val enforced = transaction { isTotpEnforcedForOrg(caller.principal.userId, orgId) }
        if (!enforced) return
        // A session is sent to enrol by the generic refusal it has always had.
        // A key has no screen to be sent to, so it is told what is in the way.
        throw when (caller.principal.credential) {
            is Credential.ApiKey -> ForbiddenException(ErrorCodes.TOTP_ENROLLMENT_REQUIRED)
            is Credential.Session -> ForbiddenException()
        }
    }

    /**
     * Debounced session activity touch. Only writes to the DB if the session
     * hasn't been touched in the last [SESSION_TOUCH_DEBOUNCE_SECONDS].
     * Uses atomic ConcurrentHashMap.compute to prevent concurrent requests
     * from racing past the debounce check.
     */
    private fun touchSessionActivity(sessionId: UUID) {
        val now = Instant.now().epochSecond
        var shouldWrite = false
        sessionTouchCache.compute(sessionId) { _, lastTouch ->
            if (lastTouch == null || now - lastTouch >= SESSION_TOUCH_DEBOUNCE_SECONDS) {
                shouldWrite = true
                now
            } else {
                lastTouch
            }
        }
        if (!shouldWrite) return

        try {
            transaction {
                Sessions.update({ Sessions.id eq sessionId }) {
                    it[lastActiveAt] = Instant.now()
                }
            }
        } catch (_: Exception) {
            // Best-effort — if it fails, next debounce window will retry
        }
    }

    /**
     * Checks if TOTP is enforced for a user in a specific org.
     * Reads from permission_cache if available, otherwise checks org + groups directly.
     */
    fun isTotpEnforcedForOrg(userId: UUID, orgId: UUID): Boolean {
        val membership = OrgUsers.selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull() ?: return false

        val cache = membership[OrgUsers.permissionCache]
        if (cache != null) {
            return CachedPermissions.fromJsonObject(cache).totpRequired
        }

        // Fallback: check org and groups directly
        val org = Organizations.selectAll()
            .where { Organizations.id eq orgId }
            .firstOrNull()
        if (org != null && org[Organizations.totpRequired]) return true

        val groupIds = OrgUserGroups.selectAll()
            .where { OrgUserGroups.orgUserId eq membership[OrgUsers.id] }
            .map { it[OrgUserGroups.orgGroupId] }

        for (groupId in groupIds) {
            val group = OrgGroups.selectAll()
                .where { OrgGroups.id eq groupId }
                .firstOrNull()
            if (group != null && group[OrgGroups.totpRequired]) return true
        }

        return false
    }
}
