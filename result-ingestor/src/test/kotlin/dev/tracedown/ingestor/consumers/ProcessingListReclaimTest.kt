package dev.tracedown.ingestor.consumers

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which in-flight probe results may be taken away from another consumer.
 *
 * This is the path that makes a hard kill survivable: a consumer killed between
 * taking a message and recording it leaves that message in a list nobody reads,
 * and nothing else in the system would ever look there. It is also the path that
 * can do damage if it is too eager — taking a message from a consumer that is
 * merely busy duplicates work, and taking one from *itself* would re-run the
 * message it is holding right now.
 */
class ProcessingListReclaimTest {

    private val self = "consumer-self"

    private fun processing(vararg ids: String) = ids.map { ProcessingListReclaim.processingKey(it) }

    @Test
    fun `a consumer with no heartbeat is reclaimed`() {
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = processing("dead-one"),
            liveConsumerIds = emptySet(),
            selfConsumerId = self,
        )
        assertEquals(listOf(ProcessingListReclaim.processingKey("dead-one")), orphans)
    }

    @Test
    fun `a consumer that is still beating is left alone`() {
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = processing("busy-one"),
            liveConsumerIds = setOf("busy-one"),
            selfConsumerId = self,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `a consumer never reclaims from itself`() {
        // Its own in-flight message is in hand, not abandoned — and at startup
        // its heartbeat may not have landed yet, which must not be read as death.
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = processing(self),
            liveConsumerIds = emptySet(),
            selfConsumerId = self,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `a restarted process reclaims the list its previous life left behind`() {
        // The restart takes a new id, so the abandoned list belongs to a
        // consumer that is not this one and has no heartbeat.
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = processing("consumer-before-the-kill", self),
            liveConsumerIds = emptySet(),
            selfConsumerId = self,
        )
        assertEquals(listOf(ProcessingListReclaim.processingKey("consumer-before-the-kill")), orphans)
    }

    @Test
    fun `only the dead among many are reclaimed`() {
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = processing("alive-a", "dead-b", "alive-c", "dead-d"),
            liveConsumerIds = setOf("alive-a", "alive-c"),
            selfConsumerId = self,
        )
        assertEquals(
            listOf(ProcessingListReclaim.processingKey("dead-b"), ProcessingListReclaim.processingKey("dead-d")),
            orphans,
        )
    }

    @Test
    fun `a key that is not a processing list is never touched`() {
        // The sweep scans by prefix; anything that slips through the pattern
        // must not be drained into the result queue.
        val orphans = ProcessingListReclaim.orphaned(
            processingKeys = listOf("probe_results_queue", "probe_results_dlq", ProcessingListReclaim.PROCESSING_PREFIX),
            liveConsumerIds = emptySet(),
            selfConsumerId = self,
        )
        assertTrue(orphans.isEmpty())
    }

    @Test
    fun `key naming round-trips`() {
        assertEquals("abc", ProcessingListReclaim.consumerIdOf(ProcessingListReclaim.processingKey("abc")))
        assertNull(ProcessingListReclaim.consumerIdOf("something_else"))
        assertNull(ProcessingListReclaim.consumerIdOf(ProcessingListReclaim.PROCESSING_PREFIX))
    }

    @Test
    fun `a live consumer refreshes several times inside its own expiry`() {
        // The margin is what stops a briefly-starved consumer from being
        // declared dead and having its message run twice.
        assertTrue(
            ProcessingListReclaim.HEARTBEAT_TTL_SECONDS >= 3 * ProcessingListReclaim.HEARTBEAT_REFRESH_SECONDS,
            "the heartbeat tolerates too few missed refreshes",
        )
    }
}
