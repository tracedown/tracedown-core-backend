package dev.tracedown.common.runs

/**
 * How long one run can take, end to end — shared by the scheduler, whose
 * execution lock has to outlive the run it protects, and the gateway, whose
 * run handle must not call a run lost while it may still be under way.
 */
object RunBounds {

    /** The per-agent client overhead over the probe timeout. */
    const val DISPATCH_OVERHEAD_MS = 15_000L

    /**
     * Wall clock reserved for re-dispatching a run after an agent-level
     * failure: another attempt may only start inside it, so the worst case is
     * one full attempt begun at its edge (`RETRY_WINDOW_MS + timeout +
     * DISPATCH_OVERHEAD_MS`).
     */
    const val RETRY_WINDOW_MS = 20_000L

    /** Extra headroom so the lock never lapses mid-dispatch. */
    const val SAFETY_MARGIN_MS = 15_000L

    /**
     * The scheduler's execution-lock TTL for a probe timeout of [timeoutMs],
     * in whole seconds: the longest a run can legitimately take, retries
     * included, plus the margin.
     */
    fun lockTtlSeconds(timeoutMs: Int): Long {
        val ceilingMs = timeoutMs.toLong() + DISPATCH_OVERHEAD_MS + RETRY_WINDOW_MS + SAFETY_MARGIN_MS
        return (ceilingMs + 999) / 1000 // ceil to whole seconds
    }

    /** The least a run handle waits before it reads `expired`. */
    const val MIN_EXPIRY_SECONDS = 600L

    /** Added to twice the lock: the trip through the queue and the ingestor. */
    private const val EXPIRY_MARGIN_SECONDS = 60L

    /**
     * How long a run asked for may go without a result before its handle
     * reads `expired`, for a probe timeout of [timeoutMs]: twice the
     * execution lock (a run may wait behind the one holding it, then run
     * itself) plus a margin for the queues, and never under
     * [MIN_EXPIRY_SECONDS].
     */
    fun runExpirySeconds(timeoutMs: Int): Long =
        maxOf(MIN_EXPIRY_SECONDS, 2 * lockTtlSeconds(timeoutMs) + EXPIRY_MARGIN_SECONDS)
}
