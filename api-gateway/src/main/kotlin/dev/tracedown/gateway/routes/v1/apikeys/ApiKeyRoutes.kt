package dev.tracedown.gateway.routes.v1.apikeys

import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post

/**
 * @OpenAPITag API Keys
 * An organization's oversight of the API keys that act in it: list, revoke,
 * delete. Keys are minted by the user they act as — see `/api/v1/me/api-keys`.
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
    /** Lists every API key acting in the organization, whoever holds it. */
    get<ApiKeys> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(ApiKeyController.listForOrg(orgId, principal.userId, pfs))
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
