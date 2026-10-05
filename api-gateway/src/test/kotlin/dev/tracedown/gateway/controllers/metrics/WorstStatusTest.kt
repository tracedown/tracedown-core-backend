package dev.tracedown.gateway.controllers.metrics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * The status a minute of the recent-probes strip is painted with when several
 * runs fall into it.
 *
 * The defect this covers: a run that did not evaluate (`error` — an agent
 * that went silent, an executor that threw) was not one of the statuses the
 * merge knew, so it fell through to `success` and the minute showed green.
 * A minute of shed ticks (`skipped`, reachable through the database fallback)
 * fell through the same way.
 */
class WorstStatusTest {

    @Test
    fun `a minute with only error runs is an error minute, not a green one`() {
        assertEquals("error", DashboardMetricsController.worstStatus(listOf("error")))
        assertEquals("error", DashboardMetricsController.worstStatus(listOf("success", "error", "success")))
    }

    @Test
    fun `error is painted like failure, so it outranks timeout`() {
        // Every surface paints error failure-red and timeout yellow; a minute
        // must never look milder for having one more run in it.
        assertEquals("error", DashboardMetricsController.worstStatus(listOf("timeout", "error")))
        assertEquals("failure", DashboardMetricsController.worstStatus(listOf("error", "failure")))
        assertEquals("timeout", DashboardMetricsController.worstStatus(listOf("timeout", "success")))
    }

    @Test
    fun `a minute in which nothing ran stays skipped`() {
        assertEquals("skipped", DashboardMetricsController.worstStatus(listOf("skipped", "skipped")))
        assertEquals("success", DashboardMetricsController.worstStatus(listOf("skipped", "success")))
    }

    @Test
    fun `all green stays green`() {
        assertEquals("success", DashboardMetricsController.worstStatus(listOf("success", "success")))
    }
}
