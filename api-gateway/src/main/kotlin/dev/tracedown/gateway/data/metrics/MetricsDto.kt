package dev.tracedown.gateway.data.metrics

import kotlinx.serialization.Serializable

@Serializable
data class ServiceMetricsDto(
    val counters: MetricsCounters,
    val state: MetricsState,
    val percentiles: ResponsePercentiles? = null,
    /** Accessible-set totals — present only on aggregate (workspace/project) responses. */
    val projectCount: Int? = null,
    val serviceCount: Int? = null,
)

@Serializable
data class MetricsCounters(
    val probesTotal: Long,
    val probesSuccess: Long,
    val probesFailure: Long,
    val probesTimeout: Long,
)

@Serializable
data class MetricsState(
    val lastStatus: String?,
    val lastConsecutive: Long,
    val lastResponseMs: Long,
    val lastRunAt: Long?,
)

@Serializable
data class ResponsePercentiles(
    val p50: Long,
    val p95: Long,
    val p99: Long,
)

@Serializable
data class HourlyBucket(
    val hour: String,
    val total: Long,
    val success: Long,
    val failure: Long,
    val timeout: Long,
    val sumMs: Long,
    val callCount: Long = 0,
)

/** One aggregate bucket from `probe_aggregates`, as a statistics time-series point. */
@Serializable
data class StatBucket(
    /** ISO-8601 bucket start (UTC). */
    val bucketStart: String,
    val p50Ms: Int?,
    val p95Ms: Int?,
    val p99Ms: Int?,
    /** Percentage 0..100 (the stored 0..1 fraction ×100); null when the bucket had no runs. */
    val uptimePct: Double?,
    val errorRatePct: Double?,
    val probeCount: Int,
)

/** Per-region (per probe agent) statistics series for a service. */
@Serializable
data class RegionSeries(
    val agentId: Long,
    val agentLabel: String,
    val buckets: List<StatBucket>,
)

/** Average time spent in each phase of a call, in milliseconds. */
@Serializable
data class PhaseTimings(
    val dnsMs: Int,
    val connectMs: Int,
    val tlsMs: Int,
    val ttfbMs: Int,
    val transferMs: Int,
    val responseMs: Int,
)

/** How often one endpoint answered with one status code. */
@Serializable
data class EndpointCodeCount(
    /** 0 means the call produced no HTTP response at all. */
    val code: Int,
    val count: Long,
)

/**
 * One endpoint of a service over the window — the method and the URL template
 * as the script writes it, with every interpolated variable left as a
 * placeholder. Two calls that share a method and a template are one endpoint,
 * and their counts add up.
 */
@Serializable
data class EndpointStat(
    /** `"<METHOD> <template>"`, the stored identity. */
    val key: String,
    val method: String,
    val template: String,
    /**
     * A resolved URL this endpoint was last seen at, query and fragment
     * stripped. Null when no recent run named one.
     */
    val exampleUrl: String?,
    val calls: Long,
    /** Every status code seen in the window, ascending, with 0 ("no response") last. */
    val codes: List<EndpointCodeCount>,
    /** Averages over the window's timed calls; null when none were timed. */
    val phases: PhaseTimings?,
    /** The same, for the equal-length window immediately before; null when it holds no data. */
    val previousPhases: PhaseTimings?,
    val avgSizeBytes: Long?,
)

/**
 * Deep statistics for a service over a window, read straight from `probe_aggregates`:
 * the all-agents [overall] trend plus a per-region breakdown. Bucket granularity is
 * hourly for short windows, daily for long ones.
 *
 * [endpoints] is the same window broken down per endpoint, from
 * `probe_step_aggregates`. It starts filling from the upgrade that introduced
 * it — there is no backfill — so an older window can legitimately be empty.
 */
@Serializable
data class ServiceStatisticsDto(
    val window: String,
    /** "hourly" | "daily". */
    val bucketType: String,
    val overall: List<StatBucket>,
    val regions: List<RegionSeries>,
    /**
     * Ordered by first appearance in the service's current script where that
     * is derivable, and by call count otherwise. At most
     * [dev.tracedown.gateway.controllers.metrics.DashboardMetricsController.MAX_ENDPOINTS].
     */
    val endpoints: List<EndpointStat> = emptyList(),
    /** True when endpoints past the cap were dropped from [endpoints]. */
    val endpointsTruncated: Boolean = false,
)
