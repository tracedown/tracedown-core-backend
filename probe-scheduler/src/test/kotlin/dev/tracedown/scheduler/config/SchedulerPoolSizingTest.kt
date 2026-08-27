package dev.tracedown.scheduler.config

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How many database connections the scheduler asks for.
 *
 * The defect these cover: the pool was left at its default of ten while fifty
 * dispatch workers each ran about six blocking transactions per tick. Under a
 * full tick the workers hit Hikari's 30-second connection timeout; the
 * exception was caught and logged, and the probe produced no result row at all.
 * The pool has to be a function of the configured concurrency — every dispatch
 * worker can want a connection at the same instant, which is what "concurrent
 * dispatches" means.
 */
class SchedulerPoolSizingTest {

    @Test
    fun `the pool covers every dispatch worker plus the rest of the service`() {
        // The consistency sweep, the health challenge round, the shed recorders
        // and the schedule bootstrap each want a connection of their own.
        assertEquals(
            50 + SchedulerConfig.POOL_HEADROOM,
            SchedulerConfig.poolSizeFor(dispatchWorkers = 50),
        )
        assertTrue(SchedulerConfig.poolSizeFor(dispatchWorkers = 50) > 50)
    }

    @Test
    fun `raising the workers raises the pool with them`() {
        val small = SchedulerConfig.poolSizeFor(dispatchWorkers = 10)
        val large = SchedulerConfig.poolSizeFor(dispatchWorkers = 100)
        assertEquals(90, large - small)
    }

    @Test
    fun `the default no longer leaves fifty workers on ten connections`() {
        // The shape of the original defect, stated as a test.
        assertTrue(SchedulerConfig.poolSizeFor(dispatchWorkers = 50) >= 50)
    }

    // ---- the operator override ----

    @Test
    fun `an explicit size wins over the derivation`() {
        assertEquals(20, SchedulerConfig.resolvePoolSize(explicit = 20, dispatchWorkers = 50))
    }

    @Test
    fun `an absent or nonsensical override falls back to the derivation`() {
        val derived = SchedulerConfig.poolSizeFor(dispatchWorkers = 50)
        assertEquals(derived, SchedulerConfig.resolvePoolSize(null, 50))
        assertEquals(derived, SchedulerConfig.resolvePoolSize(0, 50))
        assertEquals(derived, SchedulerConfig.resolvePoolSize(-1, 50))
    }
}
