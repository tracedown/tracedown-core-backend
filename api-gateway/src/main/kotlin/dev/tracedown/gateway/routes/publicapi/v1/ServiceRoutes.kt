package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.runs.RunRequestController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.services.RunRequested
import dev.tracedown.gateway.data.services.SetAllowedAgentsRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.services.UpdateScriptRequest
import dev.tracedown.gateway.data.services.UpdateServiceRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Services: a monitored API, its Lace script, its schedule and where it runs.
 */
fun Route.serviceRoutes() {
    /**
     * Lists the services of the project named by the `projectId` query
     * parameter (required) that the caller may see, oldest first. Paged with
     * `page` and `pageSize`.
     */
    get("/services") {
        val caller = call.apiCaller
        val projectId = call.requiredUuidQuery("projectId")
        call.respond(ServiceController.list(caller.orgId, projectId, caller.userId, publicPaging(call)))
    }

    /**
     * Creates a service in the project named by the body's `projectId`. A
     * `script`, when the body carries one, is validated and saved as a script
     * save is — and switches the service on, as the first save does, unless
     * `isActive` is false.
     */
    post("/services") {
        val caller = call.apiCaller
        val body = tryReceive<CreateServiceRequest>(call)
        val projectId = parseUuid(body.projectId, "project ID")
        call.respond(ServiceController.create(caller.orgId, projectId, body, caller.userId))
    }

    /** Returns one service. */
    get("/services/{id}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(ServiceController.get(caller.orgId, serviceId, caller.userId))
    }

    /** Updates a service's configuration. Fields left out are unchanged. */
    patch("/services/{id}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val body = tryReceive<UpdateServiceRequest>(call)
        call.respond(ServiceController.update(caller.orgId, serviceId, body, caller.userId))
    }

    /** Deletes a service. */
    delete("/services/{id}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        ServiceController.delete(caller.orgId, serviceId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Replaces the service's Lace script. Validated before it is saved. */
    patch("/services/{id}/script") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val body = tryReceive<UpdateScriptRequest>(call)
        call.respond(ServiceController.updateScript(caller.orgId, serviceId, body, caller.userId))
    }

    /** The service together with its most recent runs, in one read. */
    get("/services/{id}/snapshot") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(ServiceController.snapshot(caller.orgId, serviceId, caller.userId))
    }

    /** Enables or disables the service. */
    patch("/services/{id}/toggle") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggle(caller.orgId, serviceId, body, caller.userId))
    }

    /**
     * Asks for one run of the service now, outside its schedule. Answers 202
     * with `requestedAt` and `runId` once the request is recorded; the run is
     * followed at `/services/{id}/runs/{runId}`, and its result is filed under
     * that id. A service that would not run is refused instead of queued for
     * nothing: 409 `script_missing`, or 409 `service_inactive` when it is
     * switched off.
     */
    post("/services/{id}/run") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val ticket = ServiceController.triggerRun(caller.orgId, serviceId, caller.userId, refuseUnrunnable = true)
        call.respond(
            HttpStatusCode.Accepted,
            RunRequested(requestedAt = ticket.requestedAt.toString(), runId = ticket.runId.toString()),
        )
    }

    /**
     * Where a run asked for with `POST …/run` stands: `pending`, then `done`
     * or `skipped` (with the result, and for a skip its reason), or `expired`
     * when nothing was recorded within the gateway's bound. 404 for an id that
     * is not a run of this service.
     */
    get("/services/{id}/runs/{runId}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val runId = call.pathUuid("runId")
        call.respond(RunRequestController.status(caller.orgId, serviceId, runId, caller.userId))
    }

    /** The agents the service may run on, by slug. An empty list means any agent. */
    get("/services/{id}/agents") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(ServiceController.listAllowedAgents(caller.orgId, serviceId, caller.userId))
    }

    /**
     * Replaces the agents the service may run on. An empty list means any
     * agent. The slugs are the ones `GET /agents` lists; an unknown one is
     * refused, 400 `field_invalid` with `details.unknown`.
     */
    put("/services/{id}/agents") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val body = tryReceive<SetAllowedAgentsRequest>(call)
        call.respond(ServiceController.setAllowedAgents(caller.orgId, serviceId, body.slugs, caller.userId))
    }
}
