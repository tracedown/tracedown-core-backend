package dev.tracedown.gateway.context

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.UnauthorizedException
import java.util.UUID

/** How a request proved who it is. Which kind is accepted depends on the route namespace — see `requireAuth`. */
sealed interface Credential {
    /** A signed-in session: the dashboard's credential. */
    data class Session(val sessionId: UUID) : Credential

    /**
     * An API key. [access] is the key's own ceiling (an `AccessLevel`: read or
     * write), applied on top of the permissions of the user the key acts as.
     */
    data class ApiKey(val keyId: UUID, val access: Short) : Credential
}

/**
 * The authenticated caller of a request: a user, acting in at most one
 * organization, by way of a [credential].
 *
 * An API key acts as a user, so a key principal carries that user's id and the
 * key's organization, and every permission check keyed on [userId] meets it
 * exactly as it would meet that user's session.
 */
data class AuthPrincipal(
    val userId: UUID,
    val email: String,
    val organizationId: UUID?,
    val credential: Credential,
) {
    constructor(userId: UUID, sessionId: UUID, email: String, organizationId: UUID?) :
        this(userId, email, organizationId, Credential.Session(sessionId))

    /**
     * The session behind this request. Refuses rather than inventing one when
     * the caller authenticated with an API key: anything that needs a session
     * (signing out, listing sessions, changing the password) is not something a
     * key may do, and code that asks for one here fails closed.
     */
    val sessionId: UUID
        get() = (credential as? Credential.Session)?.sessionId
            ?: throw UnauthorizedException(ErrorCodes.SESSION_REQUIRED)
}

/**
 * An authenticated caller, plus what the TOTP enrolment guard needs to know
 * about them, plus what to do once every guard has let them through —
 * bookkeeping that should say "this credential was used" only when it was.
 * Run at most once per request.
 */
class ResolvedCaller(
    val principal: AuthPrincipal,
    val totpEnabled: Boolean,
    private val onAdmitted: (() -> Unit)? = null,
) {
    @Volatile
    private var admitted = false

    fun admitted() {
        if (admitted) return
        admitted = true
        onAdmitted?.invoke()
    }
}
