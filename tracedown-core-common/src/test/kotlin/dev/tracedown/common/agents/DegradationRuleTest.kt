package dev.tracedown.common.agents

import dev.tracedown.common.alerts.SystemAlertService.DEGRADED_RTT_MS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The degraded verdict is relative to the agent's own baseline and needs two
 * slow rounds. The defect these cover: an agent whose every round sits near
 * 920 ms because of geography was flagged degraded by ordinary jitter — a
 * single 1.3 s round — while the fixed 1.2 s ceiling gave it 280 ms of room.
 */
class DegradationRuleTest {

    private val farBaseline = List(30) { 920 }
    private val nearBaseline = List(30) { 40 }

    @Test
    fun `the ceiling is the fixed floor for a fast agent and twice the median for a far one`() {
        assertEquals(DEGRADED_RTT_MS, DegradationRule.thresholdMs(DegradationRule.baseline(nearBaseline)))
        assertEquals(1840, DegradationRule.thresholdMs(DegradationRule.baseline(farBaseline)))
        assertEquals(DEGRADED_RTT_MS, DegradationRule.thresholdMs(null), "no history: the floor stands alone")
    }

    @Test
    fun `jitter on a far agent is not degradation`() {
        val baseline = DegradationRule.baseline(farBaseline)
        assertFalse(DegradationRule.isDegraded(1300, priorRoundTripMs = 925, baselineMs = baseline))
        assertFalse(DegradationRule.isDegraded(1468, priorRoundTripMs = 1300, baselineMs = baseline), "both under 2x")
    }

    @Test
    fun `two consecutive rounds above the ceiling are`() {
        val baseline = DegradationRule.baseline(farBaseline)
        assertFalse(DegradationRule.isDegraded(3264, priorRoundTripMs = 925, baselineMs = baseline), "first slow round holds")
        assertTrue(DegradationRule.isDegraded(3100, priorRoundTripMs = 3264, baselineMs = baseline))
        val near = DegradationRule.baseline(nearBaseline)
        assertTrue(DegradationRule.isDegraded(1300, priorRoundTripMs = 1250, baselineMs = near), "a fast agent still answers to the floor")
    }

    @Test
    fun `a first round has no prior and never convicts`() {
        assertFalse(DegradationRule.isDegraded(5000, priorRoundTripMs = null, baselineMs = null))
    }

    @Test
    fun `the baseline needs enough samples and is the median`() {
        assertNull(DegradationRule.baseline(listOf(40, 41, 39, 5000)))
        assertEquals(44, DegradationRule.baseline(listOf(40, 41, 45, 5000, 44)))
    }

    // --- verdict(): the same rule read back off a list of rounds, latest first.

    @Test
    fun `too few rounds to say leaves the fixed floor standing alone`() {
        val verdict = DegradationRule.verdict(listOf(1800, 900, 900, 900))
        assertNull(verdict.baselineMs, "three earlier rounds is under the minimum sample")
        assertEquals(DEGRADED_RTT_MS, verdict.thresholdMs)
        assertFalse(verdict.degraded, "the round before it is under the floor")

        // And while the floor stands alone it is the whole rule, so an agent
        // that has not yet earned a baseline is judged by it — both rounds over.
        assertTrue(DegradationRule.verdict(listOf(1800, 1500, 1500)).degraded)
    }

    @Test
    fun `an agent with no rounds at all is not judged`() {
        assertEquals(DegradationRule.UNKNOWN, DegradationRule.verdict(emptyList()))
    }

    @Test
    fun `a far agent running at its usual pace is not degraded`() {
        val verdict = DegradationRule.verdict(listOf(1800) + List(30) { 1500 })
        assertEquals(1500, verdict.baselineMs)
        assertEquals(3000, verdict.thresholdMs)
        assertFalse(verdict.degraded, "1.8 s is ordinary for an agent whose median is 1.5 s")
    }

    @Test
    fun `one slow round on a far agent is weather, two is a condition`() {
        val history = List(30) { 1500 }
        assertFalse(DegradationRule.verdict(listOf(3200, 1500) + history).degraded, "the first slow round holds")
        assertTrue(DegradationRule.verdict(listOf(3100, 3200) + history).degraded)
    }

    @Test
    fun `the baseline excludes the round being judged`() {
        // Six rounds, five of them 100 ms: taking the median over all six would
        // let the 5 s round drag the sample it is measured against.
        val verdict = DegradationRule.verdict(listOf(5000, 100, 100, 100, 100, 100))
        assertEquals(100, verdict.baselineMs)
    }

    @Test
    fun `only the rounds the baseline covers count towards it`() {
        // A long tail of slow rounds beyond BASELINE_ROUNDS must not move the
        // median the way it would if the whole list were used.
        val rounds = listOf(1000) + List(DegradationRule.BASELINE_ROUNDS) { 100 } + List(200) { 9000 }
        assertEquals(100, DegradationRule.verdict(rounds).baselineMs)
    }
}
