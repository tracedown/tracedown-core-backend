package dev.tracedown.gateway.routes.v1.me

import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.controllers.me.UserDataController
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.gateway.data.me.ChangeEmailRequest
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * @OpenAPITag Me
 * Account-scoped data: personal data export, email change, and the caller's
 * own API keys.
 */
@Resource("/api/v1/me")
class Me {
    @Resource("export")
    class Export(val parent: Me = Me())

    @Resource("email")
    class Email(val parent: Me = Me())

    @Resource("api-keys")
    class ApiKeys(val parent: Me = Me()) {
        @Resource("{id}")
        class ById(val parent: ApiKeys = ApiKeys(), val id: String) {
            @Resource("revoke")
            class Revoke(val parent: ById)
        }
    }
}

fun Route.meRoutes() {
    /**
     * Returns a single JSON document of all data stored about the calling
     * user (secrets excluded). Versioned envelope — see UserDataExport.
     */
    get<Me.Export> {
        val principal = requireAuth(call)
        call.respond(UserDataController.export(principal.userId, principal.sessionId))
    }

    /**
     * Changes the account email. Requires the current password, plus a TOTP
     * code when enrolled. Revokes all other sessions and returns the updated
     * profile.
     */
    post<Me.Email> {
        val principal = requireAuth(call)
        val body = tryReceive<ChangeEmailRequest>(call)
        val result = UserDataController.changeEmail(
            userId = principal.userId,
            sessionId = principal.sessionId,
            orgId = principal.organizationId,
            request = body,
        )
        call.respond(result)
    }

    /** Lists the caller's own API keys, across every organization they belong to. */
    get<Me.ApiKeys> {
        val principal = requireAuth(call)
        call.respond(ApiKeyController.listOwn(principal.userId, parsePfsParams(call)))
    }

    /**
     * Mints an API key that acts as the caller in their current organization.
     * Requires the current password, plus a TOTP code when enrolled. The key
     * is in the response and nowhere else afterwards.
     */
    post<Me.ApiKeys> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateApiKeyRequest>(call)
        // The body is a credential; nothing between here and the caller keeps it.
        call.response.headers.append(HttpHeaders.CacheControl, "no-store")
        call.respond(HttpStatusCode.Created, ApiKeyController.create(orgId, body, principal.userId))
    }

    /** Revokes one of the caller's own API keys (cannot be undone). */
    post<Me.ApiKeys.ById.Revoke> { resource ->
        val principal = requireAuth(call)
        ApiKeyController.revokeOwn(principal.userId, parseUuid(resource.parent.id, "id"))
        call.respond(mapOf("ok" to true))
    }

    /** Deletes one of the caller's own API keys. */
    delete<Me.ApiKeys.ById> { resource ->
        val principal = requireAuth(call)
        ApiKeyController.deleteOwn(principal.userId, parseUuid(resource.id, "id"))
        call.respond(mapOf("ok" to true))
    }
}
