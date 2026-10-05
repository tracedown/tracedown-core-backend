package dev.tracedown.common.pfs

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Paging arithmetic. The offset is a Long because a page number a client
 * chose, times the page size, does not fit in an Int — computed in Int it
 * wraps to a negative offset the database refuses.
 */
class PfsParamsTest {

    @Test
    fun `the offset of a far page does not overflow`() {
        val params = PfsParams(page = Int.MAX_VALUE, pageSize = 100)
        assertEquals((Int.MAX_VALUE - 1).toLong() * 100, params.offset)
    }

    @Test
    fun `the offset strides by the clamped page size`() {
        assertEquals(0L, PfsParams(page = 1, pageSize = 50).offset)
        assertEquals(200L, PfsParams(page = 3, pageSize = 100).offset)
        assertEquals(200L, PfsParams(page = 3, pageSize = 1000).offset)
    }

    @Test
    fun `an in-memory page past the end is empty, however far past`() {
        val rows = (1..10).toList()
        assertEquals(emptyList(), rows.toPage(PfsParams(page = Int.MAX_VALUE, pageSize = 100)).items)
        assertEquals(listOf(9, 10), rows.toPage(PfsParams(page = 2, pageSize = 8)).items)
    }
}
