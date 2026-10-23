package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.data.services.ValidateScriptRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Lace scripts, judged without being saved.
 */
fun Route.scriptRoutes() {
    /**
     * What a save would make of a script: the Lace validator's findings and
     * every refusal the platform's policies would make, with the variables and
     * schedule of the service named by `serviceId` when given. Changes
     * nothing, so a read-only key may ask (see [dev.tracedown.gateway.routes.publicapi.PublicApi]).
     * Read access to the service suffices.
     */
    post("/scripts/validate") {
        val caller = call.apiCaller
        val body = tryReceive<ValidateScriptRequest>(call)
        val serviceId = body.serviceId?.let { parseUuid(it, "serviceId") }
        call.respond(ServiceController.validate(caller.orgId, caller.userId, body.script, serviceId))
    }
}
