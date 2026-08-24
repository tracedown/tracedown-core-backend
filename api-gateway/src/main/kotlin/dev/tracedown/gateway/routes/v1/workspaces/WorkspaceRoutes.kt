package dev.tracedown.gateway.routes.v1.workspaces

import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.controllers.variables.VariableHierarchyController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.UpdateWorkspaceRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Workspaces
 * Workspace CRUD and workspace-level variables.
 */
@Resource("/api/v1/workspaces")
class Workspaces {
    @Resource("{workspaceId}")
    class ById(val parent: Workspaces = Workspaces(), val workspaceId: String) {
        @Resource("services/toggle")
        class ServicesToggle(val parent: ById)

        @Resource("variables")
        class Variables(val parent: ById) {
            @Resource("hierarchy")
            class Hierarchy(val parent: Variables)

            @Resource("{varId}")
            class VarById(val parent: Variables, val varId: String) {
                @Resource("reveal")
                class Reveal(val parent: VarById)
            }
        }
    }
}

fun Route.workspaceRoutes() {
    /** Creates a new workspace. */
    post<Workspaces> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateWorkspaceRequest>(call)
        call.respond(WorkspaceController.create(orgId, body, principal.userId))
    }

    /** Lists all workspaces the user has access to. */
    get<Workspaces> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(WorkspaceController.list(orgId, principal.userId, pfs))
    }

    /** Returns a single workspace. */
    get<Workspaces.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.workspaceId, "workspace ID")
        call.respond(WorkspaceController.get(orgId, wsId, principal.userId))
    }

    /**
     * Enables or disables every service in every project of the workspace, in one
     * transaction. Reports what moved, what was already there, and what was skipped.
     */
    patch<Workspaces.ById.ServicesToggle> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.workspaceId, "workspaceId")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggleWorkspaceServices(orgId, wsId, body.isActive, principal.userId))
    }

    /** Updates a workspace's name. */
    patch<Workspaces.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.workspaceId, "workspace ID")
        val body = tryReceive<UpdateWorkspaceRequest>(call)
        call.respond(WorkspaceController.update(orgId, wsId, body, principal.userId))
    }

    /** Soft-deletes a workspace. */
    delete<Workspaces.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.workspaceId, "workspace ID")
        WorkspaceController.delete(orgId, wsId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Lists variables. Encrypted values are masked. Metrics shown as plaintext. */
    get<Workspaces.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.workspaceId, "workspace ID")
        val pfs = parsePfsParams(call)
        call.respond(WorkspaceController.listVariables(orgId, wsId, principal.userId, pfs))
    }

    /** Full inherited variable hierarchy (workspace → org) + locked vars. */
    get<Workspaces.ById.Variables.Hierarchy> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.parent.workspaceId, "workspace ID")
        call.respond(VariableHierarchyController.forWorkspace(orgId, wsId, principal.userId))
    }

    /** Creates a variable. Type: "secret", "variable" (default), or "metric". */
    post<Workspaces.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.workspaceId, "workspace ID")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(WorkspaceController.createVariable(orgId, wsId, body, principal.userId))
    }

    /** Decrypts and returns a single variable. Secrets cannot be revealed. */
    get<Workspaces.ById.Variables.VarById.Reveal> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.parent.parent.workspaceId, "workspace ID")
        val varId = parseUuid(resource.parent.varId, "variable ID")
        call.respond(WorkspaceController.revealVariable(orgId, wsId, varId, principal.userId))
    }

    /** Updates a variable's value. Re-encrypts if encrypted type. */
    patch<Workspaces.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.parent.workspaceId, "workspace ID")
        val varId = parseUuid(resource.varId, "variable ID")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(WorkspaceController.updateVariable(orgId, wsId, varId, body, principal.userId))
    }

    /** Soft-deletes a variable. */
    delete<Workspaces.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(resource.parent.parent.workspaceId, "workspace ID")
        val varId = parseUuid(resource.varId, "variable ID")
        WorkspaceController.deleteVariable(orgId, wsId, varId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
