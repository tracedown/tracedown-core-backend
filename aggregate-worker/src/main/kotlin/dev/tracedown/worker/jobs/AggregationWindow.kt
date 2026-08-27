package dev.tracedown.worker.jobs

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Which buckets an aggregation run should (re)build.
 *
 * The rollups used to look back a fixed distance and nothing else — three hours
 * for hourly, three days for daily. That is correct only while the worker is
 * never away for longer than the lookback. Four hours of downtime meant four
 * hourly buckets that no run would ever visit again, and once retention removed
 * the raw `probe_results` rows behind them there was nothing left to rebuild
 * them from. The gap was permanent and silent.
 *
 * The window is therefore derived from a persisted watermark instead:
 *
 *  - **Resume from the watermark.** The start of the window is where the last
 *    completed run left off, so a gap of any length is picked up.
 *  - **Always redo the trailing buckets.** Results arrive late (a queue
 *    backlog, an agent that took its time), so the most recent
 *    `trailingBuckets` are rebuilt on every run regardless of the watermark.
 *    That is exactly what the old fixed lookback did, and it is kept.
 *  - **Bounded per run.** A long gap is closed over successive runs rather than
 *    in one query spanning months. Each run advances by at most
 *    `maxBucketsPerRun`.
 *  - **Never reach past the raw data.** Buckets whose `probe_results` rows
 *    retention has already deleted cannot be rebuilt — the aggregate that
 *    exists is the only surviving record of them, and it is more correct than
 *    anything a rebuild could produce. `maxBacklogBuckets` is the floor.
 *
 * Every field is a plain value and the function is total: it is the whole
 * decision, testable without a database.
 */
object AggregationWindow {

    /**
     * A window to aggregate, half-open as `[start, end)`, and the watermark to
     * record once it commits.
     *
     * [watermark] is deliberately behind [end] by the trailing re-check, so the
     * next run reopens those buckets for late arrivals instead of sealing them.
     */
    data class Window(val start: Instant, val end: Instant, val watermark: Instant)

    /**
     * The next window to build, or null when there is nothing to build (the clock
     * has not crossed a bucket boundary since the last run).
     *
     * @param now current time
     * @param unit bucket size — [ChronoUnit.HOURS] or [ChronoUnit.DAYS]
     * @param lastWatermark where the previous completed run stopped, null on
     *   first ever run
     * @param trailingBuckets how many recent buckets to rebuild every run
     * @param maxBucketsPerRun ceiling on the width of one window
     * @param maxBacklogBuckets how far back a rebuild may reach at all —
     *   normally the raw-result retention window
     */
    fun nextWindow(
        now: Instant,
        unit: ChronoUnit,
        lastWatermark: Instant?,
        trailingBuckets: Long,
        maxBucketsPerRun: Long,
        maxBacklogBuckets: Long,
    ): Window? {
        require(maxBucketsPerRun > trailingBuckets) {
            "maxBucketsPerRun ($maxBucketsPerRun) must exceed trailingBuckets ($trailingBuckets), " +
                "or a backlog can never be worked off"
        }

        // Only closed buckets are aggregated: the bucket now falls in is still
        // accumulating results, and building it would just be overwritten.
        val head = now.truncatedTo(unit)
        val trailingStart = head.minus(trailingBuckets, unit)
        val backlogFloor = head.minus(maxBacklogBuckets, unit)

        var start = (lastWatermark ?: trailingStart).truncatedTo(unit)
        // Never older than the raw data that would have to feed the rebuild.
        if (start.isBefore(backlogFloor)) start = backlogFloor
        // Never newer than the trailing re-check — late arrivals land there.
        if (start.isAfter(trailingStart)) start = trailingStart

        val end = minOf(start.plus(maxBucketsPerRun, unit), head)
        if (!start.isBefore(end)) return null

        // Hold the watermark back by the trailing re-check so those buckets stay
        // open; never let it move backwards past the window's own start.
        val watermark = maxOf(start, end.minus(trailingBuckets, unit))
        return Window(start = start, end = end, watermark = watermark)
    }

    /**
     * How far back a rebuild may reach, in buckets, given the raw-result
     * retention setting.
     *
     * A non-positive retention means results are kept forever; a rebuild could
     * then legitimately reach back further than any window we would want to
     * scan in one process, so it is capped at [UNBOUNDED_RETENTION_DAYS].
     */
    fun backlogBuckets(retentionDays: Int, unit: ChronoUnit): Long {
        val days = if (retentionDays <= 0) UNBOUNDED_RETENTION_DAYS else retentionDays.toLong()
        return when (unit) {
            ChronoUnit.HOURS -> days * 24
            ChronoUnit.DAYS -> days
            else -> throw IllegalArgumentException("unsupported bucket unit: $unit")
        }
    }

    /** Backlog ceiling used when raw results are kept forever. */
    const val UNBOUNDED_RETENTION_DAYS = 365L
}
