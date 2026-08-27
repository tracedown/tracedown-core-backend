package dev.tracedown.worker.jobs

import java.time.Duration

/**
 * How far a retention pass may go in one tick.
 *
 * Retention used to read every expired row of every organization into the JVM
 * before deleting anything: one query materialised the whole expired set to
 * find the distinct organizations, a second materialised every expired id for
 * an organization, and those ids were then passed back as an `IN` list. The
 * first organization with a few hundred thousand retained results took the
 * worker out of memory — and because every worker job shares one process, that
 * took aggregation, purge and session cleanup down with it.
 *
 * The fix is to delete in bounded batches. These are the two bounds:
 *
 *  - [DEFAULT_BATCH_SIZE] caps how many ids exist in memory at once, so peak
 *    footprint is a function of the batch, never of the backlog.
 *  - [DEFAULT_TICK_BUDGET] caps how long one tick may spend deleting. A very
 *    large backlog (a first run after retention was shortened, say) is drained
 *    across successive ticks instead of monopolising the process — the other
 *    jobs in it still need to run.
 *
 * Neither bound can lose data: whatever is not deleted this tick is still
 * expired on the next one.
 */
object RetentionBatching {

    /**
     * Ids held in memory per delete round.
     *
     * Small enough that a round is a short transaction (long delete
     * transactions on `probe_results` block nothing else here, but they do hold
     * a pooled connection), large enough that the per-round overhead is
     * amortised.
     */
    const val DEFAULT_BATCH_SIZE = 500

    /** Wall-clock ceiling for one retention tick. Well under the 1-hour interval. */
    val DEFAULT_TICK_BUDGET: Duration = Duration.ofMinutes(10)

    /** What the pass should do after finishing a delete round. */
    enum class Verdict {
        /** The organization still has expired rows and there is budget left. */
        CONTINUE,

        /** The round came back short — this organization is drained. */
        ORG_DRAINED,

        /** The tick has run long enough; stop here and resume on the next one. */
        BUDGET_SPENT,
    }

    /**
     * Decides what to do after a delete round returned [rowsInRound] rows.
     *
     * A short round means the organization is drained, and that is reported
     * even when the budget is also gone: the caller then still checks
     * [budgetSpent] before starting the next organization, so a drained
     * organization is never re-scanned just to discover it is empty.
     */
    fun verdict(rowsInRound: Int, batchSize: Int, elapsed: Duration, budget: Duration): Verdict = when {
        rowsInRound < batchSize -> Verdict.ORG_DRAINED
        budgetSpent(elapsed, budget) -> Verdict.BUDGET_SPENT
        else -> Verdict.CONTINUE
    }

    /** Whether [elapsed] has reached [budget]. A non-positive budget means no ceiling. */
    fun budgetSpent(elapsed: Duration, budget: Duration): Boolean {
        if (budget.isZero || budget.isNegative) return false
        return elapsed >= budget
    }
}
