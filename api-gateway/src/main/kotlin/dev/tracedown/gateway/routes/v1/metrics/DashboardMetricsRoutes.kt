package dev.tracedown.gateway.routes.v1.metrics

import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.controllers.metrics.MetricsAccess
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parseUuid
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import kotlinx.serialization.Serializable

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

    /**
     * The statistics window per endpoint over time. A resource of its own, not
     * a field on [Statistics]: twenty endpoints over a week of hourly buckets
     * dwarf the rest of that response, which the dashboard polls.
     */
    @Resource("statistics/endpoint-series")
    class EndpointSeries(val parent: ServiceMetrics, val window: String = "24h")

    /**
     * The window's most-failing assertions. The only read in the family that
     * touches raw `probe_steps` rather than a rollup, so it is asked for when
     * the panel is looked at rather than on every statistics poll.
     */
    @Resource("statistics/assertions")
    class Assertions(val parent: ServiceMetrics, val window: String = "24h")

    /**
     * Failed runs by hour of day and weekday. Has a lookback of its own —
     * weeks, not the selected window — so it does not take a `window` at all.
     */
    @Resource("statistics/failure-heatmap")
    class FailureHeatmap(
        val parent: ServiceMetrics,
        val days: Int = DashboardMetricsController.DEFAULT_HEATMAP_DAYS,
    )
}

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
        val metrics = MetricsAccess.serviceCurrent(orgId, principal.userId, serviceId)
        if (metrics != null) call.respond(metrics) else call.respond(HttpStatusCode.NoContent, "")
    }

    /** Returns hourly metric buckets for time-series charts. Default 24h, max 168h (7 days). */
    get<ServiceMetrics.History> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.serviceHistory(orgId, principal.userId, serviceId, resource.hours))
    }

    /** Returns the last N recent-probe data points for a service (default 10, max 50). */
    get<ServiceMetrics.RecentProbes> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.serviceRecentProbes(orgId, principal.userId, serviceId, resource.limit))
    }

    /** Deep statistics (uptime/error-rate/latency trend + per-region) from probe_aggregates. */
    get<ServiceMetrics.Statistics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.serviceStatistics(orgId, principal.userId, serviceId, resource.window))
    }

    /** The same window per endpoint over time, on the same buckets, from probe_step_aggregates. */
    get<ServiceMetrics.EndpointSeries> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.endpointSeries(orgId, principal.userId, serviceId, resource.window))
    }

    /** The window's most-failing assertions, computed from raw probe_steps. */
    get<ServiceMetrics.Assertions> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.assertionFailures(orgId, principal.userId, serviceId, resource.window))
    }

    /** Failed runs by UTC hour of day and ISO weekday, from the hourly rollups. */
    get<ServiceMetrics.FailureHeatmap> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val serviceId = parseUuid(resource.parent.serviceId, "service ID")
        call.respond(MetricsAccess.failureHeatmap(orgId, principal.userId, serviceId, resource.days))
    }

    /** Aggregated metrics for accessible services in a project. Filters by service-level permissions. */
    get<ProjectMetrics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "project ID")
        call.respond(MetricsAccess.projectCurrent(orgId, principal.userId, projectId).orEmpty())
    }

    /** Aggregated metrics for accessible services in a workspace. Filters by project-level permissions. */
    get<WorkspaceMetrics> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = parseUuid(resource.workspaceId, "workspace ID")
        call.respond(MetricsAccess.workspaceCurrent(orgId, principal.userId, workspaceId).orEmpty())
    }

    /** Aggregated hourly history for accessible services in a project. Default 24h, max 168h. */
    get<ProjectMetricsHistory> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val projectId = parseUuid(resource.projectId, "project ID")
        call.respond(MetricsAccess.projectHistory(orgId, principal.userId, projectId, resource.hours))
    }

    /** Aggregated hourly history for accessible services in a workspace. Default 24h, max 168h. */
    get<WorkspaceMetricsHistory> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = parseUuid(resource.workspaceId, "workspace ID")
        call.respond(MetricsAccess.workspaceHistory(orgId, principal.userId, workspaceId, resource.hours))
    }
}
