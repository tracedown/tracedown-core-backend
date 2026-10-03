package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.routes.publicapi.apiCaller
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The calling API key.
 */
fun Route.keyRoutes() {
    /**
     * Describes the key the request was made with: what it is called, what it
     * may do, when it expires, and the organization and user it acts for. The
     * way for a client to check a key before relying on it.
     */
    get("/key") {
        val caller = call.apiCaller
        call.respond(ApiKeyController.describe(caller.keyId))
    }
}
