package dev.tracedown.gateway.routes.v1.metrics

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.data.metrics.MetricsCounters
import dev.tracedown.gateway.data.metrics.MetricsState
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.requireCachedPermissions
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * @OpenAPITag Dashboard Metrics
 * Metrics endpoints for frontend dashboards.
 */
@Resource("/api/v1/services/{serviceId}/metrics")
class ServiceMetrics(val serviceId: String) {
    @Resource("history")
    class History(val parent: ServiceMetrics, val hours: Int = 24)

    @Resource("recent-probes")
    class RecentProbes(val parent: ServiceMetrics, val limit: Int = 10)

    @Resource("statistics")
    class Statistics(val parent: ServiceMetrics, val window: String = "24h")
}

private val STATISTICS_WINDOWS = setOf("24h", "7d", "30d", "90d")

@Serializable
@Resource("/api/v1/projects/{projectId}/metrics")
class ProjectMetrics(val projectId: String)

@Serializable
@Resource("/api/v1/workspaces/{workspaceId}/metrics")
class WorkspaceMetrics(val workspaceId: String)

@Serializable
@Resource("/api/v1/projects/{projectId}/metrics/history")
class ProjectMetricsHistory(val projectId: String, val hours: Int = 24)

@Serializable
@Resource("/api/v1/workspaces/{workspaceId}/metrics/history")
class WorkspaceMetricsHistory(val workspaceId: String, val hours: Int = 24)

fun Route.dashboardMetricsRoutes() {
    /** Returns current counters and state for a service. */
    get<ServiceMetrics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.serviceId, "service ID")
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
        val metrics = DashboardMetricsController.getServiceMetrics(serviceId)
        if (metrics != null) call.respond(metrics) else call.respond(HttpStatusCode.NoContent, "")
    }

    /** Returns hourly metric buckets for time-series charts. Default 24h, max 168h (7 days). */
    get<ServiceMetrics.History> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }

        val hours = resource.hours
        if (hours < 1 || hours > 168) throw BadRequestException(ErrorCodes.FIELD_INVALID)

        call.respond(DashboardMetricsController.getServiceHistory(serviceId, hours))
    }

    /** Returns the last N recent-probe data points for a service (default 10, max 50). */
    get<ServiceMetrics.RecentProbes> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
        val limit = resource.limit.coerceIn(1, 50)
        call.respond(DashboardMetricsController.getServiceRecentProbes(serviceId, limit))
    }

    /** Deep statistics (uptime/error-rate/latency trend + per-region) from probe_aggregates. */
    get<ServiceMetrics.Statistics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
        if (resource.window !in STATISTICS_WINDOWS) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        call.respond(DashboardMetricsController.getServiceStatistics(serviceId, resource.window))
    }

    /** Aggregated metrics for accessible services in a project. Filters by service-level permissions. */
    get<ProjectMetrics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "project ID")
        val serviceIds = transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "project", projectId, listOf("workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
            val parentChain = listOf("project::$projectId", "workspace::${ctx.workspaceId}")
            Services.selectAll()
                .where { (Services.projectId eq projectId) and (Services.deleted eq false) }
                .filter { canAccessResource(cached, "service", it[Services.id], parentChain) }
                .map { it[Services.id] }
        }
        val metrics = DashboardMetricsController.getAggregatedMetrics(serviceIds) ?: emptyAggregateMetrics()
        call.respond(metrics.copy(serviceCount = serviceIds.size))
    }

    /** Aggregated metrics for accessible services in a workspace. Filters by project-level permissions. */
    get<WorkspaceMetrics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = parseUuid(resource.workspaceId, "workspace ID")
        val (projectIds, serviceIds) = transaction {
            ResourceResolver.resolveWorkspace(workspaceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "workspace", workspaceId)) {
                throw NotFoundException()
            }
            val wsKey = "workspace::$workspaceId"
            val accessibleProjectIds = Projects.selectAll()
                .where { (Projects.workspaceId eq workspaceId) and (Projects.deleted eq false) }
                .filter { canAccessResource(cached, "project", it[Projects.id], listOf(wsKey)) }
                .map { it[Projects.id] }
            val ids = if (accessibleProjectIds.isEmpty()) emptyList()
            else Services.selectAll()
                .where { (Services.projectId inList accessibleProjectIds) and (Services.deleted eq false) }
                .map { it[Services.id] }
            accessibleProjectIds to ids
        }
        val metrics = DashboardMetricsController.getAggregatedMetrics(serviceIds) ?: emptyAggregateMetrics()
        call.respond(metrics.copy(projectCount = projectIds.size, serviceCount = serviceIds.size))
    }

    /** Aggregated hourly history for accessible services in a project. Default 24h, max 168h. */
    get<ProjectMetricsHistory> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "project ID")
        val hours = resource.hours
        if (hours < 1 || hours > 168) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        val serviceIds = transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "project", projectId, listOf("workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
            val parentChain = listOf("project::$projectId", "workspace::${ctx.workspaceId}")
            Services.selectAll()
                .where { (Services.projectId eq projectId) and (Services.deleted eq false) }
                .filter { canAccessResource(cached, "service", it[Services.id], parentChain) }
                .map { it[Services.id] }
        }
        call.respond(DashboardMetricsController.getAggregatedHistory(serviceIds, hours))
    }

    /** Aggregated hourly history for accessible services in a workspace. Default 24h, max 168h. */
    get<WorkspaceMetricsHistory> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = parseUuid(resource.workspaceId, "workspace ID")
        val hours = resource.hours
        if (hours < 1 || hours > 168) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        val serviceIds = transaction {
            ResourceResolver.resolveWorkspace(workspaceId, orgId)
            val cached = requireCachedPermissions(orgId, principal.userId)
            if (!canAccessResource(cached, "workspace", workspaceId)) {
                throw NotFoundException()
            }
            val wsKey = "workspace::$workspaceId"
            val accessibleProjectIds = Projects.selectAll()
                .where { (Projects.workspaceId eq workspaceId) and (Projects.deleted eq false) }
                .filter { canAccessResource(cached, "project", it[Projects.id], listOf(wsKey)) }
                .map { it[Projects.id] }
            if (accessibleProjectIds.isEmpty()) emptyList()
            else Services.selectAll()
                .where { (Services.projectId inList accessibleProjectIds) and (Services.deleted eq false) }
                .map { it[Services.id] }
        }
        call.respond(DashboardMetricsController.getAggregatedHistory(serviceIds, hours))
    }
}

/** Zeroed aggregate for resources whose services have no metrics yet — counts still apply. */
private fun emptyAggregateMetrics() = ServiceMetricsDto(
    counters = MetricsCounters(probesTotal = 0, probesSuccess = 0, probesFailure = 0, probesTimeout = 0),
    state = MetricsState(lastStatus = null, lastConsecutive = 0, lastResponseMs = 0, lastRunAt = null),
)
