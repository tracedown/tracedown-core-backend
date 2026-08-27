package dev.tracedown.notifications.delivery

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * The breaker is what stops a dead endpoint from consuming a delivery worker on
 * every event bound to it. Driven with a hand-cranked clock — no network, no
 * coroutines, no wall-clock waiting.
 */
class WebhookCircuitBreakerTest {

    private var now = 1_000L
    private val hook = UUID.randomUUID()
    private val other = UUID.randomUUID()

    private fun breaker(threshold: Int = 3, openMs: Long = 60_000L) =
        WebhookCircuitBreaker(failureThreshold = threshold, openMillis = openMs, clock = { now })

    @Test
    fun `an unknown webhook is closed`() {
        assertFalse(breaker().isOpen(hook))
    }

    @Test
    fun `failures below the threshold do not open the circuit`() {
        val b = breaker()
        b.recordFailure(hook)
        b.recordFailure(hook)
        assertFalse(b.isOpen(hook), "two failures must still be dialled — endpoints have bad minutes")
        assertEquals(2, b.failureCount(hook))
    }

    @Test
    fun `the threshold failure opens the circuit`() {
        val b = breaker()
        repeat(3) { b.recordFailure(hook) }
        assertTrue(b.isOpen(hook))
    }

    @Test
    fun `an open circuit closes again when its window expires`() {
        val b = breaker()
        repeat(3) { b.recordFailure(hook) }
        now += 59_999
        assertTrue(b.isOpen(hook), "still inside the window")
        now += 1
        assertFalse(b.isOpen(hook), "the window has passed — one probe delivery is let through")
    }

    @Test
    fun `a probe that fails re-opens the circuit immediately`() {
        val b = breaker()
        repeat(3) { b.recordFailure(hook) }
        now += 60_000
        assertFalse(b.isOpen(hook))
        b.recordFailure(hook)
        assertTrue(b.isOpen(hook), "the failure count is still past the threshold, so it re-opens at once")
    }

    @Test
    fun `a success forgets the endpoint entirely`() {
        val b = breaker()
        repeat(3) { b.recordFailure(hook) }
        now += 60_000
        b.recordSuccess(hook)
        assertFalse(b.isOpen(hook))
        assertEquals(0, b.failureCount(hook))
        // And the next bad minute starts from zero rather than re-opening.
        b.recordFailure(hook)
        assertFalse(b.isOpen(hook))
    }

    @Test
    fun `one dead endpoint does not trip another`() {
        val b = breaker()
        repeat(3) { b.recordFailure(hook) }
        assertTrue(b.isOpen(hook))
        assertFalse(b.isOpen(other), "state is per webhook id, not per host or global")
    }

    @Test
    fun `a threshold of one opens on the first failure`() {
        val b = breaker(threshold = 1)
        b.recordFailure(hook)
        assertTrue(b.isOpen(hook))
    }

    @Test
    fun `tracked state is dropped on success rather than accumulating`() {
        val b = breaker()
        b.recordFailure(hook)
        b.recordFailure(other)
        assertEquals(2, b.trackedCount())
        b.recordSuccess(hook)
        b.recordSuccess(other)
        assertEquals(0, b.trackedCount())
    }
}
