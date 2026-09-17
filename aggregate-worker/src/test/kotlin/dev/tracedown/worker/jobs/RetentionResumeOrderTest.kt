package dev.tracedown.worker.jobs

import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The order the retention cursor reasons in.
 *
 * The defect these cover: the organization list comes out of PostgreSQL's
 * `ORDER BY organization_id`, which compares a `uuid` as sixteen unsigned
 * bytes, while the cursor located itself in that list with Kotlin's `<`, which
 * is [java.util.UUID.compareTo] — a *signed* comparison of the top 64 bits. The
 * two disagree for every pair of ids whose top bit differs, so for about half of
 * all cursor values a budget-bound tick rotated to the wrong place: back to the
 * front of the list, re-sweeping organizations it had just swept and never
 * reaching the tail it stopped in. That is the exact starvation the cursor was
 * added to prevent, and it is invisible in production — the job logs that it is
 * resuming at an organization it then does not start at.
 *
 * These ids straddle the sign bit deliberately: `7fff…` sorts *first* in the
 * database and *second* in Java.
 */
class RetentionResumeOrderTest {

    /** Sorts first in PostgreSQL, second under `java.util.UUID.compareTo`. */
    private val low = UUID.fromString("7fffffff-0000-4000-8000-000000000000")

    /** Sorts second in PostgreSQL, first under `java.util.UUID.compareTo`. */
    private val high = UUID.fromString("80000000-0000-4000-8000-000000000000")

    @Test
    fun `the comparator is the database's order, not Java's`() {
        assertTrue(RetentionJob.ORG_ID_ORDER.compare(low, high) < 0, "PostgreSQL sorts 7fff… before 8000…")
        assertTrue(low > high, "Java sorts 8000… first — the mismatch this comparator exists for")
    }

    @Test
    fun `the comparator falls through to the low bits`() {
        val a = UUID(0L, -1L) // low bits 0xffff…, unsigned-greatest
        val b = UUID(0L, 1L)
        assertTrue(RetentionJob.ORG_ID_ORDER.compare(b, a) < 0)
        assertEquals(0, RetentionJob.ORG_ID_ORDER.compare(a, UUID(0L, -1L)))
    }

    @Test
    fun `a cursor past the sign bit resumes at its own organization`() {
        // Database order, as the job receives it.
        val orgIds = listOf(low, high)

        // The tick stopped on `high`, the second organization: the next one has
        // to start there, or `high` is never reached at all.
        assertEquals(listOf(high, low), RetentionJob.resumeOrder(orgIds, high))
    }

    @Test
    fun `a cursor at the head leaves the list alone`() {
        val orgIds = listOf(low, high)
        assertEquals(orgIds, RetentionJob.resumeOrder(orgIds, low))
        assertEquals(orgIds, RetentionJob.resumeOrder(orgIds, null))
    }

    @Test
    fun `a cursor whose organization is gone resumes at the next one`() {
        val gone = UUID.fromString("7fffffff-0000-4000-8000-000000000001")
        assertEquals(listOf(high, low), RetentionJob.resumeOrder(listOf(low, high), gone))
    }

    @Test
    fun `a cursor past the end of the list starts from the top`() {
        val beyond = UUID.fromString("ffffffff-ffff-4fff-bfff-ffffffffffff")
        assertEquals(listOf(low, high), RetentionJob.resumeOrder(listOf(low, high), beyond))
    }

    @Test
    fun `rotation keeps every organization exactly once`() {
        val orgIds = listOf(low, high, UUID.fromString("c0000000-0000-4000-8000-000000000000"))
        val rotated = RetentionJob.resumeOrder(orgIds, orgIds[1])
        assertEquals(orgIds.toSet(), rotated.toSet())
        assertEquals(orgIds.size, rotated.size)
    }
}
