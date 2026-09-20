package dev.tracedown.gateway.controllers.metrics

import dev.tracedown.common.models.ProbeAggregates
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.Services
import dev.tracedown.common.util.EndpointKeySql
import dev.tracedown.common.util.EndpointKeys
import dev.tracedown.gateway.data.metrics.AssertionFailureStat
import dev.tracedown.gateway.data.metrics.AssertionFailuresDto
import dev.tracedown.gateway.data.metrics.EndpointCodeCount
import dev.tracedown.gateway.data.metrics.EndpointSeries
import dev.tracedown.gateway.data.metrics.EndpointSeriesDto
import dev.tracedown.gateway.data.metrics.EndpointSeriesPoint
import dev.tracedown.gateway.data.metrics.EndpointStat
import dev.tracedown.gateway.data.metrics.FailureHeatmapDto
import dev.tracedown.gateway.data.metrics.HeatmapCell
import dev.tracedown.gateway.data.metrics.HourlyBucket
import dev.tracedown.gateway.data.metrics.PhaseTimings
import dev.tracedown.gateway.data.metrics.RegionSeries
import dev.tracedown.gateway.data.metrics.ServiceStatisticsDto
import dev.tracedown.gateway.data.metrics.StatBucket
import dev.tracedown.gateway.data.services.ProbePoint
import dev.tracedown.gateway.util.EndpointFolding
import dev.tracedown.gateway.util.HourBuckets
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import dev.tracedown.gateway.data.metrics.MetricsCounters
import dev.tracedown.gateway.data.metrics.MetricsState
import dev.tracedown.gateway.data.metrics.ResponsePercentiles
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import io.lettuce.core.LettuceFutures
import io.lettuce.core.RedisFuture
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.math.round

/**
 * Reads dashboard metrics from Redis B for the frontend.
 *
 * Data is written by the metrics-service via MetricsWriter on each probe nudge.
 * On Redis cache miss, computes metrics on-demand from probe_results in the DB
 * and backfills the Redis cache for subsequent reads.
 */
object DashboardMetricsController {

    private lateinit var redisProvider: () -> RedisCommands<String, String>

    private val historyLog = org.slf4j.LoggerFactory.getLogger("dev.tracedown.gateway.metrics.history")

    /**
     * TTL given to an hourly bucket this controller seals — the same one the
     * ingest path sets when it creates a bucket, so a sealed hour lives exactly
     * as long as a written one.
     */
    private var hourlyBucketTtlSeconds: Long = DEFAULT_HOURLY_BUCKET_TTL_SECONDS

    /** Initialize with a Redis B connection provider and the hourly-bucket TTL. */
    fun init(
        redis: () -> RedisCommands<String, String>,
        hourlyBucketTtlSeconds: Long = DEFAULT_HOURLY_BUCKET_TTL_SECONDS,
    ) {
        this.redisProvider = redis
        this.hourlyBucketTtlSeconds = hourlyBucketTtlSeconds
    }

    /** Matches the metrics-service default for `METRICS_HOURLY_BUCKET_TTL_SECONDS`. */
    const val DEFAULT_HOURLY_BUCKET_TTL_SECONDS = 90000L

    private val redis get() = redisProvider()

    /**
     * Deep statistics for a service, straight from the aggregate tables (no
     * Redis): the all-agents trend plus a per-region breakdown, and the same
     * window broken down per endpoint, over [window]. Short windows use hourly
     * buckets, long windows daily. `uptime_pct`/`error_rate` are stored as
     * 0..1 fractions and returned as 0..100 percentages.
     */
    fun getServiceStatistics(serviceId: UUID, window: String): ServiceStatisticsDto {
        val spec = windowSpec(window)
        val bucketType = spec.bucketType
        val since = spec.since
        return transaction {
            val overall = ProbeAggregates.selectAll()
                .where {
                    (ProbeAggregates.serviceId eq serviceId) and
                        ProbeAggregates.probeAgentId.isNull() and
                        (ProbeAggregates.bucketType eq bucketType) and
                        (ProbeAggregates.bucketStart greaterEq since)
                }
                .orderBy(ProbeAggregates.bucketStart, SortOrder.ASC)
                .map { it.toStatBucket() }

            val regions = ProbeAggregates.innerJoin(ProbeAgents)
                .selectAll()
                .where {
                    (ProbeAggregates.serviceId eq serviceId) and
                        ProbeAggregates.probeAgentId.isNotNull() and
                        (ProbeAggregates.bucketType eq bucketType) and
                        (ProbeAggregates.bucketStart greaterEq since)
                }
                .orderBy(ProbeAggregates.bucketStart, SortOrder.ASC)
                .toList()
                .groupBy { it[ProbeAggregates.probeAgentId]!! }
                .map { (agentId, rows) ->
                    RegionSeries(
                        agentId = agentId,
                        agentLabel = rows.first()[ProbeAgents.label],
                        buckets = rows.map { it.toStatBucket() },
                    )
                }
                .sortedBy { it.agentLabel }

            val ranked = endpointStats(serviceId, spec)

            ServiceStatisticsDto(
                window = window,
                bucketType = bucketType,
                overall = overall,
                regions = regions,
                endpoints = ranked.take(MAX_ENDPOINTS),
                endpointsTruncated = ranked.size > MAX_ENDPOINTS,
            )
        }
    }

    /**
     * How many endpoints a statistics response carries. A script is a probe,
     * not a crawl: past a couple of dozen calls the chart it feeds stops being
     * readable, and the rest are dropped with `endpointsTruncated` set rather
     * than silently.
     */
    const val MAX_ENDPOINTS = 20

    /**
     * How many assertions the failure ranking carries. The same reasoning as
     * [MAX_ENDPOINTS]: a list this is meant to be read off, not exported.
     */
    const val MAX_ASSERTIONS = 20

    /** A window token resolved into the bounds every query in the read shares. */
    private class StatsWindow(
        /** "hourly" | "daily". */
        val bucketType: String,
        /** The one `now` the whole read is taken against. */
        val now: Instant,
        /** Earliest bucket start in the window. */
        val since: Instant,
        /** Earliest bucket start of the equal-length window immediately before. */
        val previousSince: Instant,
    )

    /**
     * Maps a window token to its bucket granularity and bounds. One `now` for
     * the whole read, so the window and the one before it are exactly
     * adjacent and exactly the same length.
     */
    private fun windowSpec(window: String): StatsWindow {
        val now = Instant.now()
        val (bucketType, length, unit) = when (window) {
            "7d" -> Triple("hourly", 7L, ChronoUnit.DAYS)
            "30d" -> Triple("daily", 30L, ChronoUnit.DAYS)
            "90d" -> Triple("daily", 90L, ChronoUnit.DAYS)
            else -> Triple("hourly", 24L, ChronoUnit.HOURS)
        }
        val since = now.minus(length, unit)
        return StatsWindow(bucketType, now, since, since.minus(length, unit))
    }

    // ── Per-endpoint statistics ─────────────────────────────────────────

    /**
     * How far back the example-URL lookup reads. It exists to put a real
     * address next to a template, not to be exhaustive, so it is bounded by
     * rows rather than by time: the most recent steps of the window, which is
     * an index scan whether the window is a day or a quarter. An endpoint that
     * has not been called in that many steps simply has no example.
     */
    private const val EXAMPLE_URL_STEP_SCAN = 2000

    /** Running totals for one endpoint over one window. */
    private class EndpointTotals {
        var calls = 0L
        var timed = 0L
        var dnsMs = 0L
        var connectMs = 0L
        var tlsMs = 0L
        var ttfbMs = 0L
        var transferMs = 0L
        var responseMs = 0L
        var sizeBytes = 0L
        var sized = 0L
        val codes = linkedMapOf<Int, Long>()

        /**
         * Adds [other]'s raw counts into this one, for the legacy fold. Sums
         * and denominators both, so the averages taken afterwards are over the
         * combined population — merging two averages would weight a handful of
         * legacy calls the same as a week of current ones.
         */
        fun absorb(other: EndpointTotals): EndpointTotals {
            calls += other.calls
            timed += other.timed
            dnsMs += other.dnsMs
            connectMs += other.connectMs
            tlsMs += other.tlsMs
            ttfbMs += other.ttfbMs
            transferMs += other.transferMs
            responseMs += other.responseMs
            sizeBytes += other.sizeBytes
            sized += other.sized
            for ((code, count) in other.codes) codes[code] = (codes[code] ?: 0L) + count
            return this
        }
    }

    /**
     * The window broken down per endpoint, ordered but not yet capped.
     *
     * Three queries, none of them per endpoint: the window's aggregate rows,
     * the preceding window's, and one bounded lookup for an example URL. The
     * service's current script supplies the order where it can be parsed —
     * a statistics table that lists endpoints in the order they are written is
     * readable next to the script; one ordered by call count is not.
     */
    private fun endpointStats(serviceId: UUID, spec: StatsWindow): List<EndpointStat> {
        val raw = readEndpointTotals(serviceId, spec.bucketType, spec.since, null)
        if (raw.isEmpty()) return emptyList()

        // One fold decision for the whole read, taken from the window on
        // display, and applied to everything the read compares against it.
        val folds = EndpointFolding.foldMap(raw.keys)
        val current = EndpointFolding.fold(raw, folds) { target, legacy -> target.absorb(legacy) }
        val previous = EndpointFolding.fold(
            readEndpointTotals(serviceId, spec.bucketType, spec.previousSince, spec.since),
            folds,
        ) { target, legacy -> target.absorb(legacy) }
        // A folded endpoint keeps its own example and borrows the legacy one
        // only when it has none — the two name the same address either way.
        val examples = EndpointFolding.fold(readExampleUrls(serviceId, spec.since), folds) { target, _ -> target }
        val order = scriptEndpointOrder(serviceId)

        return current
            .map { (key, totals) ->
                val (method, template) = EndpointKeys.split(key)
                EndpointStat(
                    key = key,
                    method = method,
                    template = template,
                    exampleUrl = examples[key],
                    calls = totals.calls,
                    codes = totals.codes.entries
                        // Ascending, but "no response" last: it is not a status
                        // code, and sorting it in front of 200 would put the
                        // least ordinary outcome where the eye starts.
                        .sortedWith(compareBy({ if (it.key == 0) 1 else 0 }, { it.key }))
                        .map { EndpointCodeCount(it.key, it.value) },
                    phases = totals.toPhases(),
                    previousPhases = previous[key]?.toPhases(),
                    avgSizeBytes = if (totals.sized > 0) {
                        Math.round(totals.sizeBytes.toDouble() / totals.sized)
                    } else {
                        null
                    },
                )
            }
            .sortedWith(
                compareBy<EndpointStat> { order[it.key] ?: Int.MAX_VALUE }
                    .thenByDescending { it.calls }
                    .thenBy { it.key },
            )
    }

    /**
     * The endpoint order the statistics read lists, over call counts alone —
     * the series read ranks the same window by the same rule without building
     * an [EndpointStat] for every key it will then drop.
     */
    private fun rankKeys(calls: Map<String, Long>, order: Map<String, Int>): List<String> =
        calls.keys.sortedWith(
            compareBy<String> { order[it] ?: Int.MAX_VALUE }
                .thenByDescending { calls[it] ?: 0L }
                .thenBy { it },
        )

    /** Averages over the calls that carried timings; null when none did. */
    private fun EndpointTotals.toPhases(): PhaseTimings? {
        if (timed <= 0) return null
        fun avg(sum: Long): Int = Math.round(sum.toDouble() / timed).toInt()
        return PhaseTimings(
            dnsMs = avg(dnsMs),
            connectMs = avg(connectMs),
            tlsMs = avg(tlsMs),
            ttfbMs = avg(ttfbMs),
            transferMs = avg(transferMs),
            responseMs = avg(responseMs),
        )
    }

    /**
     * Sums `probe_step_aggregates` over `[since, until)` (open-ended when
     * [until] is null) into one entry per endpoint. Insertion-ordered, so an
     * unparseable script still yields a stable list.
     */
    private fun readEndpointTotals(
        serviceId: UUID,
        bucketType: String,
        since: Instant,
        until: Instant?,
    ): Map<String, EndpointTotals> {
        val upperBound = if (until != null) "AND bucket_start < '${sqlTimestamp(until)}'" else ""
        // service id is a UUID object and bucket_type comes from this file's
        // own window table — neither is caller text.
        val sql = """
            SELECT endpoint_key,
                   status_code,
                   SUM(call_count)      AS calls,
                   SUM(timed_count)     AS timed,
                   SUM(sum_dns_ms)      AS dns_ms,
                   SUM(sum_connect_ms)  AS connect_ms,
                   SUM(sum_tls_ms)      AS tls_ms,
                   SUM(sum_ttfb_ms)     AS ttfb_ms,
                   SUM(sum_transfer_ms) AS transfer_ms,
                   SUM(sum_response_ms) AS response_ms,
                   SUM(sum_size_bytes)  AS size_bytes,
                   SUM(sized_count)     AS sized
            FROM probe_step_aggregates
            WHERE service_id = '$serviceId'
              AND bucket_type = '$bucketType'
              AND bucket_start >= '${sqlTimestamp(since)}'
              $upperBound
            GROUP BY endpoint_key, status_code
        """.trimIndent()

        val out = linkedMapOf<String, EndpointTotals>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                val totals = out.getOrPut(rs.getString("endpoint_key")) { EndpointTotals() }
                val calls = rs.getLong("calls")
                totals.calls += calls
                totals.timed += rs.getLong("timed")
                totals.dnsMs += rs.getLong("dns_ms")
                totals.connectMs += rs.getLong("connect_ms")
                totals.tlsMs += rs.getLong("tls_ms")
                totals.ttfbMs += rs.getLong("ttfb_ms")
                totals.transferMs += rs.getLong("transfer_ms")
                totals.responseMs += rs.getLong("response_ms")
                totals.sizeBytes += rs.getLong("size_bytes")
                totals.sized += rs.getLong("sized")
                val code = rs.getInt("status_code")
                totals.codes[code] = (totals.codes[code] ?: 0L) + calls
            }
        }
        return out
    }

    /**
     * A resolved URL to show beside each endpoint's template, in one query —
     * the most recent one each key was seen at, query and fragment stripped.
     *
     * `DISTINCT ON` over a bounded scan of the window's newest steps, so the
     * cost does not grow with the length of the window. Steps that carry no
     * key of their own are named the same way the aggregation names them, so
     * their example lands under the same key their counts do.
     */
    private fun readExampleUrls(serviceId: UUID, since: Instant): Map<String, String> {
        val sql = """
            SELECT DISTINCT ON (k) k, url
            FROM (
                SELECT COALESCE(s.endpoint_key, ${EndpointKeySql.fallbackKey("s.request_url")}) AS k,
                       ${EndpointKeySql.withoutQuery("s.request_url")} AS url,
                       r.started_at AS seen_at
                FROM probe_results r
                JOIN probe_steps s ON s.probe_result_id = r.id
                WHERE r.service_id = '$serviceId'
                  AND r.started_at >= '${sqlTimestamp(since)}'
                  AND r.status != 'skipped'
                ORDER BY r.started_at DESC
                LIMIT $EXAMPLE_URL_STEP_SCAN
            ) t
            ORDER BY k, seen_at DESC
        """.trimIndent()

        val out = mutableMapOf<String, String>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                val url = rs.getString("url")
                if (!url.isNullOrBlank()) out[rs.getString("k")] = url
            }
        }
        return out
    }

    /**
     * Where each endpoint of the service's **current** script first appears in
     * it, by key. Empty when the service is gone or its script does not parse
     * — the caller then falls back to ordering by call count.
     *
     * The key derivation is the same pure function the scheduler names a
     * dispatched call with, so a key read here is the key stored there.
     */
    private fun scriptEndpointOrder(serviceId: UUID): Map<String, Int> {
        val script = Services.selectAll()
            .where { Services.id eq serviceId }
            .limit(1)
            .firstOrNull()
            ?.get(Services.script)
            ?: return emptyMap()
        if (script.isBlank()) return emptyMap()
        return try {
            @Suppress("UNCHECKED_CAST")
            val calls = dev.lacelang.validator.parse(script)["calls"] as? List<Map<String, Any?>>
                ?: return emptyMap()
            calls.map { EndpointKeys.key(it["method"] as? String, it["url"]) }
                .distinct()
                .withIndex()
                .associate { (index, key) -> key to index }
        } catch (e: Exception) {
            // A service can hold a script that no longer parses; it still has
            // history, and history is still worth showing.
            historyLog.debug("service {} script does not parse — endpoints ordered by call count: {}", serviceId, e.message)
            emptyMap()
        }
    }

    // ── Per-endpoint time series ────────────────────────────────────────

    /**
     * The statistics window broken down per endpoint **and per bucket**, on the
     * granularity and the bounds the statistics read uses for the same
     * [window] — so a point here lines up with an `overall[]` bucket of the same
     * `bucketStart` without the caller snapping anything.
     *
     * Its own resource rather than a field on the statistics response: twenty
     * endpoints over a week of hourly buckets is two orders of magnitude more
     * JSON than everything else that read returns, and that read is polled.
     */
    fun getEndpointSeries(serviceId: UUID, window: String): EndpointSeriesDto {
        val spec = windowSpec(window)
        val empty = EndpointSeriesDto(
            window = window,
            bucketType = spec.bucketType,
            buckets = emptyList(),
            all = emptyList(),
            endpoints = emptyList(),
            endpointsTruncated = false,
        )
        return transaction {
            val raw = readEndpointBuckets(serviceId, spec.bucketType, spec.since)
            if (raw.isEmpty()) return@transaction empty

            // The service-wide series is summed BEFORE the fold and over every
            // key, capped or not: relabelling a key cannot change a total, and
            // an "all endpoints" line that quietly meant "all twenty listed
            // endpoints" would not add up to the service's own trend.
            val all = sortedMapOf<Instant, EndpointTotals>()
            for (series in raw.values) {
                for ((bucket, totals) in series) all.getOrPut(bucket) { EndpointTotals() }.absorb(totals)
            }

            val folded = EndpointFolding.fold(raw) { target, legacy ->
                for ((bucket, totals) in legacy) target.getOrPut(bucket) { EndpointTotals() }.absorb(totals)
                target
            }
            val calls = folded.mapValues { (_, series) -> series.values.sumOf { it.calls } }
            val ranked = rankKeys(calls, scriptEndpointOrder(serviceId))

            EndpointSeriesDto(
                window = window,
                bucketType = spec.bucketType,
                buckets = all.keys.map { it.toString() },
                all = all.map { (bucket, totals) -> totals.toSeriesPoint(bucket) },
                endpoints = ranked.take(MAX_ENDPOINTS).map { key ->
                    val (method, template) = EndpointKeys.split(key)
                    EndpointSeries(
                        key = key,
                        method = method,
                        template = template,
                        points = folded.getValue(key).entries
                            .sortedBy { it.key }
                            .map { (bucket, totals) -> totals.toSeriesPoint(bucket) },
                    )
                },
                endpointsTruncated = ranked.size > MAX_ENDPOINTS,
            )
        }
    }

    /** One bucket's totals as a series point; a zero denominator becomes null, not zero. */
    private fun EndpointTotals.toSeriesPoint(bucket: Instant) = EndpointSeriesPoint(
        bucketStart = bucket.toString(),
        calls = calls,
        phases = toPhases(),
        avgSizeBytes = if (sized > 0) Math.round(sizeBytes.toDouble() / sized) else null,
    )

    /**
     * `probe_step_aggregates` over `[since, ∞)` as one running total per
     * endpoint **per bucket** — the same rows [readEndpointTotals] sums, kept
     * apart along the time axis instead of collapsed onto it.
     *
     * Status codes are summed away here: the series draws timings and sizes,
     * and keeping a code breakdown per bucket per endpoint would multiply the
     * payload by the number of codes for a chart that never reads it.
     */
    private fun readEndpointBuckets(
        serviceId: UUID,
        bucketType: String,
        since: Instant,
    ): Map<String, MutableMap<Instant, EndpointTotals>> {
        // Neither interpolated value is caller text: a UUID object and this
        // file's own window table.
        val sql = """
            SELECT endpoint_key,
                   bucket_start,
                   SUM(call_count)      AS calls,
                   SUM(timed_count)     AS timed,
                   SUM(sum_dns_ms)      AS dns_ms,
                   SUM(sum_connect_ms)  AS connect_ms,
                   SUM(sum_tls_ms)      AS tls_ms,
                   SUM(sum_ttfb_ms)     AS ttfb_ms,
                   SUM(sum_transfer_ms) AS transfer_ms,
                   SUM(sum_response_ms) AS response_ms,
                   SUM(sum_size_bytes)  AS size_bytes,
                   SUM(sized_count)     AS sized
            FROM probe_step_aggregates
            WHERE service_id = '$serviceId'
              AND bucket_type = '$bucketType'
              AND bucket_start >= '${sqlTimestamp(since)}'
            GROUP BY endpoint_key, bucket_start
            ORDER BY endpoint_key, bucket_start
        """.trimIndent()

        val out = linkedMapOf<String, MutableMap<Instant, EndpointTotals>>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                val series = out.getOrPut(rs.getString("endpoint_key")) { linkedMapOf() }
                val totals = series.getOrPut(rs.getTimestamp("bucket_start").toInstant()) { EndpointTotals() }
                totals.calls += rs.getLong("calls")
                totals.timed += rs.getLong("timed")
                totals.dnsMs += rs.getLong("dns_ms")
                totals.connectMs += rs.getLong("connect_ms")
                totals.tlsMs += rs.getLong("tls_ms")
                totals.ttfbMs += rs.getLong("ttfb_ms")
                totals.transferMs += rs.getLong("transfer_ms")
                totals.responseMs += rs.getLong("response_ms")
                totals.sizeBytes += rs.getLong("size_bytes")
                totals.sized += rs.getLong("sized")
            }
        }
        return out
    }

    // ── Most-failing assertions ─────────────────────────────────────────

    /**
     * How many runs the assertion ranking may read before it stops and says so.
     *
     * There is no assertion rollup — the ranking is computed from raw
     * `probe_steps.assertion_results` — so the only thing keeping it off a
     * table with a hundred million rows in it is this cap and the window. The
     * bound is *runs*, not steps or assertions: `probe_results` is where the
     * `(service_id, started_at DESC)` index is, so a row limit there is an
     * index scan that stops, and the steps hanging off those runs are reached
     * by the foreign-key index one result at a time.
     *
     * 20 000 covers a week of minutely probing on a single agent, which is what
     * the longest hourly window asks for. Past that the read is honest about
     * how far it got rather than slower.
     */
    private const val ASSERTION_RUN_SCAN = 20_000

    /**
     * How many grouped assertions come back from the database before the fold
     * and the top-[MAX_ASSERTIONS] cut are applied in the JVM.
     *
     * The group count is normally a script's assertion count times its call
     * count — tens. It is only large when a script compares against an
     * interpolated value, which stores a distinct `expected` per run; this
     * keeps that case from materialising a row per run.
     */
    private const val ASSERTION_GROUP_SCAN = 1_000

    /**
     * Steps of one run the ranking reads. It is the `LIMIT` that keeps the
     * step lookup a correlated subquery — see [readAssertionGroups] — and a
     * ceiling on a pathological run at the same time. A probe is not a crawl:
     * a script with two hundred calls in it is outside what this panel ranks,
     * and its later steps are left out rather than dragged through the scan.
     */
    private const val MAX_STEPS_PER_RUN = 200

    /**
     * The window's most-failing assertions, grouped by endpoint and by the
     * assertion's **declared** identity.
     *
     * Computed at read time from the ProbeResult `calls[].assertions` arrays the
     * ingestor stores verbatim (spec §9). Nothing rolls those up, so the ranking
     * reaches exactly as far back as result retention still holds — which is why
     * the response carries the window it actually covered rather than the one it
     * was asked for.
     */
    fun getAssertionFailures(serviceId: UUID, window: String): AssertionFailuresDto {
        val spec = windowSpec(window)
        return transaction {
            val scanned = readAssertionScanBounds(serviceId, spec.since)
            val rows = readAssertionGroups(serviceId, spec.since)
            // The fold is over endpoint keys, so it is applied to the endpoint
            // half of each row and the rows are then regrouped: one assertion
            // evaluated under both the legacy name and the real one is one
            // assertion. The key set is this read's own — the aggregation these
            // rows never pass through lags by up to an hour, and deciding from
            // it would leave a fresh window unfolded.
            val folds = EndpointFolding.foldMap(rows.map { it.endpointKey }.distinct())
            for (row in rows) folds[row.endpointKey]?.let { row.endpointKey = it }
            val folded = rows.groupBy { it.identity }
            AssertionFailuresDto(
                window = window,
                // What the numbers are really over: the window's own lower
                // bound when the whole window was read, and the oldest run
                // actually seen when the cap bit or retention had already
                // taken the rest.
                since = (scanned.oldest ?: spec.since).toString(),
                until = spec.now.toString(),
                truncated = scanned.runs >= ASSERTION_RUN_SCAN,
                assertions = folded.values
                    .map { group -> group.reduce(AssertionGroup::absorb) }
                    .filter { it.failures > 0 }
                    .sortedWith(
                        compareByDescending<AssertionGroup> { it.failures }
                            .thenByDescending { it.failureRate() }
                            .thenBy { it.endpointKey }
                            .thenBy { it.identity },
                    )
                    .take(MAX_ASSERTIONS)
                    .mapNotNull { it.toDto() },
            )
        }
    }

    /** One `(endpoint, assertion identity)` group of the window. */
    private class AssertionGroup(
        /** Rewritten in place when [EndpointFolding] gives the endpoint a real method. */
        var endpointKey: String,
        val assertionMethod: String?,
        val scope: String?,
        val op: String?,
        val expected: String?,
        val kind: String?,
        val expression: String?,
        var failures: Long,
        var evaluations: Long,
        var lastFailedAt: Instant?,
    ) {
        /**
         * The group's key, endpoint first — the fold rewrites the endpoint half
         * and regroups on what is left, so the endpoint has to be the prefix.
         * `\u0000` separates: it cannot occur in any of the parts.
         */
        val identity: String
            get() = listOf(endpointKey, assertionMethod, scope, op, expected, kind, expression)
                .joinToString("\u0000") { it ?: "" }

        fun absorb(other: AssertionGroup): AssertionGroup {
            failures += other.failures
            evaluations += other.evaluations
            val theirs = other.lastFailedAt
            if (theirs != null && (lastFailedAt?.isBefore(theirs) != false)) lastFailedAt = theirs
            return this
        }

        fun failureRate(): Double = if (evaluations > 0) failures.toDouble() / evaluations else 0.0

        /**
         * Null for a group that never failed, which the query's `HAVING` and
         * the caller's filter both already exclude — the ranking would have
         * nothing to say about it, and it has no "last failed at" to print.
         */
        fun toDto(): AssertionFailureStat? {
            val failedAt = lastFailedAt ?: return null
            val (method, template) = EndpointKeys.split(endpointKey)
            return AssertionFailureStat(
                endpointKey = endpointKey,
                method = method,
                template = template,
                assertionMethod = assertionMethod,
                scope = scope,
                op = op,
                expected = expected,
                kind = kind,
                expression = expression,
                failures = failures,
                evaluations = evaluations,
                failureRatePct = round(failureRate() * 10000.0) / 100.0,
                lastFailedAt = failedAt.toString(),
            )
        }
    }

    /** How much of the window the assertion scan actually reached. */
    private class AssertionScan(val runs: Long, val oldest: Instant?)

    /** The scanned runs' count and oldest start — the two numbers `since`/`truncated` are read off. */
    private fun readAssertionScanBounds(serviceId: UUID, since: Instant): AssertionScan {
        val sql = """
            SELECT count(*) AS runs, min(started_at) AS oldest
            FROM (${assertionScanSql(serviceId, since)}) t
        """.trimIndent()
        var scan = AssertionScan(0, null)
        TransactionManager.current().exec(sql) { rs ->
            if (rs.next()) scan = AssertionScan(rs.getLong("runs"), rs.getTimestamp("oldest")?.toInstant())
        }
        return scan
    }

    /**
     * The runs the ranking is computed over: the newest [ASSERTION_RUN_SCAN] of
     * the window, by the `(service_id, started_at DESC)` index. Skipped ticks
     * are excluded as they are everywhere else — a tick that never ran
     * evaluated no assertion.
     */
    private fun assertionScanSql(serviceId: UUID, since: Instant): String = """
        SELECT id, started_at
        FROM probe_results
        WHERE service_id = '$serviceId'
          AND started_at >= '${sqlTimestamp(since)}'
          AND status != 'skipped'
        ORDER BY started_at DESC
        LIMIT $ASSERTION_RUN_SCAN
    """.trimIndent()

    /**
     * One row per `(endpoint, assertion identity)` seen in the scanned runs.
     *
     * The identity is the **declared** side of the assertion and nothing else:
     * `method`/`scope`/`op`/`expected` for a scope assertion, `kind` and the
     * rendered `expression` for an `assert` condition. `actual`, `actualLhs`,
     * `actualRhs` and `options` are what the target answered — grouping on them
     * would give one row per distinct failure and rank nothing.
     * `assertions[].index` is left out too: it moves when a script is edited,
     * which would split one assertion's history in two.
     *
     * `assertion_results` holds whatever the executor wrote, so the array is
     * substituted for an empty one rather than filtered in a `WHERE` — the
     * expansion is evaluated before a predicate at the same level, and a row
     * that somehow held an object would abort the whole read.
     *
     * **The steps are reached through a `LATERAL` with a `LIMIT`, and that is
     * load-bearing.** Written as a plain join, the planner hash-joins the
     * scanned runs against *all* of `probe_steps` — measured on PostgreSQL 18
     * against 240 000 steps: `Seq Scan on probe_steps (240 002 rows)`, and the
     * same shape with `enable_seqscan = off`, only through the index. That
     * plan costs the whole table however narrow the window is, and stays
     * cheapest until `probe_steps` is some 20 million rows, which is exactly
     * the size at which the scan it then abandons would have hurt. A subquery
     * carrying a `LIMIT` cannot be flattened into that join, so the shape is
     * the one the cap was chosen for:
     *
     *     Nested Loop  (rows=180 000)
     *       -> Limit (rows=20 000)
     *            -> Index Scan using idx_probe_results_service
     *       -> Index Scan using idx_probe_steps_result (20 000 searches)
     *       -> Function Scan on jsonb_array_elements
     *     20 000 runs / 60 000 steps / 180 000 assertions, 120 000 buffers, 1.4 s
     *
     * — work proportional to the window, not to the table.
     */
    private fun readAssertionGroups(serviceId: UUID, since: Instant): List<AssertionGroup> {
        val failed = "a.value->>'outcome' = 'failed'"
        val sql = """
            WITH scanned AS (${assertionScanSql(serviceId, since)})
            SELECT COALESCE(s.endpoint_key, ${EndpointKeySql.fallbackKey("s.request_url")}) AS endpoint_key,
                   a.value->>'method'                          AS assertion_method,
                   a.value->>'scope'                           AS scope,
                   a.value->>'op'                              AS op,
                   left(a.value->>'expected', $MAX_EXPECTED_CHARS)     AS expected,
                   a.value->>'kind'                            AS kind,
                   left(a.value->>'expression', $MAX_EXPRESSION_CHARS) AS expression,
                   count(*)                                    AS evaluations,
                   count(*) FILTER (WHERE $failed)             AS failures,
                   max(r.started_at) FILTER (WHERE $failed)    AS last_failed_at
            FROM scanned r
            CROSS JOIN LATERAL (
                SELECT st.endpoint_key, st.request_url, st.assertion_results
                FROM probe_steps st
                WHERE st.probe_result_id = r.id
                LIMIT $MAX_STEPS_PER_RUN
            ) s
            CROSS JOIN LATERAL jsonb_array_elements(
                CASE WHEN jsonb_typeof(s.assertion_results) = 'array'
                     THEN s.assertion_results ELSE '[]'::jsonb END
            ) AS a
            GROUP BY 1, 2, 3, 4, 5, 6, 7
            HAVING count(*) FILTER (WHERE $failed) > 0
            ORDER BY failures DESC, evaluations DESC
            LIMIT $ASSERTION_GROUP_SCAN
        """.trimIndent()

        val out = mutableListOf<AssertionGroup>()
        // The statement opens with a CTE, and Exposed reads the statement type
        // off the leading keyword — left to guess it would run this through
        // `executeUpdate` and throw on the rows it gets back.
        TransactionManager.current().exec(sql, explicitStatementType = StatementType.SELECT) { rs ->
            while (rs.next()) {
                out += AssertionGroup(
                    endpointKey = rs.getString("endpoint_key"),
                    assertionMethod = rs.getString("assertion_method"),
                    scope = rs.getString("scope"),
                    op = rs.getString("op"),
                    expected = rs.getString("expected"),
                    kind = rs.getString("kind"),
                    expression = rs.getString("expression"),
                    failures = rs.getLong("failures"),
                    evaluations = rs.getLong("evaluations"),
                    lastFailedAt = rs.getTimestamp("last_failed_at")?.toInstant(),
                )
            }
        }
        return out
    }

    /** An expected value is a label, not a document — enough of it to recognise. */
    private const val MAX_EXPECTED_CHARS = 120

    /** The same for a rendered `assert` condition, which is a line of source. */
    private const val MAX_EXPRESSION_CHARS = 200

    // ── Failure heatmap ─────────────────────────────────────────────────

    /**
     * The longest lookback the heatmap accepts. It is the default retention of
     * hourly aggregate rows: asking for more could only return the same grid.
     */
    const val MAX_HEATMAP_DAYS = 365

    /** The heatmap's default lookback — a quarter, enough for a weekly shape to show. */
    const val DEFAULT_HEATMAP_DAYS = 90

    /**
     * Failed runs by hour of day and ISO weekday over the last [days] days.
     *
     * Read from the all-agents hourly rows of `probe_aggregates`, which is the
     * only place holding a correct *run* failure count per hour:
     * `probe_step_aggregates` counts calls, and files an assertion failure
     * under the `200` the target answered with.
     *
     * **Everything is UTC and there is no timezone parameter.** An offset in
     * the query would make every cached grid per-viewer, and a named zone would
     * have to be applied to a `timestamp` column written in the writing JVM's
     * wall clock — one clock for the bounds and another for the truncation is
     * how the daily bucket already goes wrong off UTC. So the column is
     * converted once, and the bounds and the extraction both read the result.
     */
    fun getFailureHeatmap(serviceId: UUID, days: Int): FailureHeatmapDto {
        val span = days.coerceIn(1, MAX_HEATMAP_DAYS)
        val now = Instant.now()
        val since = now.truncatedTo(ChronoUnit.HOURS).minus(span.toLong(), ChronoUnit.DAYS)
        val zone = ZoneId.systemDefault()

        // Service id is a UUID object and the bound is this method's own
        // arithmetic — neither is caller text. The zone is the JVM's own id.
        val sql = """
            SELECT extract(isodow FROM utc)::int AS weekday,
                   extract(hour   FROM utc)::int AS hour,
                   sum(probe_count)::bigint      AS runs,
                   sum(round((probe_count * coalesce(error_rate, 0))::numeric))::bigint AS failed_runs,
                   min(bucket_start)             AS first_bucket,
                   max(bucket_start)             AS last_bucket
            FROM (
                SELECT bucket_start,
                       bucket_start AT TIME ZONE '${zone.id}' AT TIME ZONE 'UTC' AS utc,
                       probe_count,
                       error_rate
                FROM probe_aggregates
                WHERE service_id = '$serviceId'
                  AND probe_agent_id IS NULL
                  AND bucket_type = 'hourly'
                  AND bucket_start >= '${sqlTimestamp(since)}'
                  AND probe_count > 0
            ) t
            GROUP BY 1, 2
            ORDER BY 1, 2
        """.trimIndent()

        val cells = mutableListOf<HeatmapCell>()
        var runs = 0L
        var failed = 0L
        var first: Instant? = null
        var last: Instant? = null
        transaction {
            TransactionManager.current().exec(sql) { rs ->
                while (rs.next()) {
                    val cellRuns = rs.getLong("runs")
                    val cellFailed = rs.getLong("failed_runs")
                    cells += HeatmapCell(
                        weekday = rs.getInt("weekday"),
                        hour = rs.getInt("hour"),
                        runs = cellRuns,
                        failedRuns = cellFailed,
                    )
                    runs += cellRuns
                    failed += cellFailed
                    val cellFirst = rs.getTimestamp("first_bucket")?.toInstant()
                    val cellLast = rs.getTimestamp("last_bucket")?.toInstant()
                    if (cellFirst != null && (first?.isAfter(cellFirst) != false)) first = cellFirst
                    if (cellLast != null && (last?.isBefore(cellLast) != false)) last = cellLast
                }
            }
        }

        return FailureHeatmapDto(
            days = span,
            timezone = "UTC",
            since = since.toString(),
            until = now.toString(),
            coveredFrom = first?.toString(),
            coveredTo = last?.toString(),
            totalRuns = runs,
            totalFailedRuns = failed,
            cells = cells,
        )
    }

    /**
     * An instant as the literal a `timestamp` column compares against.
     *
     * `bucket_start` and `started_at` are timestamps **without** a zone, and
     * everything that writes them does so through JDBC — i.e. as the writing
     * JVM's local wall clock. A bound has to be spelled in that same clock or
     * it selects the wrong buckets everywhere but UTC.
     */
    private fun sqlTimestamp(instant: Instant): String = SQL_TIMESTAMP.format(instant)

    private val SQL_TIMESTAMP: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault())

    private fun ResultRow.toStatBucket() = StatBucket(
        bucketStart = this[ProbeAggregates.bucketStart].toString(),
        p50Ms = this[ProbeAggregates.p50Ms],
        p95Ms = this[ProbeAggregates.p95Ms],
        p99Ms = this[ProbeAggregates.p99Ms],
        uptimePct = this[ProbeAggregates.uptimePct]?.let { round(it * 10000.0) / 100.0 },
        errorRatePct = this[ProbeAggregates.errorRate]?.let { round(it * 10000.0) / 100.0 },
        probeCount = this[ProbeAggregates.probeCount],
    )

    /**
     * Returns current counters and state for a single service.
     * Reads from Redis B first; on cache miss, computes from probe_results and backfills cache.
     */
    fun getServiceMetrics(serviceId: UUID): ServiceMetricsDto? {
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"
        val percKey = "metrics:svc:$serviceId:percentiles"

        val counters = redis.hgetall(counterKey)
        val state = redis.hgetall(stateKey)

        if (counters.isNotEmpty() || state.isNotEmpty()) {
            val perc = redis.hgetall(percKey)
            val percentiles = if (perc.isNotEmpty()) ResponsePercentiles(
                p50 = perc["p50"]?.toLongOrNull() ?: 0,
                p95 = perc["p95"]?.toLongOrNull() ?: 0,
                p99 = perc["p99"]?.toLongOrNull() ?: 0,
            ) else null

            return ServiceMetricsDto(
                counters = MetricsCounters(
                    probesTotal = counters["probes_total"]?.toLongOrNull() ?: 0,
                    probesSuccess = counters["probes_success"]?.toLongOrNull() ?: 0,
                    probesFailure = counters["probes_failure"]?.toLongOrNull() ?: 0,
                    probesTimeout = counters["probes_timeout"]?.toLongOrNull() ?: 0,
                ),
                state = MetricsState(
                    lastStatus = state["last_status"],
                    lastConsecutive = state["last_consecutive"]?.toLongOrNull() ?: 0,
                    lastResponseMs = state["last_response_ms"]?.toLongOrNull() ?: 0,
                    lastRunAt = state["last_run_at"]?.toLongOrNull(),
                ),
                percentiles = percentiles,
            )
        }

        // Cache miss — compute from DB and backfill
        return computeAndCacheServiceMetrics(serviceId)
    }

    /**
     * Computes metrics from probe_results for a service and writes them to Redis B.
     * Returns null if the service has no results at all.
     */
    private fun computeAndCacheServiceMetrics(serviceId: UUID): ServiceMetricsDto? {
        data class DbMetrics(
            val total: Long,
            val success: Long,
            val failure: Long,
            val timeout: Long,
            val lastStatus: String?,
            val lastResponseMs: Long,
            val lastRunAt: Long?,
            val percentiles: ResponsePercentiles?,
        )

        val db = transaction {
            // Skipped rows are history-only: a tick that never ran is not a
            // probe that failed. Counting them would put them in the uptime
            // denominator and quietly drag the number down every time the
            // platform sheds a tick or finds no agent to run on — and the
            // live Redis counters (which the ingestor never increments for a
            // skipped result) would then disagree with this DB backfill.
            val results = ProbeResults.selectAll()
                .where { (ProbeResults.serviceId eq serviceId) and (ProbeResults.status neq "skipped") }
                .toList()

            if (results.isEmpty()) return@transaction null

            val total = results.size.toLong()
            val success = results.count { it[ProbeResults.status] == "success" }.toLong()
            val failure = results.count { it[ProbeResults.status] == "failure" }.toLong()
            val timeout = results.count { it[ProbeResults.status] == "timeout" }.toLong()

            val latest = results.maxByOrNull { it[ProbeResults.startedAt] }
            val lastStatus = latest?.get(ProbeResults.status)
            val lastResponseMs = latest?.get(ProbeResults.totalResponseMs)?.toLong() ?: 0
            val lastRunAt = latest?.get(ProbeResults.startedAt)?.epochSecond

            // Percentiles from probe_aggregates (all-agents rollup rows)
            val aggregates = ProbeAggregates.selectAll()
                .where {
                    (ProbeAggregates.serviceId eq serviceId) and
                    (ProbeAggregates.probeAgentId.isNull()) and
                    (ProbeAggregates.bucketType eq "hourly")
                }
                .orderBy(ProbeAggregates.bucketStart, SortOrder.DESC)
                .toList()

            val percentiles = if (aggregates.isNotEmpty()) {
                // Weighted average across hourly buckets
                var totalCount = 0L
                var sumP50 = 0L
                var sumP95 = 0L
                var sumP99 = 0L
                for (a in aggregates) {
                    val count = a[ProbeAggregates.probeCount].toLong()
                    val p50 = a[ProbeAggregates.p50Ms]?.toLong() ?: continue
                    val p95 = a[ProbeAggregates.p95Ms]?.toLong() ?: continue
                    val p99 = a[ProbeAggregates.p99Ms]?.toLong() ?: continue
                    totalCount += count
                    sumP50 += p50 * count
                    sumP95 += p95 * count
                    sumP99 += p99 * count
                }
                if (totalCount > 0) ResponsePercentiles(
                    p50 = sumP50 / totalCount,
                    p95 = sumP95 / totalCount,
                    p99 = sumP99 / totalCount,
                ) else null
            } else null

            DbMetrics(total, success, failure, timeout, lastStatus, lastResponseMs, lastRunAt, percentiles)
        } ?: return null

        // Backfill Redis cache
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"
        val percKey = "metrics:svc:$serviceId:percentiles"
        try {
            redis.hset(counterKey, mapOf(
                "probes_total" to db.total.toString(),
                "probes_success" to db.success.toString(),
                "probes_failure" to db.failure.toString(),
                "probes_timeout" to db.timeout.toString(),
            ))
            redis.expire(counterKey, 86400)

            val stateMap = mutableMapOf<String, String>()
            db.lastStatus?.let { stateMap["last_status"] = it }
            stateMap["last_response_ms"] = db.lastResponseMs.toString()
            db.lastRunAt?.let { stateMap["last_run_at"] = it.toString() }
            stateMap["last_consecutive"] = "1"
            redis.hset(stateKey, stateMap)
            redis.expire(stateKey, 86400)

            if (db.percentiles != null) {
                redis.hset(percKey, mapOf(
                    "p50" to db.percentiles.p50.toString(),
                    "p95" to db.percentiles.p95.toString(),
                    "p99" to db.percentiles.p99.toString(),
                ))
                redis.expire(percKey, 86400)
            }
        } catch (_: Exception) {
            // Best-effort cache backfill
        }

        return ServiceMetricsDto(
            counters = MetricsCounters(db.total, db.success, db.failure, db.timeout),
            state = MetricsState(db.lastStatus, 1, db.lastResponseMs, db.lastRunAt),
            percentiles = db.percentiles,
        )
    }

    /**
     * Aggregates counters and picks the most recent state across multiple services.
     * Returns null if none of the services have any metrics.
     */
    fun getAggregatedMetrics(serviceIds: List<UUID>): ServiceMetricsDto? {
        if (serviceIds.isEmpty()) return null

        var totalProbes = 0L
        var totalSuccess = 0L
        var totalFailure = 0L
        var totalTimeout = 0L
        var latestRunAt: Long? = null
        var latestStatus: String? = null
        var latestResponseMs = 0L
        var latestConsecutive = 0L
        var hasAny = false

        fun accumulate(m: ServiceMetricsDto) {
            hasAny = true
            totalProbes += m.counters.probesTotal
            totalSuccess += m.counters.probesSuccess
            totalFailure += m.counters.probesFailure
            totalTimeout += m.counters.probesTimeout

            val runAt = m.state.lastRunAt
            if (runAt != null && (latestRunAt?.let { runAt > it } != false)) {
                latestRunAt = runAt
                latestStatus = m.state.lastStatus
                latestResponseMs = m.state.lastResponseMs
                latestConsecutive = m.state.lastConsecutive
            }
        }

        // Pipelined counter+state reads for every service; one await instead
        // of two round-trips per service.
        val async = redis.statefulConnection.async()
        val counterFutures = serviceIds.map { async.hgetall("metrics:svc:$it:counters") }
        val stateFutures = serviceIds.map { async.hgetall("metrics:svc:$it:state") }
        LettuceFutures.awaitAll(30, TimeUnit.SECONDS, *(counterFutures + stateFutures).toTypedArray())

        val coldServices = mutableListOf<UUID>()
        for (i in serviceIds.indices) {
            val counters = counterFutures[i].get()
            val state = stateFutures[i].get()
            if (counters.isEmpty() && state.isEmpty()) {
                coldServices.add(serviceIds[i])
                continue
            }
            accumulate(ServiceMetricsDto(
                counters = MetricsCounters(
                    probesTotal = counters["probes_total"]?.toLongOrNull() ?: 0,
                    probesSuccess = counters["probes_success"]?.toLongOrNull() ?: 0,
                    probesFailure = counters["probes_failure"]?.toLongOrNull() ?: 0,
                    probesTimeout = counters["probes_timeout"]?.toLongOrNull() ?: 0,
                ),
                state = MetricsState(
                    lastStatus = state["last_status"],
                    lastConsecutive = state["last_consecutive"]?.toLongOrNull() ?: 0,
                    lastResponseMs = state["last_response_ms"]?.toLongOrNull() ?: 0,
                    lastRunAt = state["last_run_at"]?.toLongOrNull(),
                ),
            ))
        }

        // Services Redis doesn't know (cold cache): per-service DB compute,
        // which also backfills Redis for the next poll.
        for (id in coldServices) {
            computeAndCacheServiceMetrics(id)?.let { accumulate(it) }
        }

        if (!hasAny) return null

        return ServiceMetricsDto(
            counters = MetricsCounters(totalProbes, totalSuccess, totalFailure, totalTimeout),
            state = MetricsState(latestStatus, latestConsecutive, latestResponseMs, latestRunAt),
        )
    }

    /**
     * Aggregates hourly buckets across multiple services for the last [hours] hours.
     * Each hour bucket sums total/success/failure/timeout/sumMs across all services.
     *
     * Closed hours come from sealed Redis hashes only — an unsealed one is
     * recomputed from `probe_results` and sealed, see [readClosedBuckets]. The
     * hour in progress is read from Redis as it stands.
     */
    fun getAggregatedHistory(serviceIds: List<UUID>, hours: Int): List<HourlyBucket> {
        val now = Instant.now()
        val bucketKeys = HourBuckets.window(now, hours)
        if (bucketKeys.isEmpty()) return emptyList()
        if (serviceIds.isEmpty()) {
            return bucketKeys.map { emptyBucket(it) }
        }

        // window() ends on the hour in progress, so that is the one open key
        // and everything before it is closed and final.
        val (closedKeys, openKeys) = HourBuckets.splitClosed(bucketKeys, now)
        val currentKey = openKeys.last()

        // Closed hours are immutable — cache the assembled series until the
        // hour rolls over (the key embeds the current hour, so rollover is a
        // natural miss). Keyed by the exact visible service set so per-user
        // access differences can never leak. Worst case: one full recompute per
        // id-set per hour, instead of one per 30 seconds.
        val closedCacheKey = "metrics:agg:history:closed:$hours:$currentKey:${idSetHash(serviceIds)}"
        val closed: List<HourlyBucket> = redis.get(closedCacheKey)?.let { cached ->
            try {
                Json.decodeFromString<List<HourlyBucket>>(cached)
            } catch (_: Exception) {
                null // stale shape — recompute
            }
        } ?: run {
            val computed = readClosedBuckets(serviceIds, closedKeys, now)
            redis.set(closedCacheKey, Json.encodeToString<List<HourlyBucket>>(computed), SetArgs.Builder.ex(CLOSED_CACHE_SECONDS))
            computed
        }

        // The live hour is read fresh on every request — one per-hour batch
        // (≈ one read per service), bounded memory, always current.
        var current = readHourBucket(serviceIds, currentKey)
        if (current.total == 0L) {
            // Redis may be cold right after a restart — DB fallback for this hour.
            dbHistoryBuckets(serviceIds, currentKey, currentKey)[currentKey]?.let { current = it }
        }

        return closed + current
    }

    /**
     * Sums one hour across all services with a single pipelined batch —
     * batching per hour (not hours x services at once) bounds the transient
     * allocation to one hour's worth of futures.
     */
    private fun readHourBucket(serviceIds: List<UUID>, bucketKey: String): HourlyBucket {
        val sum = BucketSum()
        for (data in readHourHashes(serviceIds, bucketKey)) sum.add(data)
        return sum.toBucket(bucketKey)
    }

    /** One pipelined `HGETALL` per service for one hour, in [serviceIds] order. */
    private fun readHourHashes(serviceIds: List<UUID>, bucketKey: String): List<Map<String, String>> {
        val async = redis.statefulConnection.async()
        val futures = serviceIds.map { id -> async.hgetall("metrics:svc:$id:h:$bucketKey") }
        LettuceFutures.awaitAll(30, TimeUnit.SECONDS, *futures.toTypedArray())
        return futures.map { it.get() }
    }

    /**
     * Assembles the closed hours of a request.
     *
     * A closed hour is trusted only where the service's Redis hash carries the
     * seal. Everything else is recomputed: from the outside there is no telling
     * a complete hour from one a mid-hour Redis B restart cut in half, from one
     * the cache dropped whole, or from an hour that genuinely had no runs — the
     * hash holds a number either way. All of those go to `probe_results` in ONE
     * grouped query, are written back sealed, and are what the response carries.
     * The hour is over, so the recomputed value is final.
     */
    private fun readClosedBuckets(serviceIds: List<UUID>, closedKeys: List<String>, now: Instant): List<HourlyBucket> {
        if (closedKeys.isEmpty()) return emptyList()

        val totals = closedKeys.associateWith { BucketSum() }
        val unsealed = linkedMapOf<String, MutableList<UUID>>()

        for (hourKey in closedKeys) {
            for ((index, data) in readHourHashes(serviceIds, hourKey).withIndex()) {
                if (HourBuckets.isSealed(data)) {
                    totals.getValue(hourKey).add(data)
                } else {
                    unsealed.getOrPut(hourKey) { mutableListOf() }.add(serviceIds[index])
                }
            }
        }

        if (unsealed.isNotEmpty()) {
            val services = unsealed.values.flatten().distinct()
            val fromDb = dbHistoryBucketsByService(services, unsealed.keys.min(), unsealed.keys.max())
            val seals = mutableListOf<Triple<UUID, String, HourlyBucket>>()
            for ((hourKey, ids) in unsealed) {
                for (id in ids) {
                    val bucket = fromDb[id]?.get(hourKey) ?: emptyBucket(hourKey)
                    totals.getValue(hourKey).add(bucket)
                    // Hours older than the bucket TTL are answered from the DB
                    // but not written back — the cache would drop them again
                    // before anyone read them.
                    if (HourBuckets.isWithinRetention(hourKey, now, hourlyBucketTtlSeconds)) {
                        seals += Triple(id, hourKey, bucket)
                    }
                }
            }
            sealBuckets(seals)
        }

        return closedKeys.map { totals.getValue(it).toBucket(it) }
    }

    /**
     * Writes recomputed closed hours back to their Redis hashes, sealed, with
     * the TTL the ingest path gives an hourly bucket — one pipelined batch, so
     * a cold cache costs round trips once rather than per bucket.
     *
     * Best effort: the DB already answered the request, so a cache that refuses
     * the write must not fail it. The hours simply stay unsealed and are
     * recomputed on the next read.
     */
    private fun sealBuckets(seals: List<Triple<UUID, String, HourlyBucket>>) {
        if (seals.isEmpty()) return
        try {
            val async = redis.statefulConnection.async()
            val futures = mutableListOf<RedisFuture<*>>()
            for ((serviceId, hourKey, bucket) in seals) {
                val key = "metrics:svc:$serviceId:h:$hourKey"
                futures += async.hset(key, mapOf(
                    "total" to bucket.total.toString(),
                    "success" to bucket.success.toString(),
                    "failure" to bucket.failure.toString(),
                    "timeout" to bucket.timeout.toString(),
                    "sum_ms" to bucket.sumMs.toString(),
                    "call_count" to bucket.callCount.toString(),
                    HourBuckets.SEALED_FIELD to HourBuckets.SEALED_VALUE,
                ))
                futures += async.expire(key, hourlyBucketTtlSeconds)
            }
            if (!LettuceFutures.awaitAll(30, TimeUnit.SECONDS, *futures.toTypedArray())) {
                historyLog.warn("sealing {} hourly buckets timed out", seals.size)
            }
        } catch (e: Exception) {
            historyLog.warn("sealing {} hourly buckets failed: {}", seals.size, e.message)
        }
    }

    private fun emptyBucket(hour: String) =
        HourlyBucket(hour = hour, total = 0, success = 0, failure = 0, timeout = 0, sumMs = 0)

    /** Running total of one hour across services. */
    private class BucketSum {
        private var total = 0L
        private var success = 0L
        private var failure = 0L
        private var timeout = 0L
        private var sumMs = 0L
        private var callCount = 0L

        fun add(hash: Map<String, String>) {
            if (hash.isEmpty()) return
            total += hash["total"]?.toLongOrNull() ?: 0
            success += hash["success"]?.toLongOrNull() ?: 0
            failure += hash["failure"]?.toLongOrNull() ?: 0
            timeout += hash["timeout"]?.toLongOrNull() ?: 0
            sumMs += hash["sum_ms"]?.toLongOrNull() ?: 0
            callCount += hash["call_count"]?.toLongOrNull() ?: 0
        }

        fun add(bucket: HourlyBucket) {
            total += bucket.total
            success += bucket.success
            failure += bucket.failure
            timeout += bucket.timeout
            sumMs += bucket.sumMs
            callCount += bucket.callCount
        }

        fun toBucket(hour: String) = HourlyBucket(
            hour = hour,
            total = total,
            success = success,
            failure = failure,
            timeout = timeout,
            sumMs = sumMs,
            callCount = callCount,
        )
    }

    /** Just past an hour: the key embeds the hour, the TTL is only cleanup. */
    private const val CLOSED_CACHE_SECONDS = 3900L

    private fun idSetHash(ids: List<UUID>): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        for (id in ids.sorted()) digest.update(id.toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Aggregates probe_results into hourly buckets in SQL for the inclusive
     * hour-key range [firstHour, lastHour], summed over [serviceIds]. Returns
     * buckets keyed by hour.
     */
    private fun dbHistoryBuckets(serviceIds: List<UUID>, firstHour: String, lastHour: String): Map<String, HourlyBucket> {
        val sums = mutableMapOf<String, BucketSum>()
        for (perHour in dbHistoryBucketsByService(serviceIds, firstHour, lastHour).values) {
            for ((hour, bucket) in perHour) sums.getOrPut(hour) { BucketSum() }.add(bucket)
        }
        return sums.mapValues { (hour, sum) -> sum.toBucket(hour) }
    }

    /**
     * The same aggregation kept per service, so a recomputed hour can be
     * written back to the per-service Redis hash it came from. One query for
     * every service and every hour in the inclusive range [firstHour, lastHour]
     * — never raw rows, never a query per hour.
     */
    private fun dbHistoryBucketsByService(serviceIds: List<UUID>, firstHour: String, lastHour: String): Map<UUID, Map<String, HourlyBucket>> {
        if (serviceIds.isEmpty()) return emptyMap()
        // `started_at` is a timestamp WITHOUT a zone, and the ingestor stores an
        // Instant in it through the JDBC driver — i.e. as the writing JVM's
        // local wall clock. So the bounds go in that same wall clock, and the
        // column is converted back to UTC before it is bucketed, because the
        // hour keys these buckets answer to are UTC. On a UTC deployment both
        // are no-ops; anywhere else they are what keeps an hour's rows in the
        // hour Redis filed them under.
        val zone = ZoneId.systemDefault()
        val sqlTsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone)
        val start = HourBuckets.start(firstHour)
        val end = HourBuckets.start(lastHour).plusSeconds(3600)
        // Service ids come from the DB (UUID objects) — safe to inline.
        val idArray = serviceIds.joinToString(",") { "'$it'" }
        val sql = """
            SELECT service_id AS svc,
                   to_char(date_trunc('hour', started_at AT TIME ZONE '${zone.id}' AT TIME ZONE 'UTC'), 'YYYYMMDDHH24') AS hr,
                   count(*) AS total,
                   count(*) FILTER (WHERE status = 'success') AS success,
                   count(*) FILTER (WHERE status = 'failure') AS failure,
                   count(*) FILTER (WHERE status = 'timeout') AS timeout,
                   coalesce(sum(total_response_ms), 0) AS sum_ms,
                   coalesce(sum(jsonb_array_length(coalesce(raw_result->'calls', '[]'::jsonb))), 0) AS call_count
            FROM probe_results
            WHERE service_id IN ($idArray)
              AND started_at >= '${sqlTsFormatter.format(start)}'
              AND started_at < '${sqlTsFormatter.format(end)}'
              -- A skipped tick never ran, so it belongs in neither the
              -- numerator nor the denominator of this hour's uptime. The
              -- Redis hourly buckets exclude it as well; without this the DB
              -- fallback for the same hour would return a different number.
              AND status != 'skipped'
            GROUP BY 1, 2
        """.trimIndent()

        val out = mutableMapOf<UUID, MutableMap<String, HourlyBucket>>()
        transaction {
            exec(sql) { rs ->
                while (rs.next()) {
                    val svc = UUID.fromString(rs.getString("svc"))
                    val hr = rs.getString("hr")
                    out.getOrPut(svc) { mutableMapOf() }[hr] = HourlyBucket(
                        hour = hr,
                        total = rs.getLong("total"),
                        success = rs.getLong("success"),
                        failure = rs.getLong("failure"),
                        timeout = rs.getLong("timeout"),
                        sumMs = rs.getLong("sum_ms"),
                        callCount = rs.getLong("call_count"),
                    )
                }
            }
        }
        return out
    }

    private val recentProbesLog = org.slf4j.LoggerFactory.getLogger("dev.tracedown.gateway.metrics.recent-probes")

    /**
     * Returns the last [limit] recent probe points for a service.
     * Reads from Redis B cache first; on miss, computes from probe_results and backfills.
     *
     * Simultaneous-mode probes produce multiple results per scheduled run.
     * Points within the same minute are compacted into one: response times averaged,
     * call counts and failures summed, worst status wins.
     *
     * Points are returned in chronological order (oldest first).
     */
    fun getServiceRecentProbes(serviceId: UUID, limit: Int = 10): List<ProbePoint> {
        // Read more raw entries than needed — simultaneous results compact down
        val rawLimit = limit * 5
        val key = "metrics:svc:$serviceId:recent-probes"
        val len = try { redis.llen(key) } catch (_: Exception) { 0L }

        val raw = if (len >= limit) {
            val entries = redis.lrange(key, 0, (rawLimit - 1).toLong())
            entries.mapNotNull { parseProbePointEntry(it) }.reversed()
        } else {
            recentProbesLog.debug("recent-probe cache miss for {} (len={}, need={}), falling back to DB", serviceId, len, limit)
            computeAndCacheRecentProbes(serviceId, rawLimit)
        }

        return compactByMinute(raw).takeLast(limit)
    }

    /**
     * Groups recent probe points by minute and merges each group:
     * - avgResponseMs: weighted average across agents
     * - callCount: sum
     * - failedCalls: sum
     * - status: worst (failure > timeout > success)
     * - timestamp: from the first point in the group
     */
    private fun compactByMinute(points: List<ProbePoint>): List<ProbePoint> {
        if (points.isEmpty()) return emptyList()

        return points
            .groupBy { it.timestamp / 60 } // group by minute
            .entries
            .sortedBy { it.key }
            .map { (_, group) ->
                val totalCalls = group.sumOf { it.callCount }
                val weightedMs = group.sumOf { it.avgResponseMs.toLong() * it.callCount }
                val avgMs = if (totalCalls > 0) (weightedMs / totalCalls).toInt() else 0
                ProbePoint(
                    status = worstStatus(group.map { it.status }),
                    avgResponseMs = avgMs,
                    callCount = totalCalls,
                    failedCalls = group.sumOf { it.failedCalls },
                    timestamp = group.first().timestamp,
                )
            }
    }

    private fun worstStatus(statuses: List<String>): String = when {
        statuses.any { it == "failure" } -> "failure"
        statuses.any { it == "timeout" } -> "timeout"
        else -> "success"
    }

    private fun parseProbePointEntry(entry: String): ProbePoint? {
        val parts = entry.split("|")
        if (parts.size < 5) return null
        return ProbePoint(
            status = parts[0],
            avgResponseMs = parts[1].toIntOrNull() ?: 0,
            callCount = parts[2].toIntOrNull() ?: 0,
            failedCalls = parts[3].toIntOrNull() ?: 0,
            timestamp = parts[4].toLongOrNull() ?: 0,
        )
    }

    /**
     * Computes recent probe points from the last N probe_results rows and replaces the Redis cache.
     * Returns raw (non-compacted) points in chronological order.
     */
    private fun computeAndCacheRecentProbes(serviceId: UUID, limit: Int): List<ProbePoint> {
        val points = try {
            transaction {
                ProbeResults.selectAll()
                    .where { ProbeResults.serviceId eq serviceId }
                    .orderBy(ProbeResults.startedAt, SortOrder.DESC)
                    .limit(limit)
                    .map { row ->
                        val rawResult = row[ProbeResults.rawResult]
                        val calls = rawResult["calls"]?.jsonArray
                        val callCount = calls?.size ?: 0
                        val totalResponseMs = row[ProbeResults.totalResponseMs]
                        val avgResponseMs = if (callCount > 0) totalResponseMs / callCount else totalResponseMs
                        // A call is failed when an assertion failed OR it
                        // errored before assertions ran (DNS/connect/timeout).
                        val failedCalls = calls?.count { call ->
                            val obj = call.jsonObject
                            val errored = obj["error"] != null && obj["error"] !is JsonNull
                            errored || obj["assertions"]?.jsonArray?.any { a ->
                                a.jsonObject["outcome"]?.jsonPrimitive?.contentOrNull == "failed"
                            } == true
                        } ?: 0

                        ProbePoint(
                            status = row[ProbeResults.status],
                            avgResponseMs = avgResponseMs,
                            callCount = callCount,
                            failedCalls = failedCalls,
                            timestamp = row[ProbeResults.startedAt].epochSecond,
                        )
                    }
            }
        } catch (e: Exception) {
            recentProbesLog.warn("recent-probe DB query failed for {}: {}", serviceId, e.message)
            return emptyList()
        }

        recentProbesLog.debug("recent-probe DB returned {} points for {}", points.size, serviceId)

        if (points.isEmpty()) return emptyList()

        // Replace Redis cache entirely (delete + repopulate)
        try {
            val key = "metrics:svc:$serviceId:recent-probes"
            redis.del(key)
            // points are DESC from DB; reverse so LPUSH ends up newest-first
            val entries = points.reversed().map { p ->
                "${p.status}|${p.avgResponseMs}|${p.callCount}|${p.failedCalls}|${p.timestamp}"
            }
            redis.lpush(key, *entries.toTypedArray())
            redis.ltrim(key, 0, 49)
            redis.expire(key, 86400)
        } catch (e: Exception) {
            recentProbesLog.warn("recent-probe cache backfill failed for {}: {}", serviceId, e.message)
        }

        return points.reversed() // Return chronological (oldest first)
    }

    /**
     * Returns hourly buckets for a service over the last [hours] hours.
     * Missing buckets are returned as zeroes for continuous time-series display.
     * Falls back to DB on Redis miss.
     */
    fun getServiceHistory(serviceId: UUID, hours: Int): List<HourlyBucket> {
        return getAggregatedHistory(listOf(serviceId), hours)
    }
}
