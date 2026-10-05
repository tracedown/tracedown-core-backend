package dev.tracedown.gateway.controllers.metrics

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.gateway.data.metrics.AssertionFailuresDto
import dev.tracedown.gateway.data.metrics.EndpointSeriesDto
import dev.tracedown.gateway.data.metrics.FailureHeatmapDto
import dev.tracedown.gateway.data.metrics.HourlyBucket
import dev.tracedown.gateway.data.metrics.MetricsCounters
import dev.tracedown.gateway.data.metrics.MetricsState
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import dev.tracedown.gateway.data.metrics.ServiceStatisticsDto
import dev.tracedown.gateway.data.services.ProbePoint
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.fieldError
import dev.tracedown.gateway.util.requireCachedPermissions
import java.util.UUID
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Who may read which metrics, and which arguments a metrics read accepts.
 *
 * [DashboardMetricsController] reads by id and checks nothing — its callers
 * decide first. This is that decision, in one place, so every route that
 * serves a metric (the dashboard's and the key-authenticated API's) asks the
 * same question of the same permissions.
 *
 * A resource the caller cannot see is answered as one that does not exist
 * (404), never as a refusal: a metrics route must not confirm that an id is
 * real.
 */
object MetricsAccess {

    /** The windows the statistics reads are computed over. */
    private val STATISTICS_WINDOWS = setOf("24h", "7d", "30d", "90d")

    /** The longest history, in hours, any history read returns. */
    private const val MAX_HISTORY_HOURS = 168

    /** The services and projects of a workspace the caller may see. */
    data class WorkspaceScope(val projectIds: List<UUID>, val serviceIds: List<UUID>)

    /**
     * Asserts that [serviceId] is a service of [orgId] that [userId] may read,
     * through a grant on it or on its project or workspace.
     */
    fun requireService(orgId: UUID, userId: UUID, serviceId: UUID) {
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            if (!canAccessResource(cached, "service", ctx.serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
                throw NotFoundException()
            }
        }
    }

    /**
     * The services of [projectId] that [userId] may read, after asserting that
     * they may read the project itself.
     */
    fun projectServices(orgId: UUID, userId: UUID, projectId: UUID): List<UUID> = transaction {
        val ctx = ResourceResolver.resolveProject(projectId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canAccessResource(cached, "project", projectId, listOf("workspace::${ctx.workspaceId}"))) {
            throw NotFoundException()
        }
        val parentChain = listOf("project::$projectId", "workspace::${ctx.workspaceId}")
        Services.select(Services.id)
            .where { (Services.projectId eq projectId) and (Services.deleted eq false) }
            .filter { canAccessResource(cached, "service", it[Services.id], parentChain) }
            .map { it[Services.id] }
    }

    /**
     * The projects of [workspaceId] that [userId] may read, and every service
     * in them, after asserting that they may read the workspace itself.
     */
    fun workspaceScope(orgId: UUID, userId: UUID, workspaceId: UUID): WorkspaceScope = transaction {
        ResourceResolver.resolveWorkspace(workspaceId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canAccessResource(cached, "workspace", workspaceId)) {
            throw NotFoundException()
        }
        val wsKey = "workspace::$workspaceId"
        val accessibleProjectIds = Projects.select(Projects.id)
            .where { (Projects.workspaceId eq workspaceId) and (Projects.deleted eq false) }
            .filter { canAccessResource(cached, "project", it[Projects.id], listOf(wsKey)) }
            .map { it[Projects.id] }
        val serviceIds = if (accessibleProjectIds.isEmpty()) emptyList()
        else Services.select(Services.id)
            .where { (Services.projectId inList accessibleProjectIds) and (Services.deleted eq false) }
            .map { it[Services.id] }
        WorkspaceScope(accessibleProjectIds, serviceIds)
    }

    /** Refuses a statistics window other than the ones the rollups are read over (naming the field). */
    fun requireStatisticsWindow(window: String) {
        if (window !in STATISTICS_WINDOWS) throw fieldError("window")
    }

    /** Refuses a history length outside 1–168 hours. */
    fun requireHistoryHours(hours: Int) {
        if (hours < 1 || hours > MAX_HISTORY_HOURS) throw fieldError("hours") { put("max", JsonPrimitive(MAX_HISTORY_HOURS)) }
    }

    /**
     * Refuses a heatmap lookback outside 1 day to
     * [DashboardMetricsController.MAX_HEATMAP_DAYS] — refused rather than
     * quietly clamped: a heatmap of "the last 0 days" and one of "the last
     * 4000" are both a caller mistake, and answering the second would scan
     * whatever retention happens to hold.
     */
    fun requireHeatmapDays(days: Int) {
        if (days < 1 || days > DashboardMetricsController.MAX_HEATMAP_DAYS) {
            throw fieldError("days") { put("max", JsonPrimitive(DashboardMetricsController.MAX_HEATMAP_DAYS)) }
        }
    }

    /** Zeroed aggregate for resources whose services have no metrics yet — counts still apply. */
    private fun emptyAggregate() = ServiceMetricsDto(
        counters = MetricsCounters(probesTotal = 0, probesSuccess = 0, probesFailure = 0, probesTimeout = 0),
        state = MetricsState(lastStatus = null, lastConsecutive = 0, lastResponseMs = 0, lastRunAt = null),
    )

    // ── The reads, each with its check — one copy for every route that serves it ──

    /**
     * The current counters of a project's or workspace's readable services,
     * with how many there are. [metrics] is null when none of them has any
     * metrics yet.
     */
    data class AggregateRead(val metrics: ServiceMetricsDto?, val projectCount: Int?, val serviceCount: Int) {
        /** The aggregate with its counts, or a zeroed one carrying the counts when there is none. */
        fun orEmpty(): ServiceMetricsDto =
            (metrics ?: emptyAggregate()).copy(projectCount = projectCount, serviceCount = serviceCount)

        /** The aggregate with its counts, or null when there is none. */
        fun orNull(): ServiceMetricsDto? = metrics?.copy(projectCount = projectCount, serviceCount = serviceCount)
    }

    /** A service's current counters and state; null while it has none. */
    fun serviceCurrent(orgId: UUID, userId: UUID, serviceId: UUID): ServiceMetricsDto? {
        requireService(orgId, userId, serviceId)
        return DashboardMetricsController.getServiceMetrics(serviceId)
    }

    /** A service's hourly buckets over the last [hours]. Access is checked before the range. */
    fun serviceHistory(orgId: UUID, userId: UUID, serviceId: UUID, hours: Int): List<HourlyBucket> {
        requireService(orgId, userId, serviceId)
        requireHistoryHours(hours)
        return DashboardMetricsController.getServiceHistory(serviceId, hours)
    }

    /** A service's last [limit] runs (1–50) as chart points. */
    fun serviceRecentProbes(orgId: UUID, userId: UUID, serviceId: UUID, limit: Int): List<ProbePoint> {
        requireService(orgId, userId, serviceId)
        return DashboardMetricsController.getServiceRecentProbes(serviceId, limit.coerceIn(1, 50))
    }

    fun serviceStatistics(orgId: UUID, userId: UUID, serviceId: UUID, window: String): ServiceStatisticsDto {
        requireService(orgId, userId, serviceId)
        requireStatisticsWindow(window)
        return DashboardMetricsController.getServiceStatistics(serviceId, window)
    }

    fun endpointSeries(orgId: UUID, userId: UUID, serviceId: UUID, window: String): EndpointSeriesDto {
        requireService(orgId, userId, serviceId)
        requireStatisticsWindow(window)
        return DashboardMetricsController.getEndpointSeries(serviceId, window)
    }

    fun assertionFailures(orgId: UUID, userId: UUID, serviceId: UUID, window: String): AssertionFailuresDto {
        requireService(orgId, userId, serviceId)
        requireStatisticsWindow(window)
        return DashboardMetricsController.getAssertionFailures(serviceId, window)
    }

    fun failureHeatmap(orgId: UUID, userId: UUID, serviceId: UUID, days: Int): FailureHeatmapDto {
        requireService(orgId, userId, serviceId)
        requireHeatmapDays(days)
        return DashboardMetricsController.getFailureHeatmap(serviceId, days)
    }

    fun projectCurrent(orgId: UUID, userId: UUID, projectId: UUID): AggregateRead {
        val serviceIds = projectServices(orgId, userId, projectId)
        return AggregateRead(DashboardMetricsController.getAggregatedMetrics(serviceIds), null, serviceIds.size)
    }

    /** Hourly buckets across a project's readable services. The range is checked before access. */
    fun projectHistory(orgId: UUID, userId: UUID, projectId: UUID, hours: Int): List<HourlyBucket> {
        requireHistoryHours(hours)
        return DashboardMetricsController.getAggregatedHistory(projectServices(orgId, userId, projectId), hours)
    }

    fun workspaceCurrent(orgId: UUID, userId: UUID, workspaceId: UUID): AggregateRead {
        val scope = workspaceScope(orgId, userId, workspaceId)
        return AggregateRead(
            DashboardMetricsController.getAggregatedMetrics(scope.serviceIds), scope.projectIds.size, scope.serviceIds.size,
        )
    }

    /** Hourly buckets across a workspace's readable services. The range is checked before access. */
    fun workspaceHistory(orgId: UUID, userId: UUID, workspaceId: UUID, hours: Int): List<HourlyBucket> {
        requireHistoryHours(hours)
        return DashboardMetricsController.getAggregatedHistory(workspaceScope(orgId, userId, workspaceId).serviceIds, hours)
    }
}
