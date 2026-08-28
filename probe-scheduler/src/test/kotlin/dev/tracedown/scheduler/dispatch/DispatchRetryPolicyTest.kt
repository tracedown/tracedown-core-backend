package dev.tracedown.scheduler.dispatch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The retry decision: which dispatch failures may be re-run on another agent,
 * which may not, and what bounds the attempt.
 *
 * The defect these cover: when every dispatch for a tick failed, the results
 * were dropped on the floor — no history row, no status change, nothing the
 * customer could see. An agent killed between health rounds took every service
 * pinned to it silent. Re-running such a tick elsewhere is the fix, but only
 * where it is provably safe: a run the agent had already started must never be
 * replayed against a live target.
 */
class DispatchRetryPolicyTest {

    private val plentyOfBudget = 0L

    // ---- the taxonomy ----

    @Test
    fun `a failure that never reached the agent is retryable`() {
        // Connection refused, DNS, TLS handshake, revoked cert, connect timeout.
        assertTrue(AgentFailure.UNREACHABLE.retryable)
    }

    @Test
    fun `a job refused before it ran is retryable`() {
        assertTrue(AgentFailure.REJECTED.retryable)
    }

    @Test
    fun `a run the agent had already started is never retried`() {
        // The agent took the job and broke partway. It may already have called
        // the monitored target, and a script that posts or deletes would be
        // replayed against a live system.
        assertFalse(AgentFailure.EXECUTION_FAILED.retryable)
        assertFalse(AgentFailure.MALFORMED_RESULT.retryable)
    }

    @Test
    fun `only HTTP 500 is treated as a failure inside the agent`() {
        assertEquals(AgentFailure.EXECUTION_FAILED, AgentFailure.ofHttpStatus(500))
        // Every other non-2xx is a refusal at the boundary: the script never
        // started, so another agent may take the run.
        for (status in listOf(400, 401, 403, 404, 405, 429, 502, 503, 504)) {
            val failure = AgentFailure.ofHttpStatus(status)
            assertEquals(AgentFailure.REJECTED, failure, "HTTP $status should be a refusal")
            assertTrue(failure!!.retryable, "HTTP $status should be retryable")
        }
    }

    @Test
    fun `a successful answer is not a failure at all`() {
        for (status in listOf(200, 201, 204, 299)) {
            assertEquals(null, AgentFailure.ofHttpStatus(status), "HTTP $status carries a result")
        }
    }

    @Test
    fun `every failure reason is distinct and free of spaces`() {
        // The reason is recorded verbatim on a skipped result row and switched
        // on by the ingestor to pick an alert type.
        val reasons = AgentFailure.entries.map { it.reason }
        assertEquals(reasons.size, reasons.toSet().size, "reasons must be distinct")
        assertTrue(reasons.all { it.isNotBlank() && !it.contains(' ') })
    }

    // ---- the gate ----

    @Test
    fun `a target-level outcome never reaches the retry gate as retryable`() {
        // Belt and braces: even asked directly, a failure the agent had begun
        // executing is refused a retry however much budget remains.
        assertFalse(
            DispatchRetryPolicy.mayRetry(AgentFailure.EXECUTION_FAILED, attemptsMade = 1, elapsedMs = plentyOfBudget, hasUntriedAgent = true),
        )
        assertFalse(
            DispatchRetryPolicy.mayRetry(AgentFailure.MALFORMED_RESULT, attemptsMade = 1, elapsedMs = plentyOfBudget, hasUntriedAgent = true),
        )
    }

    @Test
    fun `an unreachable agent is retried when another one is free`() {
        assertTrue(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = 1, elapsedMs = plentyOfBudget, hasUntriedAgent = true),
        )
    }

    @Test
    fun `there is no retry when every eligible agent has been tried`() {
        // The `simultaneous` probe mode is exactly this case: it already
        // dispatches to the whole eligible set, so no fallback exists.
        assertFalse(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = 1, elapsedMs = plentyOfBudget, hasUntriedAgent = false),
        )
    }

    @Test
    fun `attempts are capped`() {
        val cap = QueuePolicyManager.MAX_DISPATCH_ATTEMPTS
        assertTrue(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = cap - 1, elapsedMs = plentyOfBudget, hasUntriedAgent = true),
            "the last allowed attempt must still be reachable",
        )
        assertFalse(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = cap, elapsedMs = plentyOfBudget, hasUntriedAgent = true),
            "a fleet of dead agents must not be walked end to end",
        )
    }

    @Test
    fun `the retry window closes even with attempts to spare`() {
        val window = QueuePolicyManager.RETRY_WINDOW_MS
        assertTrue(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = 1, elapsedMs = window - 1, hasUntriedAgent = true),
        )
        assertFalse(
            DispatchRetryPolicy.mayRetry(AgentFailure.UNREACHABLE, attemptsMade = 1, elapsedMs = window, hasUntriedAgent = true),
        )
    }

    // ---- exhaustion ----

    @Test
    fun `a run that exhausts every agent stops rather than looping`() {
        // Three agents, all refusing instantly. The fourth decision must be no.
        var attempts = 0
        val agents = 3
        var decisions = 0
        while (DispatchRetryPolicy.mayRetry(
                AgentFailure.UNREACHABLE,
                attemptsMade = ++attempts,
                elapsedMs = attempts * 10L,
                hasUntriedAgent = attempts < agents,
            )
        ) {
            decisions++
            check(decisions < 100) { "retry decision must terminate" }
        }
        assertEquals(QueuePolicyManager.MAX_DISPATCH_ATTEMPTS - 1, decisions)
    }

    // ---- the lock budget ----

    @Test
    fun `the worst-case retry chain still fits inside the execution lock`() {
        // If it did not, a retrying run would outlive its own probe_active
        // lock and the next tick could dispatch the same service concurrently
        // — a double probe and double usage attribution.
        for (timeoutMs in listOf(1_000, 5_000, 30_000, 60_000, 300_000)) {
            val lockMs = QueuePolicyManager.lockTtlSeconds(timeoutMs) * 1000
            val worstCase = DispatchRetryPolicy.worstCaseRunMs(timeoutMs)
            assertTrue(
                lockMs >= worstCase,
                "lock TTL ${lockMs}ms must cover the ${worstCase}ms worst case at timeout=${timeoutMs}ms",
            )
        }
    }

    @Test
    fun `the lock keeps a safety margin beyond the worst case`() {
        val timeoutMs = 30_000
        val lockMs = QueuePolicyManager.lockTtlSeconds(timeoutMs) * 1000
        assertTrue(
            lockMs - DispatchRetryPolicy.worstCaseRunMs(timeoutMs) >= QueuePolicyManager.SAFETY_MARGIN_MS,
        )
    }

    @Test
    fun `a run with no retries is unaffected by the widened lock beyond its margin`() {
        // The window is reserved, not spent: a single-attempt run finishes in
        // timeout + overhead as it always did.
        val timeoutMs = 30_000
        val singleAttemptMs = timeoutMs + QueuePolicyManager.DISPATCH_OVERHEAD_MS
        assertTrue(QueuePolicyManager.lockTtlSeconds(timeoutMs) * 1000 > singleAttemptMs)
    }
}
