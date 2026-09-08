package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.domain.DomainPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The unverified-domain throttle key must expire before the next tick of a
 * schedule that equals the minimum interval, and after the next tick of the
 * next faster cron schedule (one minute shorter).
 */
class UnverifiedThrottleTest {

    @Test
    fun `a schedule at the minimum interval is never throttled by the race`() {
        val ttl = QueuePolicyManager.unverifiedThrottleTtlSeconds(DomainPolicy.MIN_INTERVAL_SECONDS)
        assertTrue(ttl < DomainPolicy.MIN_INTERVAL_SECONDS, "ttl $ttl must be shorter than the window")
        // The next faster cron schedule is a whole minute shorter; it must still be caught.
        assertTrue(ttl > DomainPolicy.MIN_INTERVAL_SECONDS - 60, "ttl $ttl must outlast a schedule one minute faster")
    }

    @Test
    fun `the ttl never collapses`() {
        assertEquals(1L, QueuePolicyManager.unverifiedThrottleTtlSeconds(5))
        assertEquals(270L, QueuePolicyManager.unverifiedThrottleTtlSeconds(300))
    }
}
