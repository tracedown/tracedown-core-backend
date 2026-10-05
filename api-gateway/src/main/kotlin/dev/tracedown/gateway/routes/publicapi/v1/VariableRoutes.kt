package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.orgs.OrgVariableController
import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.variables.VariableHierarchyController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
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
 * Variables at organization, workspace, project and service scope. Values of
 * encrypted variables come back masked; there is no way to read one back
 * through this API.
 */
fun Route.variableRoutes() {
    // ── Organization ──

    /** Lists the organization's variables, values of encrypted ones masked. Paged with `page` and `pageSize`. */
    get("/variables") {
        val caller = call.apiCaller
        call.respond(OrgVariableController.list(caller.orgId, caller.userId, publicPaging(call)))
    }

    /** Creates an organization variable. Type: `secret`, `variable` (default), or `metric`. */
    post("/variables") {
        val caller = call.apiCaller
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(OrgVariableController.create(caller.orgId, body, caller.userId))
    }

    /** Updates an organization variable. */
    patch("/variables/{varId}") {
        val caller = call.apiCaller
        val varId = call.pathUuid("varId")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(OrgVariableController.update(caller.orgId, varId, body, caller.userId))
    }

    /** Deletes an organization variable. */
    delete("/variables/{varId}") {
        val caller = call.apiCaller
        val varId = call.pathUuid("varId")
        OrgVariableController.delete(caller.orgId, varId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    // ── Workspace ──

    /** Lists a workspace's variables, values of encrypted ones masked. Paged with `page` and `pageSize`. */
    get("/workspaces/{id}/variables") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        call.respond(WorkspaceController.listVariables(caller.orgId, wsId, caller.userId, publicPaging(call)))
    }

    /** Every variable a workspace sees: its own and the organization's, with those locked above it. */
    get("/workspaces/{id}/variables/hierarchy") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        call.respond(VariableHierarchyController.forWorkspace(caller.orgId, wsId, caller.userId))
    }

    /** Creates a workspace variable. Type: `secret`, `variable` (default), or `metric`. */
    post("/workspaces/{id}/variables") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(WorkspaceController.createVariable(caller.orgId, wsId, body, caller.userId))
    }

    /** Updates a workspace variable. */
    patch("/workspaces/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(WorkspaceController.updateVariable(caller.orgId, wsId, varId, body, caller.userId))
    }

    /** Deletes a workspace variable. */
    delete("/workspaces/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val wsId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        WorkspaceController.deleteVariable(caller.orgId, wsId, varId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    // ── Project ──

    /** Lists a project's variables, values of encrypted ones masked. Paged with `page` and `pageSize`. */
    get("/projects/{id}/variables") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        call.respond(ProjectController.listVariables(caller.orgId, projId, caller.userId, publicPaging(call)))
    }

    /** Every variable a project sees: its own, its workspace's and the organization's, with those locked above it. */
    get("/projects/{id}/variables/hierarchy") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        call.respond(VariableHierarchyController.forProject(caller.orgId, projId, caller.userId))
    }

    /** Creates a project variable. Type: `secret`, `variable` (default), or `metric`. */
    post("/projects/{id}/variables") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(ProjectController.createVariable(caller.orgId, projId, body, caller.userId))
    }

    /** Updates a project variable. */
    patch("/projects/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(ProjectController.updateVariable(caller.orgId, projId, varId, body, caller.userId))
    }

    /** Deletes a project variable. */
    delete("/projects/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val projId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        ProjectController.deleteVariable(caller.orgId, projId, varId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    // ── Service ──

    /** Lists a service's variables, values of encrypted ones masked. Paged with `page` and `pageSize`. */
    get("/services/{id}/variables") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(ServiceController.listVariables(caller.orgId, serviceId, caller.userId, publicPaging(call)))
    }

    /** Every variable a service sees, from its own up to the organization's, with those locked above it. */
    get("/services/{id}/variables/hierarchy") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(VariableHierarchyController.forService(caller.orgId, serviceId, caller.userId))
    }

    /** Creates a service variable. Type: `secret`, `variable` (default), or `metric`. */
    post("/services/{id}/variables") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(ServiceController.createVariable(caller.orgId, serviceId, body, caller.userId))
    }

    /** Updates a service variable. */
    patch("/services/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(ServiceController.updateVariable(caller.orgId, serviceId, varId, body, caller.userId))
    }

    /** Deletes a service variable. */
    delete("/services/{id}/variables/{varId}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val varId = call.pathUuid("varId")
        ServiceController.deleteVariable(caller.orgId, serviceId, varId, caller.userId)
        call.respond(mapOf("ok" to true))
    }
}
