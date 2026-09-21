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

/**
 * One endpoint's numbers for one bucket of the statistics window.
 *
 * Averages are taken from the stored sums and their own denominators, never
 * from other averages: [phases] is null exactly when the bucket timed no call
 * (all six members share that one denominator), and [avgSizeBytes] is null when
 * no call in it reported a size. Null means "nothing to divide by", not zero.
 */
@Serializable
data class EndpointSeriesPoint(
    /** ISO-8601 bucket start (UTC), the same spelling [StatBucket.bucketStart] uses. */
    val bucketStart: String,
    val calls: Long,
    val phases: PhaseTimings?,
    val avgSizeBytes: Long?,
)

/** One endpoint's time series over the statistics window. */
@Serializable
data class EndpointSeries(
    val key: String,
    val method: String,
    val template: String,
    /** Ascending by bucket start, and sparse: a bucket with no call has no point. */
    val points: List<EndpointSeriesPoint>,
)

/**
 * The statistics window broken down per endpoint **over time** — the same
 * buckets the service-level trend uses, from `probe_step_aggregates`.
 *
 * Kept off the statistics response on purpose: twenty endpoints over a week of
 * hourly buckets is two orders of magnitude more JSON than the rest of that
 * read, which the dashboard polls.
 */
@Serializable
data class EndpointSeriesDto(
    val window: String,
    /** "hourly" | "daily" — the same mapping the statistics read applies. */
    val bucketType: String,
    /**
     * The ascending, de-duplicated union of every bucket start that appears
     * anywhere in this response, [all] included: the chart's axis, without
     * walking every series to rebuild it.
     */
    val buckets: List<String>,
    /**
     * The whole service, every endpoint of a bucket summed — *including* the
     * ones past the cap, so the chart's "all endpoints" mode is the service and
     * not its top twenty.
     */
    val all: List<EndpointSeriesPoint>,
    /** The same endpoints, in the same order and under the same cap, as the statistics read. */
    val endpoints: List<EndpointSeries>,
    val endpointsTruncated: Boolean = false,
)

/**
 * How often one assertion of one endpoint failed over the window.
 *
 * Identity is the **declared** side of the assertion only — what the script
 * asked for, never what the target answered. `actual`/`actualLhs`/`actualRhs`
 * would make every distinct failure its own row.
 *
 * A field that does not apply to the assertion's shape is null, never omitted:
 * scope assertions (`.expect()`, `.check()`) carry [scope]/[op]/[expected],
 * `.assert()` conditions carry [kind]/[expression]. The label is composed by the
 * caller — the API returns parts, not display text.
 */
@Serializable
data class AssertionFailureStat(
    val endpointKey: String,
    val method: String,
    val template: String,
    /** `assertions[].method` (spec §9), verbatim: "expect" | "check" | "assert". */
    val assertionMethod: String?,
    val scope: String?,
    val op: String?,
    /** The expected value as text, truncated; null for an `assert` condition. */
    val expected: String?,
    val kind: String?,
    /** The condition rendered back to source form, truncated; null for a scope assertion. */
    val expression: String?,
    val failures: Long,
    /** Every evaluation of this assertion in the window — "indeterminate" included. */
    val evaluations: Long,
    /** Percentage 0..100, two decimals. */
    val failureRatePct: Double,
    /** ISO-8601 start of the newest run that failed this assertion. */
    val lastFailedAt: String,
)

/**
 * The window's most-failing assertions, computed at read time from raw
 * `probe_steps.assertion_results` — there is no assertion rollup, so this read
 * sees exactly as far back as result retention still holds.
 *
 * [since] is what the numbers are really over: the window's own lower bound
 * normally, and the oldest run actually scanned when the row cap bit
 * ([truncated]) or when retention had already taken the rest.
 */
@Serializable
data class AssertionFailuresDto(
    val window: String,
    val since: String,
    val until: String,
    val truncated: Boolean,
    /** Descending by failure count; at most [dev.tracedown.gateway.controllers.metrics.DashboardMetricsController.MAX_ASSERTIONS]. */
    val assertions: List<AssertionFailureStat>,
)

/** One hour-of-week cell of the failure heatmap. */
@Serializable
data class HeatmapCell(
    /** ISO-8601 weekday, 1 = Monday … 7 = Sunday, in UTC. */
    val weekday: Int,
    /** Hour of day 0..23, in UTC. */
    val hour: Int,
    val runs: Long,
    val failedRuns: Long,
)

/**
 * Failed runs by hour of day and day of week, from the all-agents hourly rows
 * of `probe_aggregates` — the only place that holds a correct *run* failure
 * count per hour.
 *
 * **Everything in it is UTC**, which [timezone] states so the axis can be
 * labelled honestly. There is no timezone parameter: an offset in the query
 * would make every cached grid per-viewer, and a named zone would have to be
 * applied to a column stored in the writing JVM's wall clock — which is exactly
 * the kind of mixed-clock arithmetic this family already has one bug from.
 */
@Serializable
data class FailureHeatmapDto(
    /** The lookback actually used, after clamping. */
    val days: Int,
    val timezone: String,
    /** The requested bounds. */
    val since: String,
    val until: String,
    /** Oldest and newest hour that carried runs; null when the service has no history. */
    val coveredFrom: String?,
    val coveredTo: String?,
    val totalRuns: Long,
    val totalFailedRuns: Long,
    /** Ascending by weekday then hour, and sparse: a cell with no runs is absent. */
    val cells: List<HeatmapCell>,
)
