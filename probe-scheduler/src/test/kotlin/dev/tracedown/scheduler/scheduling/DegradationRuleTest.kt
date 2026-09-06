package dev.tracedown.scheduler.scheduling

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
}
