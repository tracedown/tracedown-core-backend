package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.projects.UpdateProjectRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * Projects: groups of services inside a workspace.
 */
fun Route.projectRoutes() {
    /**
     * Lists the projects of the workspace named by the `workspaceId` query
     * parameter (required) that the caller may see, oldest first. Paged with
     * `page` and `pageSize`.
     */
    get("/projects") {
        val caller = call.apiCaller
        val wsId = call.requiredUuidQuery("workspaceId")
        call.respond(ProjectController.list(caller.orgId, wsId, caller.userId, publicPaging(call)))
    }

    /** Creates a project in the workspace named by the body's `workspaceId`. */
    post("/projects") {
        val caller = call.apiCaller
        val body = tryReceive<CreateProjectRequest>(call)
        val wsId = parseUuid(body.workspaceId, "workspace ID")
        call.respond(ProjectController.create(caller.orgId, wsId, body, caller.userId))
    }

    /** Returns one project. */
    get("/projects/{id}") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        call.respond(ProjectController.get(caller.orgId, projId, caller.userId))
    }

    /** Renames a project. */
    patch("/projects/{id}") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        val body = tryReceive<UpdateProjectRequest>(call)
        call.respond(ProjectController.update(caller.orgId, projId, body, caller.userId))
    }

    /** Deletes a project, with its services. */
    delete("/projects/{id}") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        ProjectController.delete(caller.orgId, projId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    /**
     * Enables or disables every service in the project, in one transaction.
     * Reports what moved, what was already there, and what was skipped.
     */
    patch("/projects/{id}/services-toggle") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggleProjectServices(caller.orgId, projId, body.isActive, caller.userId))
    }
}
