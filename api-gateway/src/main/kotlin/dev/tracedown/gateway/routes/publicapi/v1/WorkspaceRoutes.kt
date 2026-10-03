package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.UpdateWorkspaceRequest
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
 * Workspaces: the top level of the organization's resources.
 */
fun Route.workspaceRoutes() {
    /** Lists the workspaces the caller may see, oldest first. Paged with `page` and `pageSize`. */
    get("/workspaces") {
        val caller = call.apiCaller
        call.respond(WorkspaceController.list(caller.orgId, caller.userId, publicPaging(call)))
    }

    /** Creates a workspace. */
    post("/workspaces") {
        val caller = call.apiCaller
        val body = tryReceive<CreateWorkspaceRequest>(call)
        call.respond(WorkspaceController.create(caller.orgId, body, caller.userId))
    }

    /** Returns one workspace. */
    get("/workspaces/{id}") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        call.respond(WorkspaceController.get(caller.orgId, wsId, caller.userId))
    }

    /** Renames a workspace. */
    patch("/workspaces/{id}") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        val body = tryReceive<UpdateWorkspaceRequest>(call)
        call.respond(WorkspaceController.update(caller.orgId, wsId, body, caller.userId))
    }

    /** Deletes a workspace, with everything in it. */
    delete("/workspaces/{id}") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        WorkspaceController.delete(caller.orgId, wsId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    /**
     * Enables or disables every service in every project of the workspace, in
     * one transaction. Reports what moved, what was already there, and what was
     * skipped.
     */
    patch("/workspaces/{id}/services-toggle") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggleWorkspaceServices(caller.orgId, wsId, body.isActive, caller.userId))
    }
}
