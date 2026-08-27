package dev.tracedown.scheduler.dispatch

/**
 * Why a dispatch produced no ProbeResult of its own, and whether the run may be
 * re-dispatched to another agent.
 *
 * The distinction this type exists to draw:
 *
 * - An **agent-level** failure is one where the probe did not run against the
 *   monitored target, or was refused before it could. It says nothing about the
 *   target's health, so the run is worth attempting somewhere else.
 * - A **target-level** result is one the probe actually produced — success,
 *   failure, timeout, an assertion that did not hold. It is never represented
 *   here: it comes back as a ProbeResult and is persisted as observed. Retrying
 *   one would fabricate uptime.
 *
 * [retryable] is deliberately narrower than "agent-level". It is true only
 * where the agent provably never began executing the script, so re-running the
 * run elsewhere cannot repeat a request the monitored target has already seen.
 * A script that posts, deletes or otherwise mutates would be replayed against a
 * live system otherwise.
 */
enum class AgentFailure(
    /** Whether the same run may be dispatched to another eligible agent. */
    val retryable: Boolean,
    /** Reason string recorded on a `skipped` result when every agent failed this way. */
    val reason: String,
) {

    /**
     * No usable connection to the agent: connection refused, DNS failure, TLS
     * handshake failure, a certificate this scheduler has revoked or no longer
     * trusts, or a connect timeout. Nothing was delivered, so nothing ran.
     */
    UNREACHABLE(retryable = true, reason = "agent_unreachable"),

    /**
     * The agent (or something in front of it) answered by refusing the job
     * rather than running it — an authentication/authorization rejection, a
     * sealed payload it could not open, a path it does not serve, or a gateway
     * reporting it as unavailable. The script never started.
     */
    REJECTED(retryable = true, reason = "agent_rejected"),

    /**
     * The agent accepted the job and then failed while running it. It may
     * already have issued calls to the monitored target, so this is **not**
     * retried: a re-run could duplicate a request the target has seen. It is
     * recorded as an `error` result instead, carrying whatever the agent said.
     */
    EXECUTION_FAILED(retryable = false, reason = "agent_execution_failed"),

    /**
     * The agent answered successfully with something that is not a ProbeResult.
     * Same reasoning as [EXECUTION_FAILED]: it believed it ran, so the target
     * may already have been probed. Recorded, not retried.
     */
    MALFORMED_RESULT(retryable = false, reason = "agent_malformed_result"),
    ;

    companion object {

        /**
         * Classifies a non-2xx answer from an agent.
         *
         * 500 is the one status that means "I took the job and broke while
         * running it" — every other status is a refusal at the boundary, before
         * the script could start. That split is what makes retrying safe: a
         * refusal cannot have touched the monitored target.
         *
         * Pure, so the taxonomy is testable without a network.
         */
        fun ofHttpStatus(status: Int): AgentFailure? = when {
            status in 200..299 -> null
            status == 500 -> EXECUTION_FAILED
            else -> REJECTED
        }
    }
}
