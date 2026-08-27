package dev.tracedown.gateway.routes.v1.auth

import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.gateway.context.AuthPrincipal
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.controllers.auth.SessionController
import dev.tracedown.gateway.data.auth.ChangePasswordRequest
import dev.tracedown.gateway.data.auth.LoginRequest
import dev.tracedown.gateway.data.auth.PasswordResetConfirm
import dev.tracedown.gateway.data.auth.PasswordResetRequest
import dev.tracedown.gateway.data.auth.ProfileCapabilitiesResponse
import dev.tracedown.gateway.data.auth.RevokedCount
import dev.tracedown.gateway.data.auth.SwitchOrgRequest
import dev.tracedown.gateway.data.auth.TotpConfirmRequest
import dev.tracedown.gateway.data.auth.TotpDisableRequest
import dev.tracedown.gateway.data.auth.TotpSetupRequest
import dev.tracedown.gateway.data.auth.TotpVerifyRequest
import dev.tracedown.gateway.data.auth.UpdateProfileRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.auth.resolveOrgPermissions
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import java.util.UUID
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Auth
 * Authentication, sessions, TOTP, profile, and password management.
 */
@Resource("/api/v1/auth")
class Auth {
    @Resource("login")
    class Login(val parent: Auth = Auth())

    @Resource("login/totp")
    class LoginTotp(val parent: Auth = Auth())

    @Resource("totp/setup")
    class TotpSetup(val parent: Auth = Auth())

    @Resource("totp/enroll")
    class TotpEnroll(val parent: Auth = Auth())

    @Resource("totp/setup/confirm")
    class TotpSetupConfirm(val parent: Auth = Auth())

    @Resource("switch-org")
    class SwitchOrg(val parent: Auth = Auth())

    @Resource("password-reset")
    class PasswordReset(val parent: Auth = Auth())

    @Resource("password-reset/confirm")
    class PasswordResetConfirm(val parent: Auth = Auth())

    @Resource("profile")
    class Profile(val parent: Auth = Auth())

    @Resource("change-password")
    class ChangePassword(val parent: Auth = Auth())

    @Resource("totp/disable")
    class TotpDisable(val parent: Auth = Auth())

    @Resource("totp/recovery-codes")
    class TotpRecoveryCodes(val parent: Auth = Auth())

    @Resource("me")
    class Me(val parent: Auth = Auth())

    @Resource("logout")
    class Logout(val parent: Auth = Auth())

    @Resource("sessions")
    class Sessions(val parent: Auth = Auth()) {
        @Resource("{id}")
        class ById(val parent: Sessions = Sessions(), val id: String)
    }

    @Resource("orgs")
    class Orgs(val parent: Auth = Auth())

    @Resource("profile/capabilities")
    class ProfileCapabilities(val parent: Auth = Auth())

    @Resource("account")
    class Account(val parent: Auth = Auth())
}

fun Route.authRoutes(appConfig: AppConfig, emailPublisher: EmailPublisher) {
    /** Authenticates with email/password. Returns session, TOTP challenge, or setup token. */
    post<Auth.Login> {
        val body = tryReceive<LoginRequest>(call)
        val result = AuthController.login(
            request = body,
            sessionTtlMinutes = appConfig.jwt.ttlMinutes,
            ipAddress = call.request.local.remoteAddress,
            userAgent = call.request.headers["User-Agent"],
        )
        call.respond(result)
    }

    /** Verifies a TOTP code against a login challenge. */
    post<Auth.LoginTotp> {
        val body = tryReceive<TotpVerifyRequest>(call)
        val result = AuthController.verifyTotp(
            challenge = body.challenge,
            code = body.code,
            sessionTtlMinutes = appConfig.jwt.ttlMinutes,
            ipAddress = call.request.local.remoteAddress,
            userAgent = call.request.headers["User-Agent"],
        )
        call.respond(result)
    }

    /** Begins TOTP enrollment. Returns secret (base32), otpauth URI, and confirm token. */
    post<Auth.TotpSetup> {
        val body = tryReceive<TotpSetupRequest>(call)
        val result = AuthController.beginTotpSetup(body.setupToken)
        call.respond(result)
    }

    /**
     * Begins authenticated (self-service) TOTP enrollment for the current user.
     * Unlike [Auth.TotpSetup] this needs no setupToken — the session identifies
     * the user. Exempt from the enrollment guard so a user in a TOTP-enforced org
     * can still reach it while unenrolled. Confirm via [Auth.TotpSetupConfirm].
     */
    post<Auth.TotpEnroll> {
        val principal = requireAuth(call, checkTotpEnrollment = false)
        val result = AuthController.generateTotpSetup(principal.userId)
        call.respond(result)
    }

    /**
     * Confirms TOTP enrollment by verifying a code against the generated secret.
     * Stores the secret, enables TOTP, and returns a session.
     */
    post<Auth.TotpSetupConfirm> {
        val body = tryReceive<TotpConfirmRequest>(call)
        val result = AuthController.confirmTotpSetup(
            confirmToken = body.confirmToken,
            code = body.code,
            sessionTtlMinutes = appConfig.jwt.ttlMinutes,
            ipAddress = call.request.local.remoteAddress,
            userAgent = call.request.headers["User-Agent"],
        )
        call.respond(result)
    }

    /**
     * Switches the user's active organization. Revokes the current session
     * and returns a new session scoped to the target org.
     */
    post<Auth.SwitchOrg> {
        val principal = requireAuth(call, checkTotpEnrollment = false)
        val body = tryReceive<SwitchOrgRequest>(call)
        val targetOrgId = parseUuid(body.orgId, "organization ID")
        val result = AuthController.switchOrg(
            principal = principal,
            targetOrgId = targetOrgId,
            sessionTtlMinutes = appConfig.jwt.ttlMinutes,
            ipAddress = call.request.local.remoteAddress,
            userAgent = call.request.headers["User-Agent"],
        )
        call.respond(result)
    }

    // ── Password Reset (unauthenticated) ──

    /** Sends a password reset email. Always returns 200 to prevent enumeration. */
    post<Auth.PasswordReset> {
        val body = tryReceive<PasswordResetRequest>(call)
        AuthController.requestPasswordReset(
            email = body.email,
            emailPublisher = emailPublisher,
            resetUrlBuilder = { token -> appConfig.platform.uri.passwordResetUrl(token) },
        )
        call.respond(mapOf("ok" to true))
    }

    /** Confirms a password reset with the token from the email. */
    post<Auth.PasswordResetConfirm> {
        val body = tryReceive<dev.tracedown.gateway.data.auth.PasswordResetConfirm>(call)
        AuthController.confirmPasswordReset(body.token, body.newPassword, appConfig.auth.passwordPolicy)
        call.respond(mapOf("ok" to true))
    }

    // ── Profile ──

    /** Updates the current user's profile (display name). */
    patch<Auth.Profile> {
        val principal = requireAuth(call)
        if (!appConfig.platform.allowProfileEdit) {
            // Users with users-write permission can always edit profiles
            val orgId = principal.organizationId
            if (orgId == null || !hasOrgUsersWrite(orgId, principal.userId)) {
                throw ForbiddenException(ErrorCodes.PROFILE_EDIT_DISABLED)
            }
        }
        val body = tryReceive<UpdateProfileRequest>(call)
        call.respond(AuthController.updateProfile(principal.userId, body.displayName))
    }

    /** Returns what the current user may do to their own account. */
    get<Auth.ProfileCapabilities> {
        val principal = requireAuth(call)
        val canEdit = if (appConfig.platform.allowProfileEdit) {
            true
        } else {
            val orgId = principal.organizationId
            orgId != null && hasOrgUsersWrite(orgId, principal.userId)
        }
        val canClose = appConfig.platform.allowAccountClosure
        call.respond(
            ProfileCapabilitiesResponse(
                allowProfileEdit = canEdit,
                allowAccountClosure = canClose,
                // Only the closure section reads these, so nothing is queried
                // for an install that has closure switched off.
                ownedOrgs = if (canClose) AuthController.ownedOrgs(principal.userId) else emptyList(),
            )
        )
    }

    /** Changes the current user's password. Requires current password. */
    post<Auth.ChangePassword> {
        val principal = requireAuth(call)
        val body = tryReceive<ChangePasswordRequest>(call)
        AuthController.changePassword(principal.userId, body.currentPassword, body.newPassword, appConfig.auth.passwordPolicy)
        call.respond(mapOf("ok" to true))
    }

    // ── TOTP Management ──

    /** Disables TOTP for the current user. Requires a valid TOTP or recovery code. */
    post<Auth.TotpDisable> {
        val principal = requireAuth(call)
        val body = tryReceive<TotpDisableRequest>(call)
        AuthController.disableTotp(principal.userId, body.code)
        call.respond(mapOf("ok" to true))
    }

    /** Regenerates the current user's TOTP recovery codes. Requires a valid code. */
    post<Auth.TotpRecoveryCodes> {
        val principal = requireAuth(call)
        val body = tryReceive<TotpDisableRequest>(call)
        call.respond(mapOf("recoveryCodes" to AuthController.regenerateRecoveryCodes(principal.userId, body.code)))
    }

    /** Returns all active org memberships for the current user. */
    get<Auth.Orgs> {
        val principal = requireAuth(call)
        call.respond(AuthController.listOrgs(principal.userId))
    }

    /** Returns the current user's profile and org-level permissions (if org selected). */
    get<Auth.Me> {
        val principal = requireAuth(call)
        call.respond(AuthController.meWithPermissions(principal))
    }

    /** Revokes the current session. */
    delete<Auth.Logout> {
        val principal = requireAuth(call, checkTotpEnrollment = false)
        AuthController.logout(principal.sessionId)
        call.respond(mapOf("ok" to true))
    }

    /** Lists all active sessions for the current user. */
    get<Auth.Sessions> {
        val principal = requireAuth(call)
        val pfs = parsePfsParams(call)
        call.respond(SessionController.listSessions(principal.userId, principal.sessionId, pfs))
    }

    /** Revokes a specific session by ID. */
    delete<Auth.Sessions.ById> { resource ->
        val principal = requireAuth(call)
        val sessionId = parseUuid(resource.id, "session ID")
        SessionController.revokeSession(sessionId, principal.userId, principal.sessionId, principal.organizationId)
        call.respond(mapOf("ok" to true))
    }

    /** Revokes all sessions except the current one. */
    delete<Auth.Sessions> {
        val principal = requireAuth(call)
        val count = SessionController.revokeAllOtherSessions(principal.userId, principal.sessionId, principal.organizationId)
        call.respond(RevokedCount(revoked = count))
    }

    /**
     * Closes the current user's account. Off unless the operator switched
     * `platform.allowAccountClosure` on — on a managed install an account is
     * the operator's to remove, and an org admin removing the last membership
     * already ends one.
     *
     * Confirmed with password plus a second factor when enrolled. Owned
     * organizations block it (409) unless the account is their only member and
     * the request opts to take them along.
     */
    delete<Auth.Account> {
        val principal = requireAuth(call)
        if (!appConfig.platform.allowAccountClosure) {
            throw ForbiddenException(ErrorCodes.ACCOUNT_CLOSURE_DISABLED)
        }
        val body = tryReceive<dev.tracedown.gateway.data.auth.DeleteAccountRequest>(call)
        AuthController.deleteAccount(
            userId = principal.userId,
            password = body.password,
            code = body.code,
            deleteOwnedOrgs = body.deleteOwnedOrgs,
            purgeRetentionDays = appConfig.systemLimits.purgeRetentionDays,
        )
        call.respond(mapOf("ok" to true))
    }
}

/**
 * Extracts and validates the session from the Authorization header.
 * If [checkTotpEnrollment] is true (default), also enforces TOTP enrollment
 * for users whose org/group requires it. Exempt endpoints (logout, TOTP setup)
 * pass false.
 */
fun requireAuth(
    call: io.ktor.server.application.ApplicationCall,
    checkTotpEnrollment: Boolean = true,
): AuthPrincipal {
    val header = call.request.headers["Authorization"]
        ?: throw UnauthorizedException(ErrorCodes.MISSING_AUTH_HEADER)

    val token = if (header.startsWith("Bearer ", ignoreCase = true)) {
        header.substring(7)
    } else {
        header
    }

    if (token.isBlank()) throw UnauthorizedException()

    val principal = AuthController.resolveSession(token, checkTotpEnrollment)
    // Attribute the rest of this request's logs to the caller's org. The
    // per-call LogContext plugin clears any stale value at the start of every
    // request, so this is the sole writer on the (synchronous) handler path.
    dev.tracedown.common.logging.LogContext.putOrg(principal.organizationId)
    return principal
}

/**
 * Like [requireAuth] but also validates that the session has an organization context.
 * Returns the principal and the org ID. Throws 400 if no org is selected.
 */
fun requireAuthWithOrg(
    call: io.ktor.server.application.ApplicationCall,
    checkTotpEnrollment: Boolean = true,
): Pair<AuthPrincipal, UUID> {
    val principal = requireAuth(call, checkTotpEnrollment)
    val orgId = principal.organizationId
        ?: throw BadRequestException(ErrorCodes.NO_ORG_SELECTED)
    return principal to orgId
}

/** Checks if the user has write access on the users section in the given org. */
private fun hasOrgUsersWrite(orgId: UUID, userId: UUID): Boolean {
    val perms = resolveOrgPermissions(orgId, userId) ?: return false
    return perms.users.canWrite()
}
