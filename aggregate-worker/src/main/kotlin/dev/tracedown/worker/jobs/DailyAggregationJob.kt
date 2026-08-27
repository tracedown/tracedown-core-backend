package dev.tracedown.worker.jobs

import dev.tracedown.worker.data.JobWatermarks
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.DailyAggregationJob")

/**
 * Rolls up raw probe_results into daily buckets in probe_aggregates.
 *
 * Runs every 1 hour. The window comes from [AggregationWindow]: the last
 * [TRAILING_BUCKETS] days every time (late-arriving results), plus everything
 * back to the watermark left by the previous run, so a gap longer than the
 * lookback is backfilled rather than lost. See [AggregationWindow].
 *
 * Produces per-agent rows and an all-agents rollup (probe_agent_id IS NULL),
 * both as idempotent upserts.
 */
class DailyAggregationJob(
    override val intervalSeconds: Long = 3600L,
    /** Raw-result retention, in days; bounds how far a backfill may reach. */
    private val resultRetentionDays: Int = 90,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "DailyAggregationJob"

    override suspend fun execute() {
        val watermark = newSuspendedTransaction(Dispatchers.IO) { JobWatermarks.read(name) }

        val window = AggregationWindow.nextWindow(
            now = clock(),
            unit = ChronoUnit.DAYS,
            lastWatermark = watermark,
            trailingBuckets = TRAILING_BUCKETS,
            maxBucketsPerRun = MAX_BUCKETS_PER_RUN,
            maxBacklogBuckets = AggregationWindow.backlogBuckets(resultRetentionDays, ChronoUnit.DAYS),
        )
        if (window == null) {
            log.debug("Daily aggregation: no closed bucket to build yet")
            return
        }

        val tsStart = java.sql.Timestamp.from(window.start)
        val tsEnd = java.sql.Timestamp.from(window.end)

        newSuspendedTransaction(Dispatchers.IO) {
            val conn = this.connection.connection as java.sql.Connection

            // Per-agent aggregation
            conn.prepareStatement(PER_AGENT_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            // All-agents rollup — upserted against the partial unique index
            // over the NULL-agent rows, not deleted and re-inserted.
            conn.prepareStatement(UPSERT_ROLLUP_SQL).use { stmt ->
                stmt.setTimestamp(1, tsStart)
                stmt.setTimestamp(2, tsEnd)
                stmt.executeUpdate()
            }

            JobWatermarks.write(name, window.watermark)
        }

        val buckets = ChronoUnit.DAYS.between(window.start, window.end)
        if (buckets > TRAILING_BUCKETS + 1) {
            log.info(
                "Daily aggregation backfilled {} buckets for window [{}, {}) — resuming from watermark",
                buckets, window.start, window.end,
            )
        } else {
            log.info("Daily aggregation completed for window [{}, {})", window.start, window.end)
        }
    }

    companion object {
        /** Recent buckets rebuilt on every run, so late-arriving results land. */
        const val TRAILING_BUCKETS = 3L

        /** Widest window one run may build — two weeks of daily buckets. */
        const val MAX_BUCKETS_PER_RUN = 14L

        private val PER_AGENT_SQL = """
            INSERT INTO probe_aggregates (id, service_id, probe_agent_id, bucket_start, bucket_type,
                                          p50_ms, p95_ms, p99_ms, error_rate, uptime_pct, probe_count)
            SELECT
                gen_random_uuid(),
                service_id,
                probe_agent_id,
                date_trunc('day', started_at) AS bucket_start,
                'daily',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, probe_agent_id, date_trunc('day', started_at)
            ON CONFLICT (service_id, probe_agent_id, bucket_start, bucket_type)
            DO UPDATE SET
                p50_ms = EXCLUDED.p50_ms,
                p95_ms = EXCLUDED.p95_ms,
                p99_ms = EXCLUDED.p99_ms,
                error_rate = EXCLUDED.error_rate,
                uptime_pct = EXCLUDED.uptime_pct,
                probe_count = EXCLUDED.probe_count
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
                date_trunc('day', started_at) AS bucket_start,
                'daily',
                percentile_cont(0.50) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.95) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                percentile_cont(0.99) WITHIN GROUP (ORDER BY run_duration_ms)::int,
                COUNT(*) FILTER (WHERE status IN ('failure', 'timeout', 'error'))::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*) FILTER (WHERE status = 'success')::real / GREATEST(COUNT(*), 1)::real,
                COUNT(*)
            FROM probe_results
            WHERE started_at >= ? AND started_at < ? AND status != 'skipped'
            GROUP BY service_id, date_trunc('day', started_at)
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
