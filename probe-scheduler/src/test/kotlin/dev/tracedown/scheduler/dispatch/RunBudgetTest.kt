package dev.tracedown.scheduler.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The run budget dispatched to the agent as `requestTimeoutMs`.
 *
 * The defect this covers: the configured probe timeout never left the
 * scheduler. The scheduler sized its own HTTP timeout and its execution lock
 * from it, the agent knew nothing about it, and a slow script ran to its own
 * per-call timeouts while the scheduler gave up first — recording a synthetic
 * timeout for a run that was still going, on a service that may have been
 * perfectly healthy.
 *
 * With the budget dispatched, an over-budget run comes back as a real
 * `timeout` ProbeResult from the agent, inside the scheduler's own client
 * timeout. The agent's contract is a floor of 1s and a ceiling of 300s, both of
 * which it clamps to; sending inside that range keeps its clamp a no-op.
 */
class RunBudgetTest {

    @Test
    fun `the configured timeout is dispatched unchanged`() {
        assertEquals(30_000, AgentDispatchService.runBudgetMs(30_000))
        assertEquals(1_000, AgentDispatchService.runBudgetMs(1_000))
        assertEquals(300_000, AgentDispatchService.runBudgetMs(300_000))
    }

    @Test
    fun `a budget below the agent's floor is raised to it`() {
        // The agent would clamp it anyway; doing it here keeps both ends
        // agreeing on the number without a warning on every dispatch.
        assertEquals(AgentDispatchService.MIN_RUN_BUDGET_MS, AgentDispatchService.runBudgetMs(250))
    }

    @Test
    fun `a budget above the system ceiling is lowered to it`() {
        assertEquals(AgentDispatchService.MAX_RUN_BUDGET_MS, AgentDispatchService.runBudgetMs(600_000))
    }

    @Test
    fun `a non-positive timeout omits the budget instead of clamping up`() {
        // Clamping a misconfigured zero up to the floor would time every probe
        // out after a second. Omitting the field leaves the run to the script's
        // own per-call timeouts — how the agent behaved before this field, which
        // is the one way to fail safe.
        assertNull(AgentDispatchService.runBudgetMs(0))
        assertNull(AgentDispatchService.runBudgetMs(-1))
    }

    @Test
    fun `the agent answers before the scheduler gives up`() {
        // The whole point: the budget must expire inside the client timeout, or
        // the synthetic timeout stays the normal path for a slow script rather
        // than the fallback for an unresponsive agent.
        for (configured in listOf(1_000, 30_000, 300_000)) {
            val budget = AgentDispatchService.runBudgetMs(configured)!!
            val clientTimeoutMs = configured.toLong() + 15_000L
            assertTrue(
                budget < clientTimeoutMs,
                "a ${configured}ms probe would still be cut off by the client timeout",
            )
        }
    }
}
