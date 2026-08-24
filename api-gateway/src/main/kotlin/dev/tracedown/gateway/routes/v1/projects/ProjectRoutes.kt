package dev.tracedown.gateway.routes.v1.projects

import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.variables.VariableHierarchyController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.projects.UpdateProjectRequest
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
 * @OpenAPITag Projects
 * Project CRUD and project-level variables.
 */
@Resource("/api/v1/projects")
class Projects {
    @Resource("{id}")
    class ById(val parent: Projects = Projects(), val id: String) {
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

fun Route.projectRoutes() {
    /** Creates a project inside a workspace. */
    post<Projects> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateProjectRequest>(call)
        val wsId = parseUuid(body.workspaceId, "workspace ID")
        call.respond(ProjectController.create(orgId, wsId, body, principal.userId))
    }

    /** Lists all projects in a workspace. */
    get<Projects> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val wsId = parseUuid(call.request.queryParameters["workspaceId"] ?: "", "workspaceId query parameter")
        val pfs = parsePfsParams(call)
        call.respond(ProjectController.list(orgId, wsId, principal.userId, pfs))
    }

    /** Returns a single project. */
    get<Projects.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.id, "id")
        call.respond(ProjectController.get(orgId, projId, principal.userId))
    }

    /**
     * Enables or disables every service in the project, in one transaction.
     * Reports what moved, what was already there, and what was skipped.
     */
    patch<Projects.ById.ServicesToggle> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<ToggleServiceRequest>(call)
        call.respond(ServiceController.toggleProjectServices(orgId, projId, body.isActive, principal.userId))
    }

    /** Updates a project's name. */
    patch<Projects.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.id, "id")
        val body = tryReceive<UpdateProjectRequest>(call)
        call.respond(ProjectController.update(orgId, projId, body, principal.userId))
    }

    /** Soft-deletes a project. */
    delete<Projects.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.id, "id")
        ProjectController.delete(orgId, projId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Lists project variables. Encrypted values are masked. */
    get<Projects.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.id, "id")
        val pfs = parsePfsParams(call)
        call.respond(ProjectController.listVariables(orgId, projId, principal.userId, pfs))
    }

    /** Full inherited variable hierarchy (project → workspace → org) + locked vars. */
    get<Projects.ById.Variables.Hierarchy> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.parent.id, "id")
        call.respond(VariableHierarchyController.forProject(orgId, projId, principal.userId))
    }

    /** Creates a project variable. Type: "secret", "variable" (default), or "metric". */
    post<Projects.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.id, "id")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(ProjectController.createVariable(orgId, projId, body, principal.userId))
    }

    /** Decrypts and returns a single project variable. Secrets cannot be revealed. */
    get<Projects.ById.Variables.VarById.Reveal> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.parent.parent.id, "id")
        val varId = parseUuid(resource.parent.varId, "variable ID")
        call.respond(ProjectController.revealVariable(orgId, projId, varId, principal.userId))
    }

    /** Updates a project variable's value. */
    patch<Projects.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.parent.id, "id")
        val varId = parseUuid(resource.varId, "variable ID")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(ProjectController.updateVariable(orgId, projId, varId, body, principal.userId))
    }

    /** Soft-deletes a project variable. */
    delete<Projects.ById.Variables.VarById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projId = parseUuid(resource.parent.parent.id, "id")
        val varId = parseUuid(resource.varId, "variable ID")
        ProjectController.deleteVariable(orgId, projId, varId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
