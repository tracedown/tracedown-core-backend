package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.orgs.ResourceAccessController
import dev.tracedown.gateway.data.orgs.UpsertAccessRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put

/**
 * Who may see or change a workspace, project or service. `resourceType` is
 * `workspace`, `project` or `service`; a principal is a `user` or a `group`.
 * Every call here needs write on the resource.
 */
fun Route.accessRoutes() {
    /** Lists the users and groups granted access to the resource, and at which level. */
    get("/access/{resourceType}/{resourceId}") {
        val caller = call.apiCaller
        val resourceType = call.pathText("resourceType")
        val resourceId = call.pathUuid("resourceId")
        call.respond(ResourceAccessController.list(caller.orgId, resourceType, resourceId, caller.userId))
    }

    /** Grants a user or group access to the resource, or changes the level of a grant it has. */
    put("/access/{resourceType}/{resourceId}") {
        val caller = call.apiCaller
        val resourceType = call.pathText("resourceType")
        val resourceId = call.pathUuid("resourceId")
        val body = tryReceive<UpsertAccessRequest>(call)
        ResourceAccessController.upsert(caller.orgId, resourceType, resourceId, body, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Removes a user's or group's grant on the resource. */
    delete("/access/{resourceType}/{resourceId}/{principalType}/{principalId}") {
        val caller = call.apiCaller
        val resourceType = call.pathText("resourceType")
        val resourceId = call.pathUuid("resourceId")
        ResourceAccessController.remove(
            caller.orgId, resourceType, resourceId,
            call.pathText("principalType"), call.pathText("principalId"), caller.userId,
        )
        call.respond(mapOf("ok" to true))
    }
}
