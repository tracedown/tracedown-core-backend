package dev.tracedown.gateway.routes.v1.apikeys

import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post

/**
 * @OpenAPITag API Keys
 * API key management: create, list, revoke, delete.
 */
@Resource("/api/v1/api-keys")
class ApiKeys {
    @Resource("{id}")
    class ById(val parent: ApiKeys = ApiKeys(), val id: String) {
        @Resource("revoke")
        class Revoke(val parent: ById)
    }
}

fun Route.apiKeyRoutes() {
    // DEFERRED (SEC-M2): API-key authentication is not implemented. Nothing in
    // the gateway verifies an `X-Api-Key` header, so a key minted here would
    // authenticate no request — a dead credential. Creation and listing are
    // disabled (501) so no one is handed one, while revoke/delete stay live to
    // clean up anything that already exists. To re-enable, restore the two
    // handler bodies below (kept verbatim in comments) once a verifier ships.

    /** Creates a new API key — DISABLED until an X-Api-Key verifier exists. */
    post<ApiKeys> {
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "api_keys_deferred"))
        // val (principal, orgId) = requireAuthWithOrg(call)
        // val body = tryReceive<CreateApiKeyRequest>(call)
        // val result = ApiKeyController.create(orgId, body, principal.userId)
        // call.respond(HttpStatusCode.Created, result)
    }

    /** Lists API keys — DISABLED until an X-Api-Key verifier exists. */
    get<ApiKeys> {
        call.respond(HttpStatusCode.NotImplemented, mapOf("error" to "api_keys_deferred"))
        // val (principal, orgId) = requireAuthWithOrg(call)
        // val pfs = parsePfsParams(call)
        // val result = ApiKeyController.list(orgId, principal.userId, pfs)
        // call.respond(result)
    }

    /** Revokes an API key (cannot be undone). */
    post<ApiKeys.ById.Revoke> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val keyId = parseUuid(resource.parent.id, "id")
        ApiKeyController.revoke(orgId, keyId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Soft-deletes an API key. */
    delete<ApiKeys.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val keyId = parseUuid(resource.id, "id")
        ApiKeyController.delete(orgId, keyId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
