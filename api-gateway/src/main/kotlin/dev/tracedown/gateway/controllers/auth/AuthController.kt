package dev.tracedown.gateway.controllers.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.SessionAuthenticator
import dev.tracedown.common.auth.SessionResult
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.onboarding.PasswordHasher
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.common.auth.canRead
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.PasswordResetTokens
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.context.AuthPrincipal
import dev.tracedown.gateway.data.auth.LoginRequest
import dev.tracedown.gateway.data.auth.LoginResponse
import dev.tracedown.gateway.data.auth.MeResponse
import dev.tracedown.gateway.data.auth.OrgMembership
import dev.tracedown.gateway.data.auth.OrgPermissionsDto
import dev.tracedown.gateway.data.auth.OwnedOrgSummary
import dev.tracedown.gateway.data.auth.TotpSetupResponse
import dev.tracedown.gateway.data.auth.UserSummary
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.controllers.orgs.MembershipAccess
import dev.tracedown.gateway.controllers.orgs.OrgSettingsController
import dev.tracedown.gateway.util.AccountClosurePolicy
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.PasswordPolicyConfig
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.validatePassword
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object AuthController {

    /** Mirrors platform.trustedDomainMode; set once at startup via [init]. */
    private var trustedDomainMode: Boolean = true

    fun init(trustedDomainMode: Boolean) {
        this.trustedDomainMode = trustedDomainMode
    }

    private val secureRandom = SecureRandom()
    private const val CHALLENGE_TTL_SECONDS = 300L // 5 minutes
    private const val SESSION_TOUCH_DEBOUNCE_SECONDS = 60L
    // Per-login guess cap. The account-wide limit that actually bounds guessing
    // lives in TotpPolicy — this one only stops a single pending session being
    // hammered, and a fresh login resets it by design.

    /** Tracks last touch time per session to debounce last_active_at updates. */
    private val sessionTouchCache = java.util.concurrent.ConcurrentHashMap<UUID, Long>()

    private lateinit var hmacKey: ByteArray
    private var totpIssuer: String = "Tracedown"

    fun init(aesKeyHex: String, totpIssuer: String = "Tracedown") {
        hmacKey = aesKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        this.totpIssuer = totpIssuer
    }

    /**
     * Authenticates user credentials and determines the login response:
     * - If TOTP is enabled or enforced and user is enrolled: returns challenge for TOTP verification
     * - If TOTP is enforced but user is NOT enrolled: returns setupToken for TOTP enrollment
     * - Otherwise: creates session directly
     */
    fun login(
        request: LoginRequest,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        if (request.email.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.password.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        return transaction {
            val user = verifyCredentials(request.email, request.password)
            val userId = user[Users.id]
            val userHasTotp = user[Users.totpEnabled]
            val targetOrgId = resolveTargetOrgId(user)
            // A user with no active organization may still sign in — they land on
            // the app's "no organizations" screen (e.g. removed from their last
            // org, or a pending invitee). Org-mandated TOTP only applies when
            // there is actually an org to enforce it.
            val totpEnforced = targetOrgId != null && isTotpEnforcedForOrg(userId, targetOrgId)

            when {
                userHasTotp -> {
                    // User has TOTP enrolled — open a pending session; its id is the
                    // challenge the client echoes back to verifyTotp.
                    val pendingId = createPendingSession(userId, targetOrgId, ipAddress, userAgent)
                    LoginResponse(totpRequired = true, challenge = pendingId.toString())
                }
                totpEnforced -> {
                    // TOTP is enforced but user hasn't enrolled — require setup
                    val setupToken = createChallenge(userId)
                    LoginResponse(totpSetupRequired = true, setupToken = setupToken)
                }
                else -> {
                    createSession(user, sessionTtlMinutes, ipAddress, userAgent)
                }
            }
        }
    }

    /**
     * Verifies a TOTP code against the pending session named by [challenge] (its
     * id) and, on success, activates that same row into a usable session — the
     * bearer token is minted only here, so the pre-auth challenge never doubles
     * as a credential.
     *
     * Failed codes count against the ACCOUNT, not this session: counting them on
     * the pending row meant an attacker holding the password reset the counter
     * by starting a new login, so five guesses per login became unlimited
     * guesses. The account counter trips a time-boxed lock (see [TotpPolicy]);
     * the pending row's own counter is kept as a per-login cap on top of it.
     */
    fun verifyTotp(
        challenge: String,
        code: String,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        val pendingId = try {
            UUID.fromString(challenge)
        } catch (_: IllegalArgumentException) {
            throw UnauthorizedException()
        }

        return transaction {
            val pending = Sessions.selectAll()
                .where {
                    (Sessions.id eq pendingId) and
                    (Sessions.status eq SessionStatus.PENDING_TOTP)
                }
                .firstOrNull()
                ?: throw UnauthorizedException()

            if (pending[Sessions.expiresAt] < Instant.now()) {
                throw UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
            }

            val attempts = pending[Sessions.totpAttemptCount]
            if (attempts >= TotpPolicy.MAX_ATTEMPTS) {
                throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
            }

            val userId = pending[Sessions.userId]
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (!user[Users.isActive]) throw UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)

            val attemptedAt = Instant.now()
            if (TotpPolicy.isLocked(user[Users.totpLockedUntil], attemptedAt)) {
                throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
            }

            if (user[Users.totpSecretEncrypted] == null || user[Users.totpSecretIv] == null) {
                throw UnauthorizedException(ErrorCodes.TOTP_NOT_CONFIGURED)
            }

            if (!consumeSecondFactor(user, code)) {
                val failure = TotpPolicy.afterFailure(user[Users.totpFailedAttempts], attemptedAt)
                Users.update({ Users.id eq userId }) {
                    it[totpFailedAttempts] = failure.attempts
                    if (failure.lockedUntil != null) it[totpLockedUntil] = failure.lockedUntil
                }
                Sessions.update({ Sessions.id eq pendingId }) {
                    it[totpAttemptCount] = attempts + 1
                }
                throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)
            }

            // A completed second factor clears the account's guess history.
            Users.update({ Users.id eq userId }) {
                it[totpFailedAttempts] = 0
                it[totpLockedUntil] = null
            }

            // Activate the pending row in place: mint the bearer token now.
            val token = generateToken()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)
            Sessions.update({ Sessions.id eq pendingId }) {
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[status] = SessionStatus.ACTIVE
                it[Sessions.expiresAt] = expiresAt
                it[lastActiveAt] = now
                it[Sessions.ipAddress] = ipAddress ?: pending[Sessions.ipAddress]
                it[Sessions.userAgent] = userAgent ?: pending[Sessions.userAgent]
            }

            LoginResponse(
                token = token,
                expiresAt = expiresAt.toString(),
                user = userSummaryFrom(user),
            )
        }
    }

    /**
     * Generates a new TOTP secret for enrollment.
     * Returns the secret as base32 and an otpauth URI for QR code generation,
     * plus a confirmToken that embeds the encrypted secret for confirmation.
     */
    fun beginTotpSetup(setupToken: String): TotpSetupResponse {
        val userId = validateChallenge(setupToken)
        return generateTotpSetup(userId)
    }

    /**
     * Generates a new TOTP secret + confirm token for [userId]. Shared by the
     * login-time setup flow (validated via setupToken) and authenticated
     * self-service enrollment (validated via session principal).
     */
    fun generateTotpSetup(userId: UUID): TotpSetupResponse {
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (user[Users.totpEnabled]) {
                throw BadRequestException(ErrorCodes.ALREADY_EXISTS)
            }

            // Generate new secret
            val secret = ByteArray(20).also { secureRandom.nextBytes(it) }
            val base32Secret = encodeBase32(secret)
            val email = user[Users.email]
            val otpauthUri = "otpauth://totp/${totpIssuer}:${email}?secret=${base32Secret}&issuer=${totpIssuer}&digits=6&period=30"

            // Encrypt and embed in confirm token
            val (encrypted, iv) = TotpUtil.encryptSecret(secret, hmacKey)
            val confirmPayload = "$userId:$encrypted:$iv"
            val expiry = Instant.now().epochSecond + CHALLENGE_TTL_SECONDS
            val signedPayload = "$confirmPayload:$expiry"
            val sig = hmacSign(signedPayload)
            val confirmToken = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("$signedPayload:$sig".toByteArray())

            TotpSetupResponse(
                secret = base32Secret,
                otpauthUri = otpauthUri,
                confirmToken = confirmToken,
            )
        }
    }

    /**
     * Confirms TOTP enrollment by verifying a code against the embedded secret.
     * Stores the encrypted secret, generates recovery codes, and creates a session.
     * Recovery codes are returned in the LoginResponse (shown once, never again).
     */
    fun confirmTotpSetup(
        confirmToken: String,
        code: String,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        // Decode confirm token: userId:encrypted:iv:expiry:sig
        val decoded = try {
            String(Base64.getUrlDecoder().decode(confirmToken))
        } catch (e: Exception) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }

        val parts = decoded.split(":")
        if (parts.size != 5) throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)

        val userId = try { UUID.fromString(parts[0]) } catch (e: Exception) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }
        val encrypted = parts[1]
        val iv = parts[2]
        val expiry = parts[3].toLongOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        val sig = parts[4]

        if (Instant.now().epochSecond > expiry) {
            throw UnauthorizedException(ErrorCodes.SETUP_TOKEN_EXPIRED)
        }

        val payload = "${parts[0]}:${parts[1]}:${parts[2]}:${parts[3]}"
        if (hmacSign(payload) != sig) {
            throw UnauthorizedException(ErrorCodes.INVALID_SETUP_TOKEN)
        }

        // Verify the code against the embedded secret. The secret is not stored
        // yet, so there is no consumed-step history to check against — but the
        // step that enrolls is recorded below, so the very same code cannot
        // then be replayed at the login prompt.
        val secret = TotpUtil.decryptSecret(encrypted, iv, hmacKey)
        val enrollStep = TotpUtil.matchingStep(secret, code)
            ?: throw UnauthorizedException(ErrorCodes.INVALID_TOTP_CODE)

        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull()
                ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            // Store the secret and enable TOTP
            Users.update({ Users.id eq userId }) {
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnabled] = true
                it[totpEnrolledAt] = Instant.now()
                it[totpLastUsedAt] = Instant.now()
                it[totpLastStep] = enrollStep
            }

            // Generate recovery codes (shown once)
            val recoveryCodes = generateRecoveryCodes(userId)

            // Re-read user to get updated fields
            val updatedUser = Users.selectAll()
                .where { Users.id eq userId }
                .first()

            val response = createSession(updatedUser, sessionTtlMinutes, ipAddress, userAgent)
            response.copy(recoveryCodes = recoveryCodes)
        }
    }

    /**
     * Resolves a session token to an AuthPrincipal.
     * Checks TOTP enrollment enforcement — if TOTP is required but not enrolled,
     * throws 403 unless the request path is exempt (handled by caller).
     */
    fun resolveSession(token: String, checkTotpEnrollment: Boolean = true): AuthPrincipal {
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

        // TOTP enrollment guard — gateway policy, only enforced for the session's org.
        val orgId = ctx.organizationId
        if (checkTotpEnrollment && !ctx.totpEnabled && orgId != null) {
            val enforced = transaction { isTotpEnforcedForOrg(ctx.userId, orgId) }
            if (enforced) throw ForbiddenException()
        }

        touchSessionActivity(ctx.sessionId)

        return AuthPrincipal(
            userId = ctx.userId,
            sessionId = ctx.sessionId,
            email = ctx.email,
            organizationId = ctx.organizationId,
        )
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

    /** Revokes a session. */
    fun logout(sessionId: UUID) {
        transaction {
            Sessions.update({ Sessions.id eq sessionId }) {
                it[revoked] = true
            }
        }
    }

    /** Returns the current user's summary. */
    fun me(principal: AuthPrincipal): UserSummary {
        return transaction {
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()
            userSummaryFrom(user)
        }
    }

    /** Returns the user profile wrapped with org-level permissions (if org selected). */
    fun meWithPermissions(principal: AuthPrincipal): MeResponse {
        return transaction {
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()
            val userSummary = userSummaryFrom(user)

            val cached = principal.organizationId?.let { orgId ->
                dev.tracedown.common.auth.resolveCachedPermissions(orgId, principal.userId)
            }
            val permissions = cached?.org?.let {
                OrgPermissionsDto(
                    users = it.users,
                    settings = it.settings,
                    domains = it.domains,
                    webhooks = it.webhooks,
                    notifications = it.notifications,
                    admin = it.admin,
                    workspaces = it.workspaces,
                    isOwner = it.isOwner,
                    // Sections registered by additional modules, so a host's
                    // surfaces can gate on their own permissions like built-ins.
                    extra = it.extra,
                )
            }

            val orgDefaultTimezone = principal.organizationId?.let { orgId ->
                Organizations.selectAll()
                    .where { Organizations.id eq orgId }
                    .firstOrNull()
                    ?.get(Organizations.defaultTimezone)
            }

            MeResponse(
                user = userSummary,
                organizationId = principal.organizationId?.toString(),
                permissions = permissions,
                resources = cached?.resources ?: emptyMap(),
                // Needed by anyone editing scripts (window editor prefill) —
                // not sensitive, always included.
                orgDefaultTimezone = orgDefaultTimezone,
                // Platform-config disclosure — settings readers only; others
                // get the safe default (domains UI hidden either way).
                trustedDomainMode = if (cached?.org?.settings?.canRead() == true) {
                    trustedDomainMode
                } else {
                    true
                },
            )
        }
    }

    /** Returns all active org memberships for the current user (id + name pairs). */
    fun listOrgs(userId: UUID): List<OrgMembership> {
        return transaction {
            (OrgUsers innerJoin Organizations)
                .select(Organizations.id, Organizations.name)
                .where {
                    (OrgUsers.userId eq userId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false) and
                    (Organizations.deleted eq false)
                }
                .map { row ->
                    OrgMembership(
                        id = row[Organizations.id].toString(),
                        name = row[Organizations.name],
                    )
                }
        }
    }

    /**
     * Switches the user's active organization. Revokes the current session
     * and creates a new one scoped to the target org. Updates selectedOrgId
     * so future logins default to this org.
     */
    fun switchOrg(
        principal: AuthPrincipal,
        targetOrgId: UUID,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        return transaction {
            // Verify membership in target org
            OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq principal.userId) and
                    (OrgUsers.organizationId eq targetOrgId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
                ?: throw BadRequestException(ErrorCodes.NOT_ORG_MEMBER)

            // Update selectedOrgId for future logins
            Users.update({ Users.id eq principal.userId }) {
                it[selectedOrgId] = targetOrgId
            }

            // Revoke current session
            Sessions.update({ Sessions.id eq principal.sessionId }) {
                it[revoked] = true
            }

            // Create new session directly scoped to target org (bypass selectedOrgId resolution)
            val user = Users.selectAll()
                .where { Users.id eq principal.userId }
                .first()

            val sessionId = UUID.randomUUID()
            val token = generateToken()
            val now = Instant.now()
            val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)

            Sessions.insert {
                it[id] = sessionId
                it[Sessions.userId] = principal.userId
                it[organizationId] = targetOrgId
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[Sessions.ipAddress] = ipAddress
                it[Sessions.userAgent] = userAgent
                it[Sessions.expiresAt] = expiresAt
                it[lastActiveAt] = now
                it[createdAt] = now
            }

            LoginResponse(
                token = token,
                expiresAt = expiresAt.toString(),
                user = userSummaryFrom(user),
            )
        }
    }

    /** Creates a session for a user — used by invite acceptance flow. */
    fun createSessionForUser(
        user: ResultRow,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse = createSession(user, sessionTtlMinutes, ipAddress, userAgent)

    // ── Password Reset ──

    /**
     * Initiates a password reset. Sends an email with a reset link.
     * Always returns success (timing-safe) to prevent email enumeration.
     */
    fun requestPasswordReset(
        email: String,
        emailPublisher: EmailPublisher,
        resetUrlBuilder: (String) -> String,
        expiryMinutes: Long = 60,
    ) {
        if (email.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        // Timing normalization: always take ~500ms regardless of whether user exists
        val startTime = System.currentTimeMillis()

        transaction {
            val user = Users.selectAll()
                .where { (Users.email eq email) and (Users.deleted eq false) and (Users.isActive eq true) }
                .firstOrNull()

            if (user != null) {
                val userId = user[Users.id]
                val rawToken = generateToken()
                val tokenHash = BCrypt.withDefaults().hashToString(10, rawToken.toCharArray())
                val now = Instant.now()
                val expiresAt = now.plusSeconds(expiryMinutes * 60)

                // Invalidate any existing unused reset tokens for this user
                PasswordResetTokens.update({
                    (PasswordResetTokens.userId eq userId) and (PasswordResetTokens.used eq false)
                }) {
                    it[used] = true
                }

                PasswordResetTokens.insert {
                    it[id] = UUID.randomUUID()
                    it[PasswordResetTokens.userId] = userId
                    it[PasswordResetTokens.tokenHash] = tokenHash
                    // Indexed locator — confirmation looks the row up by this
                    // digest instead of bcrypting every outstanding reset.
                    it[tokenLookup] = TokenHasher.sha256Hex(rawToken)
                    it[PasswordResetTokens.expiresAt] = expiresAt
                    it[createdAt] = now
                }

                val resetLink = resetUrlBuilder(rawToken)
                emailPublisher.publish(
                    to = email,
                    subject = "Reset your password",
                    type = "system.password-reset",
                    vars = mapOf(
                        "userName" to user[Users.displayName],
                        "expiryMinutes" to expiryMinutes.toString(),
                        "resetLink" to resetLink,
                    ),
                    source = "api-gateway",
                )
            }
        }

        // Normalize timing to prevent enumeration
        val elapsed = System.currentTimeMillis() - startTime
        if (elapsed < 500) {
            Thread.sleep(500 - elapsed)
        }
    }

    /**
     * Confirms a password reset by validating the token and setting a new password.
     * The token is single-use and expires after the configured TTL.
     */
    fun confirmPasswordReset(token: String, newPassword: String, passwordPolicy: PasswordPolicyConfig) {
        if (token.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (newPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        val errors = validatePassword(newPassword, passwordPolicy)
        if (errors.isNotEmpty()) throw BadRequestException(ErrorCodes.PASSWORD_TOO_WEAK)

        transaction {
            // Locate the single candidate by its indexed digest, with unused and
            // unexpired settled in SQL. This endpoint is unauthenticated, so it
            // must not do work proportional to how many resets are outstanding:
            // scanning every unused row and bcrypting each hash let one junk
            // request burn a bcrypt per live token. A wrong or expired token now
            // costs one indexed lookup and no bcrypt; only the located row is
            // verified — the digest locates, the bcrypt hash authenticates.
            val matchedToken = PasswordResetTokens.selectAll()
                .where {
                    (PasswordResetTokens.tokenLookup eq TokenHasher.sha256Hex(token)) and
                        (PasswordResetTokens.used eq false) and
                        (PasswordResetTokens.expiresAt greater Instant.now())
                }
                .limit(1)
                .firstOrNull()
                ?.takeIf { row ->
                    BCrypt.verifyer().verify(token.toCharArray(), row[PasswordResetTokens.tokenHash]).verified
                }
                ?: throw BadRequestException(ErrorCodes.INVALID_TOKEN)

            val userId = matchedToken[PasswordResetTokens.userId]
            val passwordHash = PasswordHasher.hash(newPassword)

            // Update password
            Users.update({ Users.id eq userId }) {
                it[Users.passwordHash] = passwordHash
            }

            // Mark token as used
            PasswordResetTokens.update({ PasswordResetTokens.id eq matchedToken[PasswordResetTokens.id] }) {
                it[used] = true
            }

            // Revoke all sessions for security
            Sessions.update({
                (Sessions.userId eq userId) and (Sessions.revoked eq false)
            }) {
                it[revoked] = true
            }
        }
    }

    // ── Profile ──

    /** Updates the user's display name. */
    fun updateProfile(userId: UUID, displayName: String?): UserSummary {
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (displayName != null) {
                if (displayName.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (displayName.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }

            Users.update({ Users.id eq userId }) {
                displayName?.let { v -> it[Users.displayName] = v }
            }

            val updated = Users.selectAll().where { Users.id eq userId }.first()
            userSummaryFrom(updated)
        }
    }

    /** Changes the user's password. Requires the current password for verification. */
    fun changePassword(userId: UUID, currentPassword: String, newPassword: String, passwordPolicy: PasswordPolicyConfig) {
        if (currentPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (newPassword.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        val errors = validatePassword(newPassword, passwordPolicy)
        if (errors.isNotEmpty()) throw BadRequestException(ErrorCodes.PASSWORD_TOO_WEAK)

        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            val hashResult = BCrypt.verifyer().verify(
                currentPassword.toCharArray(),
                user[Users.passwordHash],
            )
            if (!hashResult.verified) throw BadRequestException(ErrorCodes.INCORRECT_PASSWORD)

            val newHash = PasswordHasher.hash(newPassword)
            Users.update({ Users.id eq userId }) {
                it[passwordHash] = newHash
            }
        }
    }

    /**
     * Re-verifies the caller's identity for sensitive operations: password
     * always; a TOTP (or recovery) code when the user is enrolled. Throws on
     * any mismatch. Call within a transaction-free context.
     */
    fun verifyIdentity(userId: UUID, password: String, code: String?) {
        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            val hashResult = BCrypt.verifyer().verify(
                password.toCharArray(),
                user[Users.passwordHash],
            )
            if (!hashResult.verified) throw BadRequestException(ErrorCodes.INCORRECT_PASSWORD)

            val secret = user[Users.totpSecretEncrypted]
            val iv = user[Users.totpSecretIv]
            if (secret != null && iv != null) {
                if (code.isNullOrBlank()) throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
                if (!consumeSecondFactor(user, code)) throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
            }
        }
    }

    // ── TOTP Management ──

    /** Disables TOTP for a user. Requires a valid TOTP code or recovery code for verification. */
    fun disableTotp(userId: UUID, code: String) {
        if (code.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

            if (!user[Users.totpEnabled]) {
                throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            }

            val secret = user[Users.totpSecretEncrypted]
                ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            val iv = user[Users.totpSecretIv]
                ?: throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)

            if (!consumeSecondFactor(user, code)) {
                throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
            }

            // Clear TOTP fields. The consumed-step marker goes too: the secret
            // is gone, so a re-enrollment starts from a clean slate.
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = false
                it[totpSecretEncrypted] = null
                it[totpSecretIv] = null
                it[totpEnrolledAt] = null
                it[totpLastStep] = null
            }

            // Delete all recovery codes
            TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }
        }
    }

    /**
     * Regenerates the user's TOTP recovery codes, invalidating the old set. Requires
     * a valid TOTP (or recovery) code, and that TOTP is enrolled. Returns the fresh
     * codes once — they are never retrievable again.
     */
    fun regenerateRecoveryCodes(userId: UUID, code: String): List<String> {
        if (code.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        return transaction {
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
            if (!user[Users.totpEnabled]) throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            if (user[Users.totpSecretEncrypted] == null || user[Users.totpSecretIv] == null) {
                throw BadRequestException(ErrorCodes.TOTP_NOT_CONFIGURED)
            }
            if (!consumeSecondFactor(user, code)) {
                throw BadRequestException(ErrorCodes.INVALID_TOTP_CODE)
            }
            // Replaces the whole set (delete + insert), returning the plaintext once.
            generateRecoveryCodes(userId)
        }
    }

    /**
     * Generates 8 recovery codes for a user. Each code is 8 chars alphanumeric.
     * Stores bcrypt hashes; returns plaintext codes (shown once).
     */
    internal fun generateRecoveryCodes(userId: UUID): List<String> {
        val codes = mutableListOf<String>()
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no I/O/0/1
        val now = Instant.now()

        // Delete any existing codes
        TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }

        repeat(8) {
            val code = (1..8).map { chars[secureRandom.nextInt(chars.length)] }.joinToString("")
            val hash = BCrypt.withDefaults().hashToString(10, code.toCharArray())

            TotpRecoveryCodes.insert {
                it[id] = UUID.randomUUID()
                it[TotpRecoveryCodes.userId] = userId
                it[codeHash] = hash
                it[createdAt] = now
            }
            codes.add(code)
        }

        return codes
    }

    /**
     * Verifies a second factor for [user] — a TOTP code or a recovery code —
     * and CONSUMES it. Returns false if neither matched, or if the TOTP code
     * matched a step this account has already spent.
     *
     * Consumption is the point. A TOTP code is single-use by definition, but
     * the verifier only ever asked "does this match the current or previous
     * window", so an observed code stayed good for the rest of its window on
     * every endpoint that takes a second factor. Recording the consumed step
     * and demanding a strictly newer one closes that, and does it once for
     * every caller rather than per endpoint. Recovery codes were always
     * single-use; they are consumed by [tryRecoveryCode] as before.
     *
     * Must run inside a transaction, and [user] must be a freshly read row —
     * the replay check reads `totp_last_step` from it.
     */
    private fun consumeSecondFactor(user: ResultRow, code: String): Boolean {
        val userId = user[Users.id]
        val secret = user[Users.totpSecretEncrypted]
        val iv = user[Users.totpSecretIv]
        val step = if (secret != null && iv != null) {
            TotpUtil.matchingStep(TotpUtil.decryptSecret(secret, iv, hmacKey), code)
        } else {
            null
        }

        if (step != null) {
            // A replayed code is not then tried as a recovery code: it is a
            // TOTP code, and it is spent.
            if (!TotpPolicy.consumes(step, user[Users.totpLastStep])) return false
        } else if (!tryRecoveryCode(userId, code)) {
            return false
        }

        Users.update({ Users.id eq userId }) {
            if (step != null) it[totpLastStep] = step
            it[totpLastUsedAt] = Instant.now()
        }
        return true
    }

    /**
     * Tries to use a recovery code for TOTP verification.
     * Returns true if a valid unused code was found and consumed.
     */
    internal fun tryRecoveryCode(userId: UUID, code: String): Boolean {
        val candidates = TotpRecoveryCodes.selectAll()
            .where {
                (TotpRecoveryCodes.userId eq userId) and
                (TotpRecoveryCodes.used eq false)
            }
            .toList()

        val matched = candidates.firstOrNull { row ->
            BCrypt.verifyer().verify(code.toCharArray(), row[TotpRecoveryCodes.codeHash]).verified
        } ?: return false

        TotpRecoveryCodes.update({ TotpRecoveryCodes.id eq matched[TotpRecoveryCodes.id] }) {
            it[used] = true
            it[usedAt] = Instant.now()
        }

        return true
    }

    // ── Internals ──

    /**
     * Resolves which org the user's session will target — same logic as createSession
     * but without actually creating the session. Used at login to determine TOTP enforcement.
     */
    private fun resolveTargetOrgId(user: ResultRow): UUID? {
        val selectedOrg = user[Users.selectedOrgId]
        return if (selectedOrg != null) {
            val valid = OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq user[Users.id]) and
                    (OrgUsers.organizationId eq selectedOrg) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
            if (valid != null) selectedOrg else {
                OrgUsers.selectAll()
                    .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                    .firstOrNull()?.get(OrgUsers.organizationId)
            }
        } else {
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                .firstOrNull()?.get(OrgUsers.organizationId)
        }
    }

    /**
     * Checks if TOTP is enforced for a user in a specific org.
     * Reads from permission_cache if available, otherwise checks org + groups directly.
     */
    private fun isTotpEnforcedForOrg(userId: UUID, orgId: UUID): Boolean {
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

    /**
     * A fixed, valid bcrypt hash (cost 12, matching [PasswordHasher]) that no
     * password will ever match. An unknown account is verified against it so the
     * password step costs the same whether or not the email exists — otherwise
     * an unknown email returns before any bcrypt runs and the response time
     * enumerates accounts. Computed once, lazily.
     */
    private val dummyHash: String by lazy {
        BCrypt.withDefaults().hashToString(12, "timing-normalization-only".toCharArray())
    }

    /**
     * Verifies a password without becoming a user-enumeration oracle.
     *
     * Two properties, both deliberate:
     *  - **Constant work for unknown/credential-less accounts.** A missing email
     *    (or an invited stub with no password yet) still spends one bcrypt
     *    verification against [dummyHash] before failing, so timing does not
     *    distinguish "no such account" from "wrong password".
     *  - **Deactivation is disclosed only after the password is proven.** The
     *    account-state check used to run first, so a stranger could tell an
     *    existing-but-disabled account (`ACCOUNT_DEACTIVATED`) from a wrong
     *    password (`INVALID_CREDENTIALS`) without knowing any password. It now
     *    runs last — only a caller who already supplied the correct password
     *    learns the account is deactivated.
     *
     * TODO: per-account login throttling/lockout (a bounded counter + time-boxed
     * lock, as [TotpPolicy] does for the second factor) is a larger change and is
     * not attempted here.
     */
    private fun verifyCredentials(email: String, password: String): ResultRow {
        val user = Users.selectAll()
            .where { (Users.email eq email) and (Users.deleted eq false) }
            .firstOrNull()

        if (user == null) {
            BCrypt.verifyer().verify(password.toCharArray(), dummyHash)
            throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
        }

        val storedHash = user[Users.passwordHash]
        if (storedHash.isBlank()) {
            // An invited stub has no password yet; treat it exactly like a wrong
            // password (same work, same code) rather than revealing it exists.
            BCrypt.verifyer().verify(password.toCharArray(), dummyHash)
            throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
        }

        val hashResult = BCrypt.verifyer().verify(password.toCharArray(), storedHash)
        if (!hashResult.verified) throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

        if (!user[Users.isActive]) throw UnauthorizedException(ErrorCodes.ACCOUNT_DEACTIVATED)

        return user
    }

    /**
     * Opens a tokenless `pending_totp` session after a successful password step.
     * Its id is handed to the client as the TOTP challenge; [verifyTotp] activates
     * it. Must run inside a transaction (it does — called from [login]).
     */
    private fun createPendingSession(
        userId: UUID,
        orgId: UUID?,
        ipAddress: String?,
        userAgent: String?,
    ): UUID {
        val sessionId = UUID.randomUUID()
        val now = Instant.now()
        Sessions.insert {
            it[id] = sessionId
            it[Sessions.userId] = userId
            it[organizationId] = orgId
            it[status] = SessionStatus.PENDING_TOTP
            it[Sessions.ipAddress] = ipAddress
            it[Sessions.userAgent] = userAgent
            it[expiresAt] = now.plusSeconds(CHALLENGE_TTL_SECONDS)
            it[lastActiveAt] = now
            it[createdAt] = now
            // sessionTokenHash stays null until activation; totpAttemptCount defaults to 0.
        }
        return sessionId
    }

    private fun createSession(
        user: ResultRow,
        sessionTtlMinutes: Long,
        ipAddress: String?,
        userAgent: String?,
    ): LoginResponse {
        val sessionId = UUID.randomUUID()
        val token = generateToken()
        val now = Instant.now()
        val expiresAt = now.plusSeconds(sessionTtlMinutes * 60)

        val selectedOrg = user[Users.selectedOrgId]
        val orgId = if (selectedOrg != null) {
            val valid = OrgUsers.selectAll()
                .where {
                    (OrgUsers.userId eq user[Users.id]) and
                    (OrgUsers.organizationId eq selectedOrg) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
            if (valid != null) selectedOrg else {
                OrgUsers.selectAll()
                    .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                    .firstOrNull()?.get(OrgUsers.organizationId)
            }
        } else {
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq user[Users.id]) and (OrgUsers.status eq "active") and (OrgUsers.deleted eq false) }
                .firstOrNull()?.get(OrgUsers.organizationId)
        }

        Sessions.insert {
            it[id] = sessionId
            it[Sessions.userId] = user[Users.id]
            it[organizationId] = orgId
            it[sessionTokenHash] = TokenHasher.sha256Hex(token)
            it[Sessions.ipAddress] = ipAddress
            it[Sessions.userAgent] = userAgent
            it[Sessions.expiresAt] = expiresAt
            it[lastActiveAt] = now
            it[createdAt] = now
        }

        return LoginResponse(
            token = token,
            expiresAt = expiresAt.toString(),
            user = userSummaryFrom(user),
        )
    }

    private fun createChallenge(userId: UUID): String {
        val expiry = Instant.now().epochSecond + CHALLENGE_TTL_SECONDS
        val payload = "$userId:$expiry"
        val sig = hmacSign(payload)
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$payload:$sig".toByteArray())
    }

    private fun validateChallenge(challenge: String): UUID {
        val decoded = try {
            String(Base64.getUrlDecoder().decode(challenge))
        } catch (e: Exception) {
            throw UnauthorizedException()
        }

        val parts = decoded.split(":")
        if (parts.size != 3) throw UnauthorizedException()

        val userId = try { UUID.fromString(parts[0]) } catch (e: Exception) {
            throw UnauthorizedException()
        }
        val expiry = parts[1].toLongOrNull()
            ?: throw UnauthorizedException()
        val sig = parts[2]

        if (Instant.now().epochSecond > expiry) {
            throw UnauthorizedException(ErrorCodes.SESSION_EXPIRED)
        }

        val expectedSig = hmacSign("${parts[0]}:${parts[1]}")
        if (sig != expectedSig) {
            throw UnauthorizedException()
        }

        return userId
    }

    private fun hmacSign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hmacKey, "HmacSHA256"))
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(mac.doFinal(data.toByteArray()))
    }

    internal fun userSummaryFrom(user: ResultRow) = UserSummary(
        id = user[Users.id].toString(),
        email = user[Users.email],
        displayName = user[Users.displayName],
        totpEnabled = user[Users.totpEnabled],
        selectedOrgId = user[Users.selectedOrgId]?.toString(),
    )

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun encodeBase32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val sb = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                bitsLeft -= 5
                sb.append(alphabet[(buffer shr bitsLeft) and 0x1F])
            }
        }
        if (bitsLeft > 0) {
            sb.append(alphabet[(buffer shl (5 - bitsLeft)) and 0x1F])
        }
        return sb.toString()
    }

    /**
     * Organizations [userId] owns, with whether they hold any other membership.
     *
     * "Sole member" means no other non-deleted membership row exists — a pending
     * invite counts as somebody, deliberately: it is a person who was told they
     * were getting an organization, and deleting it out from under them should
     * be an explicit hand-off, not a side effect of somebody closing an account.
     */
    fun ownedOrgs(userId: UUID): List<OwnedOrgSummary> = transaction {
        Organizations.selectAll()
            .where { (Organizations.ownerId eq userId) and (Organizations.deleted eq false) }
            .map { org ->
                val orgId = org[Organizations.id]
                val others = OrgUsers.selectAll()
                    .where {
                        (OrgUsers.organizationId eq orgId) and
                        (OrgUsers.userId neq userId) and
                        (OrgUsers.deleted eq false)
                    }
                    .limit(1)
                    .any()
                OwnedOrgSummary(
                    id = orgId.toString(),
                    name = org[Organizations.name],
                    soleMember = !others,
                )
            }
    }

    /**
     * Closes the user's own account.
     *
     * Confirmed exactly as deleting an organization is — password plus a second
     * factor when one is enrolled ([verifyIdentity]) — because it sits next to
     * that button and ends just as much.
     *
     * An owned organization blocks the closure. Where the account is its only
     * member, [deleteOwnedOrgs] lets the request take the organization with it,
     * through the normal deletion path so the same teardown runs; anything with
     * other members has to be handed over first, and comes back as a 409 naming
     * what is in the way.
     *
     * The account itself is never soft-deleted here directly. Every membership
     * is ended the way a removal ends one — access revoked before the row goes —
     * and [AccountLifecycle.reconcile] then draws the same conclusion it draws
     * for a member removed by an admin: no memberships left, so the account is
     * scheduled for deletion, its sessions and silences purged with it. One
     * policy, one code path.
     *
     * The one thing that differs from the admin-removal case is *how long* the
     * row is kept. [AccountLifecycle.ORPHAN_GRACE_SECONDS] exists so an account
     * that lost its last membership without asking can be revived by a re-invite.
     * Nothing here was unasked for: the holder proved who they were and pressed
     * the button. So the window is the operator's configured retention
     * ([purgeRetentionDays], `systemLimits.purgeRetentionDays`) — the same one
     * the deleted organizations above are given, and the same one whose zero
     * default is what makes "delete" mean deleted on this install.
     */
    fun deleteAccount(
        userId: UUID,
        password: String,
        code: String? = null,
        deleteOwnedOrgs: Boolean = false,
        purgeRetentionDays: Int = 0,
    ) {
        // Identity first: nothing below runs, and nothing is written to any
        // audit log, until the person at the keyboard has proved who they are.
        verifyIdentity(userId, password, code)

        val toDelete = transaction {
            val owned = ownedOrgs(userId)
            val blocking = AccountClosurePolicy.blocking(owned, deleteOwnedOrgs)
            if (blocking.isNotEmpty()) {
                throw ConflictException(
                    ErrorCodes.ACCOUNT_OWNS_ORGANIZATIONS,
                    details = buildJsonObject {
                        put("organizations", buildJsonArray { blocking.forEach { add(JsonPrimitive(it.name)) } })
                    },
                )
            }

            val displayName = Users.selectAll()
                .where { Users.id eq userId }
                .firstOrNull()?.get(Users.displayName)

            // The account is its own actor: this is the one deletion of a member
            // nobody else ordered, and the log should say so rather than leave a
            // membership vanishing with no entry at all.
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq userId) and (OrgUsers.deleted eq false) }
                .map { it[OrgUsers.organizationId] }
                .distinct()
                .forEach { orgId ->
                    AuditService.log(
                        orgId, userId, "close.account", "user", userId.toString(),
                        entityDisplayName = displayName,
                    )
                }

            owned.map { UUID.fromString(it.id) }
        }

        // Owned organizations go through the ordinary deletion path — same owner
        // check, same interception seam, same probe teardown — rather than a
        // second, quieter copy of it here.
        toDelete.forEach { OrgSettingsController.deleteOrg(it, userId, purgeRetentionDays) }

        transaction {
            val now = Instant.now()
            // The operator's retention setting, in seconds. Zero — the default —
            // makes every row below purgeable immediately, which is the whole
            // contract of `systemLimits.purgeRetentionDays = 0`.
            val retentionSeconds = purgeRetentionDays * 86_400L
            val memberships = OrgUsers.selectAll()
                .where { (OrgUsers.userId eq userId) and (OrgUsers.deleted eq false) }
                .map { it[OrgUsers.id] to it[OrgUsers.organizationId] }

            memberships.forEach { (membershipId, orgId) ->
                MembershipAccess.revokeAll(orgId, membershipId)
                OrgUsers.update({ OrgUsers.id eq membershipId }) {
                    it[deleted] = true
                    it[deletedAt] = now
                    it[purgeAfter] = now.plusSeconds(retentionSeconds)
                    it[isActive] = false
                }
            }

            // Sessions and silences go with it. The window is the configured
            // retention, not the orphan grace: this closure was requested.
            AccountLifecycle.reconcile(userId, now, retentionSeconds)

            memberships.forEach { (_, orgId) ->
                RealtimePublisher.publish("org:$orgId", orgId, "user.removed", buildJsonObject {
                    put("userId", userId.toString())
                })
            }
        }
    }
}
