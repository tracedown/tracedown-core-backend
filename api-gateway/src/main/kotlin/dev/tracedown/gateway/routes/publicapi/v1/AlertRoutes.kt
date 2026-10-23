package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.alerts.SystemAlertController
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.fieldError
import dev.tracedown.gateway.util.publicPaging
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/**
 * The warning log: what the platform has noticed about the organization's
 * agents and runs. Read under the permission the dashboard's banners need.
 */
fun Route.alertRoutes() {
    /**
     * `state=active` (default): the latest undismissed alert of each type, as
     * the dashboard's banners show them. `state=all`: every episode, newest
     * first. Paged with `page` and `pageSize`.
     */
    get("/alerts") {
        val caller = call.apiCaller
        val history = when (call.request.queryParameters["state"] ?: SystemAlertController.ACTIVE) {
            SystemAlertController.ACTIVE -> false
            SystemAlertController.ALL -> true
            else -> throw fieldError("state")
        }
        call.respond(SystemAlertController.listPublic(caller.orgId, caller.userId, history, publicPaging(call)))
    }

    /** Dismisses an alert for the caller only. */
    post("/alerts/{id}/dismiss") {
        val caller = call.apiCaller
        val alertId = call.pathUuid("id")
        SystemAlertController.dismiss(caller.orgId, alertId, caller.userId)
        call.respond(mapOf("ok" to true))
    }
}
