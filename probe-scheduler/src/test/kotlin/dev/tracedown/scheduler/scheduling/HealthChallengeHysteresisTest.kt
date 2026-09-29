package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.agents.DegradationRule
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.AgentInfo
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.CHALLENGE_TIMEOUT_MAX_MS
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.CHALLENGE_TIMEOUT_MS
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.FAILURE_THRESHOLD
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.challengeBudgetMs
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.decide
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.isPlatformRound
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.RoundOutcome
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.RESULT_INCONCLUSIVE
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.RESULT_PASS
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.nextStatus
import dev.tracedown.scheduler.scheduling.HealthChallengeJob.Companion.tokenEndpointSubject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

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

    // ── Evidence from dispatch ──────────────────────────────────────────────

    @Test
    fun `two failed challenges do not convict an agent that answered a run in between`() {
        // The Singapore case: the challenge path times out twice, the agent's
        // dispatched probes came back in the same window.
        assertNull(nextStatus("timeout", "timeout", provenAlive = true))
        assertNull(nextStatus("fail", "timeout", provenAlive = true))
    }

    @Test
    fun `evidence of life never blocks a conviction that would not have happened anyway`() {
        assertNull(nextStatus("fail", RESULT_PASS, provenAlive = true))
        assertNull(nextStatus("fail", null, provenAlive = true))
    }

    @Test
    fun `evidence of life does not change a pass`() {
        assertEquals("success", nextStatus(RESULT_PASS, "fail", provenAlive = true))
    }

    @Test
    fun `without evidence the second failure still convicts`() {
        assertEquals("failure", nextStatus("timeout", "timeout", provenAlive = false))
    }

    // ── Challenge budget ────────────────────────────────────────────────────

    @Test
    fun `an agent with no baseline gets the floor`() {
        assertEquals(CHALLENGE_TIMEOUT_MS, challengeBudgetMs(null))
    }

    @Test
    fun `a near agent gets the floor too`() {
        // 200 ms median × 10 is well under ten seconds.
        assertEquals(CHALLENGE_TIMEOUT_MS, challengeBudgetMs(200))
    }

    @Test
    fun `a far agent gets ten of its own medians`() {
        // 1.5 s median — Singapore from Frankfurt — used to get under seven.
        assertEquals(15_000L, challengeBudgetMs(1500))
    }

    @Test
    fun `the budget never outlives the token`() {
        assertEquals(CHALLENGE_TIMEOUT_MAX_MS, challengeBudgetMs(5000))
        assertTrue(CHALLENGE_TIMEOUT_MAX_MS < 30_000L, "the token lives 30 s; the budget must end first")
    }

    // ── Platform round ──────────────────────────────────────────────────────

    @Test
    fun `most of the fleet slow at once is a platform round`() {
        assertTrue(isPlatformRound(judged = 7, slow = 7))
        assertTrue(isPlatformRound(judged = 9, slow = 5))
        assertTrue(isPlatformRound(judged = 2, slow = 2))
    }

    @Test
    fun `a few slow agents are their own problem`() {
        assertFalse(isPlatformRound(judged = 9, slow = 4))
        assertFalse(isPlatformRound(judged = 9, slow = 1))
        assertFalse(isPlatformRound(judged = 3, slow = 1))
    }

    @Test
    fun `a lone agent can never be a platform round`() {
        // With one agent there is nothing to compare against.
        assertFalse(isPlatformRound(judged = 1, slow = 1))
        assertFalse(isPlatformRound(judged = 0, slow = 0))
    }

    // ── Alert decisions ─────────────────────────────────────────────────────

    private val fine = DegradationRule.Verdict(baselineMs = 800, thresholdMs = 1600, degraded = false)
    private val slow = DegradationRule.Verdict(baselineMs = 800, thresholdMs = 1600, degraded = true)

    private fun outcome(
        slug: String = "agent-1",
        result: String = RESULT_PASS,
        roundTripMs: Int = 700,
        agentElapsedMs: Int? = 300,
        statusBefore: String? = "success",
        statusAfter: String = "success",
        verdict: DegradationRule.Verdict = fine,
        priorVerdict: DegradationRule.Verdict = fine,
    ) = RoundOutcome(
        agent = AgentInfo(1, slug, "https://$slug:8443"),
        challengedAt = Instant.parse("2026-09-29T10:00:30Z"),
        result = result,
        roundTripMs = roundTripMs,
        agentElapsedMs = agentElapsedMs,
        statusBefore = statusBefore,
        statusAfter = statusAfter,
        verdict = verdict,
        priorVerdict = priorVerdict,
    )

    @Test
    fun `a healthy round raises nothing`() {
        assertTrue(decide(listOf(outcome()), platformRound = false).isEmpty())
    }

    @Test
    fun `a conviction is raised once as new and then as recurring`() {
        val first = decide(listOf(outcome(result = "timeout", statusBefore = "success", statusAfter = "failure")), false).single()
        assertEquals(SystemAlertService.AGENT_DOWN, first.alertType)
        assertFalse(first.recurring, "the round that convicts opens the episode")
        assertTrue(first.broadcast)

        val next = decide(listOf(outcome(result = "timeout", statusBefore = "failure", statusAfter = "failure")), false).single()
        assertEquals(SystemAlertService.AGENT_DOWN, next.alertType)
        assertTrue(next.recurring, "every later round of the same outage is the same episode")
    }

    @Test
    fun `recovery from down is raised once and only to the router`() {
        val raises = decide(listOf(outcome(statusBefore = "failure", statusAfter = "success")), false)
        val recovered = raises.single()
        assertEquals(SystemAlertService.AGENT_RECOVERED, recovered.alertType)
        assertEquals("down", recovered.data["from"]?.jsonPrimitive?.content)
        assertFalse(recovered.broadcast, "recovery is not a banner")
    }

    @Test
    fun `degraded is raised as new on the transition and recurring after`() {
        val first = decide(listOf(outcome(roundTripMs = 2000, verdict = slow, priorVerdict = fine)), false).single()
        assertEquals(SystemAlertService.AGENT_DEGRADED, first.alertType)
        assertFalse(first.recurring)
        assertEquals(2000, first.data["roundTripMs"]?.jsonPrimitive?.content?.toInt())
        // Attribution: the agent's own leg and the path to it are both named.
        assertEquals(300, first.data["agentMs"]?.jsonPrimitive?.content?.toInt())
        assertEquals(1700, first.data["transportMs"]?.jsonPrimitive?.content?.toInt())

        val next = decide(listOf(outcome(roundTripMs = 2100, verdict = slow, priorVerdict = slow)), false).single()
        assertTrue(next.recurring)
    }

    @Test
    fun `recovery from degraded is raised once when the verdict clears`() {
        val recovered = decide(listOf(outcome(verdict = fine, priorVerdict = slow)), false).single()
        assertEquals(SystemAlertService.AGENT_RECOVERED, recovered.alertType)
        assertEquals("degraded", recovered.data["from"]?.jsonPrimitive?.content)
        assertFalse(recovered.broadcast)
    }

    @Test
    fun `in a platform round no agent is called degraded`() {
        val outcomes = listOf(
            outcome(slug = "eu-1", roundTripMs = 2300, verdict = slow),
            outcome(slug = "us-1", roundTripMs = 2400, verdict = slow),
        )
        assertTrue(decide(outcomes, platformRound = true).isEmpty(), "the round is the subject, not the agents")
        assertEquals(2, decide(outcomes, platformRound = false).size)
    }

    @Test
    fun `a platform round still reports an agent that is down`() {
        val raises = decide(listOf(outcome(result = "timeout", statusBefore = "failure", statusAfter = "failure")), true)
        assertEquals(SystemAlertService.AGENT_DOWN, raises.single().alertType)
    }

    @Test
    fun `a non-passing round is never judged for slowness`() {
        // The carried-over verdict says degraded; the round did not pass, so
        // it was alerted on (or not) when it happened.
        val raises = decide(listOf(outcome(result = "fail", verdict = slow, priorVerdict = slow)), false)
        assertTrue(raises.isEmpty())
    }

    @Test
    fun `transport is the round trip less the agent's own time, never negative`() {
        assertEquals(1700, outcome(roundTripMs = 2000, agentElapsedMs = 300).transportMs)
        assertEquals(0, outcome(roundTripMs = 200, agentElapsedMs = 250).transportMs)
        assertNull(outcome(agentElapsedMs = null).transportMs, "an older agent reports no time of its own")
    }
}
