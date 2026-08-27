package dev.tracedown.worker.jobs

import dev.tracedown.common.models.OutboxCursorState
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Which consumer cursors may hold the outbox back.
 *
 * The defect these cover: the purge deleted only below the slowest registered
 * cursor, with nothing bounding how long "slowest" could mean "not moving".
 * A consumer that degrades correctly — retrying forever against a dependency
 * that is down — keeps its cursor row and stops advancing it, so the outbox,
 * which takes a row per probe result, grew until the database filled. A retired
 * consumer whose cursor row was never deleted did the same thing permanently.
 *
 * The rule has to hold with no consumers registered at all, which is the normal
 * self-hosted shape.
 */
class OutboxCursorPolicyTest {

    private val now: Instant = Instant.parse("2026-08-27T12:00:00Z")
    private val horizon: Duration = Duration.ofHours(24)
    private val head = 1_000_000L

    private fun cursor(name: String, lastId: Long, agedHours: Long) =
        OutboxCursorState(name, lastId, now.minus(Duration.ofHours(agedHours)))

    // ---- no consumers: the floor is absent and nothing changes ----

    @Test
    fun `with no registered consumers there is no floor`() {
        val d = OutboxCursorPolicy.decide(emptyList(), headSeq = head, now = now, horizon = horizon)
        assertNull(d.floor)
        assertTrue(d.abandoned.isEmpty())
        assertTrue(d.lagging.isEmpty())
    }

    @Test
    fun `an empty outbox with no consumers is not an abandonment`() {
        val d = OutboxCursorPolicy.decide(emptyList(), headSeq = 0, now = now, horizon = horizon)
        assertNull(d.floor)
        assertTrue(d.abandoned.isEmpty())
    }

    // ---- a healthy consumer keeps its veto ----

    @Test
    fun `a consumer that advanced recently holds the floor`() {
        val d = OutboxCursorPolicy.decide(
            listOf(cursor("indexer", lastId = 900_000, agedHours = 1)),
            headSeq = head, now = now, horizon = horizon,
        )
        assertEquals(900_000L, d.floor)
        assertTrue(d.abandoned.isEmpty())
    }

    @Test
    fun `a caught-up consumer is never abandoned however long it has been idle`() {
        // This is the case elapsed time alone would get wrong: a consumer with
        // nothing to read does not advance, so a quiet deployment would condemn
        // its only healthy consumer. It also pins nothing — it sits at the head.
        val idle = cursor("indexer", lastId = head, agedHours = 24 * 365)
        assertFalse(OutboxCursorPolicy.isAbandoned(idle, head, now, horizon))

        val d = OutboxCursorPolicy.decide(listOf(idle), headSeq = head, now = now, horizon = horizon)
        assertEquals(head, d.floor)
        assertTrue(d.abandoned.isEmpty())
    }

    @Test
    fun `a consumer behind the head but inside the horizon still holds the floor`() {
        // Down for an hour, or restarting: it gets to come back and catch up.
        val d = OutboxCursorPolicy.decide(
            listOf(cursor("indexer", lastId = 10, agedHours = 23)),
            headSeq = head, now = now, horizon = horizon,
        )
        assertEquals(10L, d.floor)
        assertTrue(d.abandoned.isEmpty())
        assertEquals(listOf("indexer"), d.lagging.map { it.consumerName })
        assertEquals(head - 10, d.lagging.single().behind)
    }

    // ---- a stalled consumer stops pinning the log ----

    @Test
    fun `a consumer behind the head and past the horizon is disregarded`() {
        val d = OutboxCursorPolicy.decide(
            listOf(cursor("indexer", lastId = 10, agedHours = 25)),
            headSeq = head, now = now, horizon = horizon,
        )
        assertNull(d.floor, "a disregarded cursor must not leave a floor behind")
        assertEquals(listOf("indexer"), d.abandoned)
    }

    @Test
    fun `one stalled consumer does not cost the others their veto`() {
        val d = OutboxCursorPolicy.decide(
            listOf(
                cursor("stalled", lastId = 10, agedHours = 72),
                cursor("healthy", lastId = 500_000, agedHours = 1),
            ),
            headSeq = head, now = now, horizon = horizon,
        )
        assertEquals(500_000L, d.floor)
        assertEquals(listOf("stalled"), d.abandoned)
    }

    @Test
    fun `the floor is the slowest of the consumers that still count`() {
        val d = OutboxCursorPolicy.decide(
            listOf(
                cursor("a", lastId = 700_000, agedHours = 1),
                cursor("b", lastId = 300_000, agedHours = 2),
                cursor("c", lastId = 999_999, agedHours = 1),
            ),
            headSeq = head, now = now, horizon = horizon,
        )
        assertEquals(300_000L, d.floor)
    }

    @Test
    fun `every consumer stalled is the same as no consumers`() {
        val d = OutboxCursorPolicy.decide(
            listOf(
                cursor("a", lastId = 1, agedHours = 100),
                cursor("b", lastId = 2, agedHours = 100),
            ),
            headSeq = head, now = now, horizon = horizon,
        )
        assertNull(d.floor)
        assertEquals(listOf("a", "b"), d.abandoned)
    }

    // ---- the horizon boundary and the escape hatch ----

    @Test
    fun `the horizon is exclusive at its boundary`() {
        val exactly = OutboxCursorState("indexer", 10, now.minus(horizon))
        assertFalse(OutboxCursorPolicy.isAbandoned(exactly, head, now, horizon))

        val justPast = OutboxCursorState("indexer", 10, now.minus(horizon).minusMillis(1))
        assertTrue(OutboxCursorPolicy.isAbandoned(justPast, head, now, horizon))
    }

    @Test
    fun `a non-positive horizon restores the unconditional floor`() {
        val ancient = cursor("indexer", lastId = 10, agedHours = 24 * 365)
        assertFalse(OutboxCursorPolicy.isAbandoned(ancient, head, now, Duration.ZERO))

        val d = OutboxCursorPolicy.decide(listOf(ancient), headSeq = head, now = now, horizon = Duration.ZERO)
        assertEquals(10L, d.floor)
        assertTrue(d.abandoned.isEmpty())
    }

    @Test
    fun `the default horizon is shorter than the outbox retention window`() {
        // What a returning consumer actually loses is decided by retention, not
        // by this: disregarded at a day, but everything inside the 7-day
        // retention window is still there when it comes back.
        assertTrue(OutboxCursorPolicy.DEFAULT_STALE_HORIZON < Duration.ofDays(7))
        assertTrue(OutboxCursorPolicy.DEFAULT_STALE_HORIZON >= Duration.ofHours(1))
    }
}
