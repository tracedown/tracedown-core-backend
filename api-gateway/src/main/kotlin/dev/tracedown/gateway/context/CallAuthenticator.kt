package dev.tracedown.gateway.context

import dev.tracedown.common.auth.ApiKeyFormat
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.net.PathCanonicalizer
import dev.tracedown.gateway.util.ApiNamespace
import dev.tracedown.gateway.util.UnauthorizedException
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/**
 * Decides who is calling, once per request.
 *
 * **The namespace picks the credential.** A request to the key-authenticated
 * API ([ApiNamespace]) is authenticated by an API key and by nothing else; every
 * other request is authenticated by a session and by nothing else. The path
 * decides which table the presented token is looked up in — never the token's
 * own shape, which the caller controls.
 *
 * That the rule lives in the one function everything already calls is the
 * point. Code that runs ahead of the handlers and asks "who is this?" — to
 * refuse a caller, or to let an unauthenticated one through to its 401 — gets a
 * true answer for both kinds of credential without knowing there are two. A
 * second, key-only entry point would have been invisible to all of it, and a
 * caller it could not name is a caller it lets pass.
 *
 * The outcome is kept on the call — the caller, or the refusal — and later
 * askers reuse it, so the lookup, the rate count and the last-used stamp happen
 * once per request however many times the question is asked.
 */
object CallAuthenticator {

    private sealed interface Outcome {
        class Authenticated(val caller: ResolvedCaller) : Outcome
        class Failed(val error: Throwable) : Outcome
    }

    private val outcomeKey = AttributeKey<Outcome>("CallAuthOutcome")

    /** The caller of [call], or the exception that says why there is none. */
    fun resolve(call: ApplicationCall): ResolvedCaller {
        val outcome = call.attributes.getOrNull(outcomeKey) ?: run {
            // Whatever the answer was — a caller, a refusal, or the database
            // being away — it is the answer for this request: asking again
            // would spend the key's budget again and append the headers twice.
            val fresh = try {
                Outcome.Authenticated(authenticate(call))
            } catch (e: Throwable) {
                Outcome.Failed(e)
            }
            call.attributes.put(outcomeKey, fresh)
            fresh
        }
        return when (outcome) {
            is Outcome.Authenticated -> outcome.caller
            is Outcome.Failed -> throw outcome.error
        }
    }

    private fun authenticate(call: ApplicationCall): ResolvedCaller {
        // Classified on the canonical path, so an equivalent spelling of a path
        // cannot move a request into the other namespace. A path that will not
        // canonicalize belongs to neither.
        val path = PathCanonicalizer.canonicalize(call.request.local.uri)
        val public = path != null && ApiNamespace.isPublic(path)

        val header = call.request.headers["Authorization"]
            ?: if (public) {
                ApiKeyAuth.refuseWithoutKey(call, UnauthorizedException(ErrorCodes.MISSING_AUTH_HEADER))
            } else {
                throw UnauthorizedException(ErrorCodes.MISSING_AUTH_HEADER)
            }

        val token = if (header.startsWith("Bearer ", ignoreCase = true)) {
            header.substring(7)
        } else {
            header
        }

        if (token.isBlank()) {
            if (public) ApiKeyAuth.refuseWithoutKey(call, UnauthorizedException())
            throw UnauthorizedException()
        }

        if (path == null) throw UnauthorizedException()

        return if (public) {
            ApiKeyAuth.authenticate(call, token)
        } else {
            authenticateSession(token)
        }
    }

    private fun authenticateSession(token: String): ResolvedCaller =
        try {
            SessionAuth.authenticateSession(token)
        } catch (e: UnauthorizedException) {
            // An API key sent to a session-only route is not a session, and the
            // generic answer leaves its holder guessing at a key that is fine.
            // Only said once the session lookup has already failed.
            if (e.code == ErrorCodes.INVALID_TOKEN && ApiKeyFormat.looksLikeKey(token)) {
                throw UnauthorizedException(ErrorCodes.SESSION_REQUIRED)
            }
            throw e
        }
}
