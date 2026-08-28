package dev.tracedown.worker.jobs

import dev.tracedown.worker.data.JobWatermarks
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.HourlyAggregationJob")

/**
 * Rolls up raw probe_results into hourly buckets in probe_aggregates.
 *
 * Runs every 15 minutes. The window comes from [AggregationWindow]: the last
 * [TRAILING_BUCKETS] hours every time (to pick up late-arriving results), plus
 * everything back to the watermark left by the previous run, so downtime of any
 * length is backfilled instead of leaving a permanent hole. See
 * [AggregationWindow] for the reasoning and the bounds.
 *
 * Produces per-agent rows and an all-agents rollup (probe_agent_id IS NULL).
 * Both are idempotent upserts — safe to re-run, to overlap, and to re-derive
 * a window whose raw rows retention has since removed (such a window updates
 * nothing rather than deleting the aggregate that is now the only record of it).
 *
 * After aggregation, pushes response time percentiles to Redis B so the
 * API gateway can serve them from cache without querying the database.
 */
class HourlyAggregationJob(
    override val intervalSeconds: Long = 900L,
    private val redisB: () -> RedisCommands<String, String>,
    /** Raw-result retention, in days; bounds how far a backfill may reach. */
    private val resultRetentionDays: Int = 90,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "HourlyAggregationJob"

    override suspend fun execute() {
        val watermark = newSuspendedTransaction(Dispatchers.IO) { JobWatermarks.read(name) }

        val window = AggregationWindow.nextWindow(
            now = clock(),
            unit = ChronoUnit.HOURS,
            lastWatermark = watermark,
            trailingBuckets = TRAILING_BUCKETS,
            maxBucketsPerRun = MAX_BUCKETS_PER_RUN,
            maxBacklogBuckets = AggregationWindow.backlogBuckets(resultRetentionDays, ChronoUnit.HOURS),
        )
        if (window == null) {
            log.debug("Hourly aggregation: no closed bucket to build yet")
            return
        }

        val tsStart = java.sql.Timestamp.from(window.start)
        val tsEnd = java.sql.Timestamp.from(window.end)

        newSuspendedTransaction(Dispatchers.IO) {
            val conn = this.connection.connection as java.sql.Connection

            // Per-agent aggregation — ON CONFLICT works because probe_agent_id is NOT NULL
            conn.prepareStatement(PER_AGENT_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            // All-agents rollup. probe_agent_id IS NULL, which the ordinary
            // unique index cannot constrain (Postgres treats NULLs as
            // distinct), so this used to be delete-then-insert. A partial
            // unique index over exactly those rows makes it a real upsert:
            // nothing is removed before the replacement is known to exist, and
            // a bucket cannot end up represented twice.
            conn.prepareStatement(UPSERT_ROLLUP_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            // Same transaction as the work it describes — see JobWatermarks.
            JobWatermarks.write(name, window.watermark)

            // Push percentiles to Redis B cache
            try {
                updatePercentilesCache(conn)
            } catch (e: Exception) {
                log.warn("Failed to update percentiles cache in Redis B", e)
            }
        }

        val buckets = ChronoUnit.HOURS.between(window.start, window.end)
        if (buckets > TRAILING_BUCKETS + 1) {
            log.info(
                "Hourly aggregation backfilled {} buckets for window [{}, {}) — resuming from watermark",
                buckets, window.start, window.end,
            )
        } else {
            log.info("Hourly aggregation completed for window [{}, {})", window.start, window.end)
        }
    }

    /**
     * Reads the latest all-agents rollup percentiles from probe_aggregates
     * and writes them to Redis B per service.
     */
    private fun updatePercentilesCache(conn: java.sql.Connection) {
        val redis = redisB()
        conn.prepareStatement(PERCENTILES_SQL).use { stmt ->
            val rs = stmt.executeQuery()
            var count = 0
            while (rs.next()) {
                val serviceId = rs.getString("service_id")
                val p50 = rs.getLong("p50")
                val p95 = rs.getLong("p95")
                val p99 = rs.getLong("p99")
                val key = "metrics:svc:$serviceId:percentiles"
                redis.hset(key, mapOf(
                    "p50" to p50.toString(),
                    "p95" to p95.toString(),
                    "p99" to p99.toString(),
                ))
                redis.expire(key, 86400)
                count++
            }
            if (count > 0) {
                log.info("Updated percentiles cache for {} services", count)
            }
        }
    }

    companion object {
        /** Recent buckets rebuilt on every run, so late-arriving results land. */
        const val TRAILING_BUCKETS = 3L

        /**
         * Widest window one run may build. A day of hourly buckets per 15-minute
         * run works off a week of downtime in a few minutes of ticks, without
         * any single query scanning a month of raw results.
         */
        const val MAX_BUCKETS_PER_RUN = 24L

        private val PER_AGENT_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                probe_agent_id,
                date_trunc('hour', started_at) AS bucket_start,
                'hourly',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, probe_agent_id, date_trunc('hour', started_at)
            ON CONFLICT (service_id, probe_agent_id, bucket_start, bucket_type)
            DO UPDATE SET
                p50_ms = EXCLUDED.p50_ms,
                p95_ms = EXCLUDED.p95_ms,
                p99_ms = EXCLUDED.p99_ms,
                error_rate = EXCLUDED.error_rate,
                uptime_pct = EXCLUDED.uptime_pct,
                probe_count = EXCLUDED.probe_count
        """.trimIndent()

        /** Computes weighted-average percentiles across all hourly rollup buckets per service. */
        private val PERCENTILES_SQL = """
            SELECT
                service_id,
                (SUM(p50_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p50,
                (SUM(p95_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p95,
                (SUM(p99_ms::bigint * probe_count) / GREATEST(SUM(probe_count), 1)) AS p99
            FROM probe_aggregates
            WHERE probe_agent_id IS NULL
              AND bucket_type = 'hourly'
              AND p50_ms IS NOT NULL
            GROUP BY service_id
        """.trimIndent()

        /**
         * The conflict target repeats the partial index's predicate, which is
         * how Postgres is told to infer `idx_probe_aggregates_rollup_unique`.
         */
        private val UPSERT_ROLLUP_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                NULL,
                date_trunc('hour', started_at) AS bucket_start,
                'hourly',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, date_trunc('hour', started_at)
            ON CONFLICT (service_id, bucket_start, bucket_type) WHERE probe_agent_id IS NULL
            DO UPDATE SET
                p50_ms = EXCLUDED.p50_ms,
                p95_ms = EXCLUDED.p95_ms,
                p99_ms = EXCLUDED.p99_ms,
                error_rate = EXCLUDED.error_rate,
                uptime_pct = EXCLUDED.uptime_pct,
                probe_count = EXCLUDED.probe_count
        """.trimIndent()
    }
}
