package dev.tracedown.worker.jobs

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The bounds on a retention pass.
 *
 * The defect these cover: retention read the whole expired set into the JVM
 * before deleting any of it, so the first organization with a large backlog took
 * the worker out of memory — and with it the aggregation, purge and cleanup jobs
 * that share the process. Deleting in bounded batches is the fix; these are the
 * two decisions that keep a batch loop from becoming an unbounded one.
 */
class RetentionBatchingTest {

    private val batch = 500
    private val budget: Duration = Duration.ofMinutes(10)

    @Test
    fun `a full round with budget left keeps going`() {
        assertEquals(
            RetentionBatching.Verdict.CONTINUE,
            RetentionBatching.verdict(batch, batch, Duration.ofMinutes(1), budget),
        )
    }

    @Test
    fun `a short round means the organization is drained`() {
        assertEquals(
            RetentionBatching.Verdict.ORG_DRAINED,
            RetentionBatching.verdict(batch - 1, batch, Duration.ofMinutes(1), budget),
        )
    }

    @Test
    fun `an empty round means the organization is drained`() {
        assertEquals(
            RetentionBatching.Verdict.ORG_DRAINED,
            RetentionBatching.verdict(0, batch, Duration.ZERO, budget),
        )
    }

    @Test
    fun `a drained organization is reported as drained even out of budget`() {
        // The caller re-checks the budget before starting the next organization,
        // so reporting BUDGET_SPENT here would only cost a re-scan of an
        // organization that is already empty.
        assertEquals(
            RetentionBatching.Verdict.ORG_DRAINED,
            RetentionBatching.verdict(1, batch, Duration.ofHours(1), budget),
        )
    }

    @Test
    fun `a full round out of budget stops the tick`() {
        assertEquals(
            RetentionBatching.Verdict.BUDGET_SPENT,
            RetentionBatching.verdict(batch, batch, Duration.ofMinutes(11), budget),
        )
    }

    // ---- the budget itself ----

    @Test
    fun `the budget is spent exactly at the boundary`() {
        assertFalse(RetentionBatching.budgetSpent(Duration.ofMinutes(9), budget))
        assertTrue(RetentionBatching.budgetSpent(budget, budget))
    }

    @Test
    fun `a zero or negative budget means no ceiling`() {
        assertFalse(RetentionBatching.budgetSpent(Duration.ofDays(7), Duration.ZERO))
        assertFalse(RetentionBatching.budgetSpent(Duration.ofDays(7), Duration.ofMinutes(-1)))
    }

    @Test
    fun `the defaults keep a batch small and a tick well inside its interval`() {
        // The interval is an hour; a tick that could outlast it would overlap
        // itself and hold two sets of connections.
        assertTrue(RetentionBatching.DEFAULT_BATCH_SIZE in 1..10_000)
        assertTrue(RetentionBatching.DEFAULT_TICK_BUDGET < Duration.ofHours(1))
    }
}
