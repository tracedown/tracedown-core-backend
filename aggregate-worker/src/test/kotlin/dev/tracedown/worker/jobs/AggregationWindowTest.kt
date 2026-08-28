package dev.tracedown.worker.jobs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which buckets an aggregation run rebuilds.
 *
 * The defect these cover: the rollups looked back a fixed three buckets and kept
 * no cursor. A worker away for longer than that left buckets no run would ever
 * visit again, and once retention removed the raw results behind them the gap
 * was permanent. The window is now derived from a watermark, which has to do
 * three things at once — resume from where the last run stopped, keep redoing
 * the trailing buckets for late arrivals, and never reach past the raw data.
 */
class AggregationWindowTest {

    private val hour = ChronoUnit.HOURS
    private val trailing = 3L
    private val maxPerRun = 24L
    private val backlog = 90L * 24

    /** 2026-08-27T14:37Z — deliberately mid-bucket. */
    private val now: Instant = Instant.parse("2026-08-27T14:37:11Z")
    private val head: Instant = now.truncatedTo(hour) // 14:00

    private fun windowFor(watermark: Instant?, maxRun: Long = maxPerRun, backlogBuckets: Long = backlog) =
        AggregationWindow.nextWindow(
            now = now,
            unit = hour,
            lastWatermark = watermark,
            trailingBuckets = trailing,
            maxBucketsPerRun = maxRun,
            maxBacklogBuckets = backlogBuckets,
        )

    // ---- the bucket that is still filling is never built ----

    @Test
    fun `the window ends at the last closed bucket, not at now`() {
        val p = assertNotNull(windowFor(watermark = null))
        assertEquals(head, p.end)
        assertTrue(p.end.isBefore(now))
    }

    // ---- first run and steady state reproduce the old fixed lookback ----

    @Test
    fun `the first ever run builds exactly the trailing window`() {
        val p = assertNotNull(windowFor(watermark = null))
        assertEquals(head.minus(trailing, hour), p.start)
        assertEquals(head, p.end)
    }

    @Test
    fun `a run that is caught up still redoes the trailing buckets`() {
        // Late-arriving results land in buckets already built. Sealing them at
        // the watermark would lose exactly the results a backlog delayed.
        val caughtUp = head.minus(trailing, hour)
        val p = assertNotNull(windowFor(watermark = caughtUp))
        assertEquals(head.minus(trailing, hour), p.start)
        assertEquals(head, p.end)
    }

    @Test
    fun `a watermark ahead of the trailing window cannot narrow it`() {
        val p = assertNotNull(windowFor(watermark = head.minus(1, hour)))
        assertEquals(head.minus(trailing, hour), p.start)
    }

    @Test
    fun `the steady state watermark is a fixed point`() {
        val first = assertNotNull(windowFor(watermark = null))
        val second = assertNotNull(windowFor(watermark = first.watermark))
        assertEquals(first.start, second.start)
        assertEquals(first.end, second.end)
        assertEquals(first.watermark, second.watermark)
    }

    // ---- downtime is backfilled, which is the whole point ----

    @Test
    fun `a gap longer than the lookback is picked up from the watermark`() {
        // Eight hours of downtime: the old fixed three-hour lookback would have
        // left five buckets permanently unbuilt.
        val p = assertNotNull(windowFor(watermark = head.minus(8, hour)))
        assertEquals(head.minus(8, hour), p.start)
        assertEquals(head, p.end)
        assertEquals(8L, ChronoUnit.HOURS.between(p.start, p.end))
    }

    @Test
    fun `a very long gap is closed over successive runs`() {
        val watermark = head.minus(200, hour)
        val first = assertNotNull(windowFor(watermark = watermark))
        assertEquals(maxPerRun, ChronoUnit.HOURS.between(first.start, first.end))
        assertTrue(first.end.isBefore(head), "one run must not span the whole gap")

        // Every run while behind advances the watermark, so the backlog is
        // actually worked off rather than the same window being rebuilt forever.
        var previous = first
        var runs = 1
        while (previous.end.isBefore(head) && runs < 50) {
            val next = assertNotNull(windowFor(watermark = previous.watermark))
            assertTrue(next.watermark > previous.watermark, "watermark must advance while behind")
            previous = next
            runs++
        }
        assertEquals(head, previous.end, "the backlog should be caught up in a bounded number of runs")
        assertTrue(runs <= 12, "200 hours at 21 per run should take about ten runs, took $runs")

        // Once caught up the watermark stops moving — the trailing re-check is
        // the fixed point, not a source of endless drift.
        val steady = assertNotNull(windowFor(watermark = previous.watermark))
        assertEquals(previous.watermark, steady.watermark)
    }

    @Test
    fun `a run may never advance by less than it re-checks`() {
        // maxBucketsPerRun <= trailingBuckets would make every run rebuild the
        // same trailing window and never move the watermark: a backlog that can
        // never be worked off. Refused at the boundary rather than discovered in
        // production.
        assertThrows<IllegalArgumentException> { windowFor(watermark = head.minus(50, hour), maxRun = trailing) }
        assertThrows<IllegalArgumentException> { windowFor(watermark = null, maxRun = trailing - 1) }
    }

    // ---- never rebuild what the raw rows no longer support ----

    @Test
    fun `a rebuild never reaches past the raw retention window`() {
        val ancient = head.minus(10_000, hour)
        val p = assertNotNull(windowFor(watermark = ancient, backlogBuckets = 48))
        assertEquals(head.minus(48, hour), p.start)
    }

    @Test
    fun `retention in days converts to buckets of the right size`() {
        assertEquals(90L * 24, AggregationWindow.backlogBuckets(90, ChronoUnit.HOURS))
        assertEquals(90L, AggregationWindow.backlogBuckets(90, ChronoUnit.DAYS))
    }

    @Test
    fun `keeping results forever still caps how far back a rebuild reaches`() {
        // "Forever" is not a window a single query should scan.
        assertEquals(
            AggregationWindow.UNBOUNDED_RETENTION_DAYS * 24,
            AggregationWindow.backlogBuckets(0, ChronoUnit.HOURS),
        )
        assertEquals(
            AggregationWindow.UNBOUNDED_RETENTION_DAYS,
            AggregationWindow.backlogBuckets(-1, ChronoUnit.DAYS),
        )
    }

    // ---- nothing to do ----

    @Test
    fun `there is no window when the clock has not crossed a bucket`() {
        // Watermark at the head and no trailing re-check to reopen: nothing is
        // both closed and unbuilt.
        val p = AggregationWindow.nextWindow(
            now = now,
            unit = hour,
            lastWatermark = head,
            trailingBuckets = 0,
            maxBucketsPerRun = 24,
            maxBacklogBuckets = backlog,
        )
        assertNull(p)
    }

    // ---- the watermark itself ----

    @Test
    fun `the watermark stays behind the window end by the trailing re-check`() {
        val p = assertNotNull(windowFor(watermark = head.minus(8, hour)))
        assertEquals(p.end.minus(trailing, hour), p.watermark)
    }

    @Test
    fun `the watermark never runs ahead of the window it describes`() {
        // A run narrower than the trailing re-check (a backlog ceiling clamping
        // the window) must not record a watermark inside ground it did not cover.
        val p = assertNotNull(windowFor(watermark = head.minus(400, hour), maxRun = 4, backlogBuckets = 400))
        assertTrue(p.watermark >= p.start)
        assertTrue(p.watermark <= p.end)
    }

    // ---- daily buckets behave the same way ----

    @Test
    fun `daily buckets follow the same rules`() {
        val p = assertNotNull(
            AggregationWindow.nextWindow(
                now = now,
                unit = ChronoUnit.DAYS,
                lastWatermark = null,
                trailingBuckets = 3,
                maxBucketsPerRun = 14,
                maxBacklogBuckets = 90,
            )
        )
        assertEquals(now.truncatedTo(ChronoUnit.DAYS), p.end)
        assertEquals(3L, ChronoUnit.DAYS.between(p.start, p.end))
    }
}
