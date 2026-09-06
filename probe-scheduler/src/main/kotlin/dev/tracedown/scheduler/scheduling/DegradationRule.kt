package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.alerts.SystemAlertService

/**
 * When a passing health challenge counts as *degraded*.
 *
 * The round trip is a cold mTLS handshake plus the agent's callback to the
 * gateway, so its floor is set by geography: an agent on another continent
 * sits near a second every round, an adjacent one near 40 ms. A fixed ceiling
 * therefore leaves the far agent a couple of hundred milliseconds of headroom
 * and flags ordinary jitter, while the near one could triple and never be
 * noticed. So the ceiling is the larger of the fixed floor and twice the
 * agent's own recent median — and, like the down verdict, it takes two
 * consecutive slow rounds: one slow handshake is weather, two is a condition.
 */
object DegradationRule {

    /** Passing rounds the baseline is taken over. */
    const val BASELINE_ROUNDS = 30

    /** Fewer samples than this and the fixed floor stands alone. */
    const val MIN_BASELINE_SAMPLES = 5

    /** The round trip above which [baselineMs] makes a round slow. */
    fun thresholdMs(baselineMs: Int?): Int =
        maxOf(SystemAlertService.DEGRADED_RTT_MS, (baselineMs ?: 0) * 2)

    /**
     * Whether this round, at [roundTripMs], following a passing round at
     * [priorRoundTripMs] (null when there was none), is degraded against an
     * agent whose recent passing rounds have median [baselineMs] (null when
     * there are too few to say).
     */
    fun isDegraded(roundTripMs: Int, priorRoundTripMs: Int?, baselineMs: Int?): Boolean {
        val threshold = thresholdMs(baselineMs)
        return roundTripMs > threshold && priorRoundTripMs != null && priorRoundTripMs > threshold
    }

    /** Median of [recentPassMs], or null below [MIN_BASELINE_SAMPLES]. */
    fun baseline(recentPassMs: List<Int>): Int? {
        if (recentPassMs.size < MIN_BASELINE_SAMPLES) return null
        val sorted = recentPassMs.sorted()
        return sorted[sorted.size / 2]
    }
}
