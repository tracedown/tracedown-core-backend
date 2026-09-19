package dev.tracedown.gateway.routes.v1.orgs

import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.controllers.orgs.InviteController
import dev.tracedown.gateway.data.orgs.AcceptInviteRequest
import dev.tracedown.gateway.data.orgs.InviteRequest
import java.util.UUID
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.gateway.util.clientIp
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post

/**
 * @OpenAPITag Invites
 * Organization invitations — send, list, revoke, view, and accept invites.
 */
@Resource("/api/v1/invites")
class Invites {
    @Resource("{token}")
    class ByToken(val parent: Invites = Invites(), val token: String) {
        @Resource("accept")
        class Accept(val parent: ByToken)
    }
}

fun Route.inviteRoutes(appConfig: AppConfig, emailPublisher: EmailPublisher) {
    /** Sends an invitation email to a user. Resends if already pending (with cooldown). */
    post<Invites> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<InviteRequest>(call)

        val result = InviteController.invite(
            orgId = orgId,
            email = body.email,
            invitedByUserId = principal.userId,
            appConfig = appConfig,
            emailPublisher = emailPublisher,
            groupIds = body.groupIds ?: emptyList(),
        )
        call.respond(result)
    }

    /** Lists pending invites for the organization. */
    get<Invites> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(InviteController.listPendingInvites(orgId, principal.userId, pfs))
    }

    /** Revokes a pending invite. The token parameter is the invite ID (UUID). */
    delete<Invites.ByToken> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val inviteId = parseUuid(resource.token, "invite ID")
        InviteController.revokeInvite(orgId, inviteId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Returns invite info (org name, email) for the accept form. Public endpoint. */
    get<Invites.ByToken> { resource ->
        call.respond(InviteController.getInviteInfo(resource.token))
    }

    /**
     * Accepts an invite. Public endpoint, but for an email that already has an
     * account the caller must be signed in as that user (proven by the optional
     * session) — a new user instead sets a password + name here. Returns the
     * outcome (new session, accepted, or login-required).
     */
    post<Invites.ByToken.Accept> { resource ->
        val body = tryReceive<AcceptInviteRequest>(call)

        // Resolve the session if one is present; absence just means "not signed in".
        val authenticatedUserId = resolveOptionalSession(call)

        val result = InviteController.acceptInvite(
            token = resource.parent.token,
            password = body.password,
            displayName = body.displayName,
            authenticatedUserId = authenticatedUserId,
            appConfig = appConfig,
            ipAddress = call.clientIp(),
            userAgent = call.request.headers["User-Agent"],
        )
        call.respond(result)
    }
}

/** The signed-in user's id, or null when no valid session accompanies the call. */
private fun resolveOptionalSession(call: io.ktor.server.application.ApplicationCall): UUID? =
    runCatching {
        val header = call.request.headers["Authorization"] ?: return@runCatching null
        val token = if (header.startsWith("Bearer ", ignoreCase = true)) header.substring(7) else header
        if (token.isBlank()) null
        // TOTP enrollment isn't re-checked here: the session was already fully
        // established at login; we only need to know who the caller is.
        else AuthController.resolveSession(token, checkTotpEnrollment = false).userId
    }.getOrNull()
