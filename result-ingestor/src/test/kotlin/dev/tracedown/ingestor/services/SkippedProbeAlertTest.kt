package dev.tracedown.ingestor.services

import dev.tracedown.common.alerts.SystemAlertService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A skipped tick is only sometimes a fault, and the banner the org sees has to
 * name the right one. The costly mistake is the default: anything not spelled
 * out here becomes "reduce your probe frequency", which for a policy decision
 * sends the org to a setting that will not help.
 */
class SkippedProbeAlertTest {

    @Test
    fun `a target that asked not to be probed raises nothing`() {
        assertNull(SkippedProbeAlert.alertType("target_opted_out"))
    }

    @Test
    fun `a tick the unverified-domain rule withheld raises nothing`() {
        assertNull(SkippedProbeAlert.alertType("unverified_includes"))
        assertNull(SkippedProbeAlert.alertType("unverified_max_calls"))
        assertNull(SkippedProbeAlert.alertType("unverified_throttle"))
    }

    @Test
    fun `an empty fleet is a fleet problem, not a capacity one`() {
        assertEquals(SystemAlertService.NO_ELIGIBLE_AGENT, SkippedProbeAlert.alertType("no_eligible_agent"))
    }

    @Test
    fun `agents that were there and did not take the run are named as such`() {
        assertEquals(SystemAlertService.AGENT_DISPATCH_FAILED, SkippedProbeAlert.alertType("agent_unreachable"))
        assertEquals(SystemAlertService.AGENT_DISPATCH_FAILED, SkippedProbeAlert.alertType("agent_rejected"))
    }

    @Test
    fun `the scheduler's own faults are its own`() {
        assertEquals(SystemAlertService.SCHEDULER_ERROR, SkippedProbeAlert.alertType("dispatch_error"))
        assertEquals(SystemAlertService.SCHEDULER_ERROR, SkippedProbeAlert.alertType("trigger_misfired"))
    }

    @Test
    fun `a shed tick is the platform being over capacity`() {
        assertEquals(SystemAlertService.DISPATCH_CAPACITY, SkippedProbeAlert.alertType("dispatch_backlog"))
        assertEquals(SystemAlertService.DISPATCH_CAPACITY, SkippedProbeAlert.alertType("dispatch_queue_full"))
        assertEquals(SystemAlertService.DISPATCH_CAPACITY, SkippedProbeAlert.alertType("unknown"))
    }
}
