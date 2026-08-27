package dev.tracedown.scheduler.scheduling

import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.FAILURE_THRESHOLD
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.RESULT_INCONCLUSIVE
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.RESULT_PASS
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.nextStatus
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.tokenEndpointSubject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Hysteresis on the agent health verdict: fail slow, recover fast.
 *
 * The defect these cover: a health challenge that fails for a reason the agent
 * does not control (the gateway it has to fetch its token from is restarting)
 * used to mark every agent `failure` in a single round, which emptied the
 * eligible-agent set and silently stopped all probing.
 */
class HealthChallengeHysteresisTest {

    @Test
    fun `a single non-pass round does not convict an agent that was passing`() {
        assertNull(nextStatus("fail", RESULT_PASS), "first failure after a pass must hold the current status")
        assertNull(nextStatus("timeout", RESULT_PASS))
        assertNull(nextStatus("wrong_token", RESULT_PASS))
    }

    @Test
    fun `two consecutive non-pass rounds convict`() {
        assertEquals("failure", nextStatus("fail", "fail"))
        assertEquals("failure", nextStatus("timeout", "fail"))
        assertEquals("failure", nextStatus("fail", "timeout"))
        assertEquals("failure", nextStatus("fail", "wrong_token"))
    }

    @Test
    fun `the threshold this implements is two`() {
        assertEquals(2, FAILURE_THRESHOLD)
    }

    @Test
    fun `recovery takes effect on the first pass`() {
        assertEquals("success", nextStatus(RESULT_PASS, "fail"))
        assertEquals("success", nextStatus(RESULT_PASS, "timeout"))
        assertEquals("success", nextStatus(RESULT_PASS, RESULT_PASS))
        assertEquals("success", nextStatus(RESULT_PASS, null))
    }

    @Test
    fun `an agent with no prior round is not convicted on its first failure`() {
        assertNull(nextStatus("fail", null))
    }

    @Test
    fun `an inconclusive prior round is never the prior result`() {
        // recordResult filters inconclusive rows out of the lookup, so the
        // value can never reach nextStatus. Asserted here so that the filter
        // and this contract stay described in one place: were an inconclusive
        // row ever passed in, it would read as evidence of failure.
        assertEquals("failure", nextStatus("fail", RESULT_INCONCLUSIVE))
    }

    @Test
    fun `a whole fleet stays eligible through a one-round platform blip`() {
        // Every agent was passing; the gateway blips for exactly one round.
        val fleet = List(5) { RESULT_PASS }
        val afterBlip = fleet.map { prior -> nextStatus("fail", prior) }
        assertTrue(afterBlip.all { it == null }, "no agent may be marked failure by one bad round")
    }

    @Test
    fun `the token endpoint alert subject omits the per-challenge id`() {
        // Otherwise every minute opens a new alert episode instead of
        // refreshing the banner that is already showing.
        val subject = tokenEndpointSubject("http://api-gateway.railway.internal:8080")
        assertEquals("http://api-gateway.railway.internal:8080/internal/health/token", subject)
        assertTrue(subject.length <= 128, "subject must fit system_alerts.subject VARCHAR(128)")
    }

    @Test
    fun `an over-long gateway url is truncated to the column width`() {
        val subject = tokenEndpointSubject("https://" + "a".repeat(200) + ".example.com")
        assertEquals(128, subject.length)
    }
}
