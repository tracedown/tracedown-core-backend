package dev.tracedown.common.runs

import kotlin.test.Test
import kotlin.test.assertEquals

/** How long a run handle waits before it reads `expired`, from the probe timeout. */
class RunBoundsTest {

    @Test
    fun `the lock covers the timeout, the retry window and the margins, in whole seconds`() {
        // 30 s timeout + 15 s overhead + 20 s retries + 15 s margin.
        assertEquals(80, RunBounds.lockTtlSeconds(30_000))
        assertEquals(81, RunBounds.lockTtlSeconds(30_001))
    }

    @Test
    fun `the expiry is twice the lock and a margin, never under the floor`() {
        // 2 × 80 + 60 = 220, under the floor.
        assertEquals(RunBounds.MIN_EXPIRY_SECONDS, RunBounds.runExpirySeconds(30_000))
        assertEquals(600, RunBounds.runExpirySeconds(1))
        // The scheduler's ceiling: 2 × (300 + 50) + 60.
        assertEquals(760, RunBounds.runExpirySeconds(300_000))
    }

    @Test
    fun `the floor is where the curve crosses it`() {
        // 2 × lock + 60 reaches 600 at a 270 s lock, a 220 s timeout.
        assertEquals(600, RunBounds.runExpirySeconds(220_000))
        assertEquals(602, RunBounds.runExpirySeconds(221_000))
    }
}
