package dev.tracedown.gateway.routes.v1.metrics

import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.gateway.controllers.metrics.UsageController
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.requireCachedPermissions
import dev.tracedown.gateway.util.requireOrgWrite
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * @OpenAPITag Usage
 * Resource usage: total requests + measured HTTP-layer ingress/egress bytes over
 * a window (2h–7d, capped at the probe-result retention). Sourced from the
 * hourly usage buckets in Redis B.
 */
@Resource("/api/v1/services/{serviceId}/usage")
class ServiceUsage(val serviceId: String, val hours: Int = 24)

@Resource("/api/v1/projects/{projectId}/usage")
class ProjectUsage(val projectId: String, val hours: Int = 24)

@Resource("/api/v1/workspaces/{workspaceId}/usage")
class WorkspaceUsage(val workspaceId: String, val hours: Int = 24)

@Resource("/api/v1/org/usage")
class OrgUsage(val hours: Int = 24)

fun Route.usageRoutes() {
    get<ServiceUsage> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.serviceId, "service ID")
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canWriteResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
        call.respond(UsageController.forService(serviceId, resource.hours))
    }

    get<ProjectUsage> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "project ID")
        transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canWriteResource(cached, "project", projectId, listOf("workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
        call.respond(UsageController.forProject(projectId, resource.hours))
    }

    get<WorkspaceUsage> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = parseUuid(resource.workspaceId, "workspace ID")
        transaction {
            ResourceResolver.resolveWorkspace(workspaceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canWriteResource(cached, "workspace", workspaceId, emptyList())) {
                throw NotFoundException()
            }
        }
        call.respond(UsageController.forWorkspace(workspaceId, resource.hours))
    }

    /** Org-wide usage — the aggregate view, gated by admin write. */
    get<OrgUsage> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        transaction {
            requireOrgWrite(orgId, principal.userId) { it.admin }
        }
        call.respond(UsageController.forOrg(orgId, resource.hours))
    }
}
