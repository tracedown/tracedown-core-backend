package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.silences.SilenceController
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.silences.UpdateSilenceRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * The calling user's own notification silences — not an organization-wide
 * mute. A service's maintenance window is a field on the service.
 */
fun Route.silenceRoutes() {
    /** Lists the caller's silences. Paged with `page` and `pageSize`. */
    get("/silences") {
        val caller = call.apiCaller
        call.respond(SilenceController.list(caller.orgId, caller.userId, publicPaging(call)))
    }

    /** Creates a silence for the caller. */
    post("/silences") {
        val caller = call.apiCaller
        val body = tryReceive<CreateSilenceRequest>(call)
        call.respond(SilenceController.create(caller.orgId, caller.userId, body))
    }

    /** Returns one of the caller's silences. */
    get("/silences/{id}") {
        val caller = call.apiCaller
        val silenceId = call.pathUuid("id")
        call.respond(SilenceController.get(caller.orgId, caller.userId, silenceId))
    }

    /** Updates a silence's channel, configuration or quiet hours. */
    patch("/silences/{id}") {
        val caller = call.apiCaller
        val silenceId = call.pathUuid("id")
        val body = tryReceive<UpdateSilenceRequest>(call)
        call.respond(SilenceController.update(caller.orgId, caller.userId, silenceId, body))
    }

    /** Removes one of the caller's silences. */
    delete("/silences/{id}") {
        val caller = call.apiCaller
        val silenceId = call.pathUuid("id")
        SilenceController.delete(caller.orgId, caller.userId, silenceId)
        call.respond(mapOf("ok" to true))
    }
}
