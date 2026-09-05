package dev.tracedown.gateway.controllers.metrics

import dev.tracedown.common.models.ProbeAggregates
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.gateway.data.metrics.HourlyBucket
import dev.tracedown.gateway.data.metrics.RegionSeries
import dev.tracedown.gateway.data.metrics.ServiceStatisticsDto
import dev.tracedown.gateway.data.metrics.StatBucket
import dev.tracedown.gateway.data.services.ProbePoint
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
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
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

    private val hourFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

    /** Initialize with a Redis B connection provider. */
    fun init(redis: () -> RedisCommands<String, String>) {
        this.redisProvider = redis
    }

    private val redis get() = redisProvider()

    /**
     * Deep statistics for a service, straight from `probe_aggregates` (no Redis):
     * the all-agents trend plus a per-region breakdown, over [window]. Short windows
     * use hourly buckets, long windows daily. `uptime_pct`/`error_rate` are stored as
     * 0..1 fractions and returned as 0..100 percentages.
     */
    fun getServiceStatistics(serviceId: UUID, window: String): ServiceStatisticsDto {
        val (bucketType, since) = windowSpec(window)
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

            ServiceStatisticsDto(window = window, bucketType = bucketType, overall = overall, regions = regions)
        }
    }

    /** Maps a window token to (bucket granularity, earliest bucket start to include). */
    private fun windowSpec(window: String): Pair<String, Instant> = when (window) {
        "7d" -> "hourly" to Instant.now().minus(7, ChronoUnit.DAYS)
        "30d" -> "daily" to Instant.now().minus(30, ChronoUnit.DAYS)
        "90d" -> "daily" to Instant.now().minus(90, ChronoUnit.DAYS)
        else -> "hourly" to Instant.now().minus(24, ChronoUnit.HOURS)
    }

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
     */
    fun getAggregatedHistory(serviceIds: List<UUID>, hours: Int): List<HourlyBucket> {
        val now = Instant.now()
        val bucketKeys = (hours - 1 downTo 0).map { hourFormatter.format(now.minusSeconds(it * 3600L)) }
        if (serviceIds.isEmpty()) {
            return bucketKeys.map { HourlyBucket(hour = it, total = 0, success = 0, failure = 0, timeout = 0, sumMs = 0) }
        }

        val currentKey = bucketKeys.last()
        val closedKeys = bucketKeys.dropLast(1)

        // Closed hours are immutable — cache them until the hour rolls over
        // (the key embeds the current hour, so rollover is a natural miss).
        // Keyed by the exact visible service set so per-user access
        // differences can never leak. Worst case: one full recompute per
        // id-set per hour, instead of one per 30 seconds.
        val closedCacheKey = "metrics:agg:history:closed:$hours:$currentKey:${idSetHash(serviceIds)}"
        val closed: List<HourlyBucket> = redis.get(closedCacheKey)?.let { cached ->
            try {
                Json.decodeFromString<List<HourlyBucket>>(cached)
            } catch (_: Exception) {
                null // stale shape — recompute
            }
        } ?: run {
            val computed = closedKeys.map { readHourBucket(serviceIds, it) }.toMutableList()
            fillEmptiesFromDb(serviceIds, computed)
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
        val async = redis.statefulConnection.async()
        val futures = serviceIds.map { id -> async.hgetall("metrics:svc:$id:h:$bucketKey") }
        LettuceFutures.awaitAll(30, TimeUnit.SECONDS, *futures.toTypedArray())

        var total = 0L; var success = 0L; var failure = 0L
        var timeout = 0L; var sumMs = 0L; var callCount = 0L
        for (future in futures) {
            val data = future.get()
            if (data.isNotEmpty()) {
                total += data["total"]?.toLongOrNull() ?: 0
                success += data["success"]?.toLongOrNull() ?: 0
                failure += data["failure"]?.toLongOrNull() ?: 0
                timeout += data["timeout"]?.toLongOrNull() ?: 0
                sumMs += data["sum_ms"]?.toLongOrNull() ?: 0
                callCount += data["call_count"]?.toLongOrNull() ?: 0
            }
        }
        return HourlyBucket(hour = bucketKey, total = total, success = success, failure = failure, timeout = timeout, sumMs = sumMs, callCount = callCount)
    }

    /**
     * Hours Redis knows nothing about (cache flush / restart): fill from the
     * DB with ONE grouped query over the whole gap span — never by loading
     * raw rows per hour.
     */
    private fun fillEmptiesFromDb(serviceIds: List<UUID>, buckets: MutableList<HourlyBucket>) {
        val emptyHours = buckets.filter { it.total == 0L }.map { it.hour }
        if (emptyHours.isEmpty()) return
        val dbBuckets = dbHistoryBuckets(serviceIds, emptyHours.min(), emptyHours.max())
        for ((idx, bucket) in buckets.withIndex()) {
            if (bucket.total == 0L) dbBuckets[bucket.hour]?.let { buckets[idx] = it }
        }
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
     * hour-key range [firstHour, lastHour]. Returns buckets keyed by hour.
     */
    private fun dbHistoryBuckets(serviceIds: List<UUID>, firstHour: String, lastHour: String): Map<String, HourlyBucket> {
        val sqlTsFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)
        val start = java.time.LocalDateTime.parse(firstHour + "0000", DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC)
        val end = java.time.LocalDateTime.parse(lastHour + "0000", DateTimeFormatter.ofPattern("yyyyMMddHHmmss")).toInstant(ZoneOffset.UTC).plusSeconds(3600)
        // Service ids come from the DB (UUID objects) — safe to inline.
        val idArray = serviceIds.joinToString(",") { "'$it'" }
        val sql = """
            SELECT to_char(date_trunc('hour', started_at), 'YYYYMMDDHH24') AS hr,
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
            GROUP BY 1
        """.trimIndent()

        val out = mutableMapOf<String, HourlyBucket>()
        transaction {
            exec(sql) { rs ->
                while (rs.next()) {
                    val hr = rs.getString("hr")
                    out[hr] = HourlyBucket(
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
