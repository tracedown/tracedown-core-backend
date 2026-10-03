package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.controllers.metrics.MetricsAccess
import dev.tracedown.gateway.routes.publicapi.apiCaller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * How a service, or every service of a project or workspace the caller may
 * see, has been doing.
 */
fun Route.metricsRoutes() {
    /**
     * Current counters and state of a service (`state.lastRunAt` in epoch
     * seconds). 204 while it has none yet.
     */
    get("/services/{id}/metrics") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val metrics = MetricsAccess.serviceCurrent(caller.orgId, caller.userId, serviceId)
        if (metrics != null) call.respond(metrics) else call.respond(HttpStatusCode.NoContent, "")
    }

    /** Hourly buckets for a service over the last `hours` hours (default 24, at most 168). */
    get("/services/{id}/metrics/history") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(MetricsAccess.serviceHistory(caller.orgId, caller.userId, serviceId, call.intQuery("hours", 24)))
    }

    /**
     * Uptime, error rate, latency trend and per-endpoint breakdown of a
     * service over `window`: `24h` (default), `7d`, `30d` or `90d`.
     */
    get("/services/{id}/metrics/statistics") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(MetricsAccess.serviceStatistics(caller.orgId, caller.userId, serviceId, window(call)))
    }

    /** The statistics window per endpoint over time, on the same buckets. Takes the same `window`. */
    get("/services/{id}/metrics/statistics/endpoint-series") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(MetricsAccess.endpointSeries(caller.orgId, caller.userId, serviceId, window(call)))
    }

    /**
     * The window's most-failing assertions. Takes the same `window`; says how
     * far back it actually read (`since`, `truncated`).
     */
    get("/services/{id}/metrics/statistics/assertions") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        call.respond(MetricsAccess.assertionFailures(caller.orgId, caller.userId, serviceId, window(call)))
    }

    /**
     * Failed runs by UTC hour of day and ISO weekday over the last `days` days
     * (default 90, at most 365).
     */
    get("/services/{id}/metrics/statistics/failure-heatmap") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val days = call.intQuery("days", DashboardMetricsController.DEFAULT_HEATMAP_DAYS)
        call.respond(MetricsAccess.failureHeatmap(caller.orgId, caller.userId, serviceId, days))
    }

    /**
     * Current counters and state across the project's services the caller may
     * see, with how many there are. 204 while none of them has any.
     */
    get("/projects/{id}/metrics") {
        val caller = call.apiCaller
        val projectId = call.pathUuid("id")
        val metrics = MetricsAccess.projectCurrent(caller.orgId, caller.userId, projectId).orNull()
        if (metrics != null) call.respond(metrics) else call.respond(HttpStatusCode.NoContent, "")
    }

    /** Hourly buckets across the project's services the caller may see (`hours`: default 24, at most 168). */
    get("/projects/{id}/metrics/history") {
        val caller = call.apiCaller
        val projectId = call.pathUuid("id")
        call.respond(MetricsAccess.projectHistory(caller.orgId, caller.userId, projectId, call.intQuery("hours", 24)))
    }

    /**
     * Current counters and state across the workspace's projects the caller may
     * see, with how many there are. 204 while none of their services has any.
     */
    get("/workspaces/{id}/metrics") {
        val caller = call.apiCaller
        val workspaceId = call.pathUuid("id")
        val metrics = MetricsAccess.workspaceCurrent(caller.orgId, caller.userId, workspaceId).orNull()
        if (metrics != null) call.respond(metrics) else call.respond(HttpStatusCode.NoContent, "")
    }

    /** Hourly buckets across the workspace's projects the caller may see (`hours`: default 24, at most 168). */
    get("/workspaces/{id}/metrics/history") {
        val caller = call.apiCaller
        val workspaceId = call.pathUuid("id")
        call.respond(MetricsAccess.workspaceHistory(caller.orgId, caller.userId, workspaceId, call.intQuery("hours", 24)))
    }
}

private fun window(call: ApplicationCall): String = call.request.queryParameters["window"] ?: "24h"
