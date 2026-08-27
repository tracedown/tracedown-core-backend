package dev.tracedown.scheduler.dispatch

/**
 * The rule for re-dispatching a probe run after an agent-level failure.
 *
 * Kept as a pure function on purpose: every clause is a safety property that
 * should be readable and testable without a scheduler, a database or a network.
 *
 * - **Only agent-level, only pre-execution.** [AgentFailure.retryable] is the
 *   gate. A run that produced any ProbeResult never reaches here, and neither
 *   does one the agent had already begun executing — retrying either would
 *   re-issue requests the monitored target has already served.
 * - **Only somewhere new.** Re-running on an agent that just failed learns
 *   nothing; the caller hands untried agents only, drawn from the same
 *   eligibility rule the first pick used.
 * - **Bounded by count**, so a large dead fleet is not walked end to end on
 *   every tick.
 * - **Bounded by the clock**, against the window
 *   [QueuePolicyManager.RETRY_WINDOW_MS] reserves inside the execution lock's
 *   TTL. Only the *start* of an attempt is gated: an attempt allowed at the
 *   very edge of the window still finishes inside the lock, because the TTL
 *   carries a whole further dispatch ceiling beyond the window.
 */
object DispatchRetryPolicy {

    /**
     * @param failure why the last attempt produced no result
     * @param attemptsMade attempts already made for this run, the first included
     * @param elapsedMs wall-clock milliseconds since the run began dispatching
     * @param hasUntriedAgent whether an eligible agent remains that this run has
     *   not already been sent to
     */
    fun mayRetry(
        failure: AgentFailure,
        attemptsMade: Int,
        elapsedMs: Long,
        hasUntriedAgent: Boolean,
    ): Boolean =
        failure.retryable &&
            hasUntriedAgent &&
            attemptsMade < QueuePolicyManager.MAX_DISPATCH_ATTEMPTS &&
            elapsedMs < QueuePolicyManager.RETRY_WINDOW_MS

    /**
     * The longest a run can legitimately occupy its execution lock: the last
     * attempt may start at the very end of the retry window and then take a
     * full dispatch ceiling of its own.
     *
     * [QueuePolicyManager.lockTtlSeconds] must cover this, or a retrying run
     * would outlive its lock and the next tick could dispatch the same service
     * concurrently. Asserted in the tests rather than assumed.
     */
    fun worstCaseRunMs(timeoutMs: Int): Long =
        QueuePolicyManager.RETRY_WINDOW_MS + timeoutMs.toLong() + QueuePolicyManager.DISPATCH_OVERHEAD_MS
}
