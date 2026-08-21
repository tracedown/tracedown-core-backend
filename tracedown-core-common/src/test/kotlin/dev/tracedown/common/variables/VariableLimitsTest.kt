package dev.tracedown.common.variables

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VariableLimitsTest {

    @AfterTest
    fun reset() = VariableLimits.init(VariableLimits.DEFAULT_MAX_PER_RESOURCE)

    @Test
    fun `a resource is full at the cap, not one past it`() {
        VariableLimits.init(3)
        assertFalse(VariableLimits.isFull(0))
        assertFalse(VariableLimits.isFull(2))
        // The third variable is allowed; the fourth is what this refuses.
        assertTrue(VariableLimits.isFull(3))
        assertTrue(VariableLimits.isFull(9_999))
    }

    @Test
    fun `an unset limit still bounds creation`() {
        assertEquals(VariableLimits.DEFAULT_MAX_PER_RESOURCE, VariableLimits.max())
        assertTrue(VariableLimits.isFull(VariableLimits.DEFAULT_MAX_PER_RESOURCE.toLong()))
    }

    @Test
    fun `a nonsensical configured value is ignored rather than obeyed`() {
        VariableLimits.init(5)
        // Zero or negative would lock every resource out of creating anything,
        // so a misconfiguration keeps the last good value instead.
        VariableLimits.init(0)
        assertEquals(5, VariableLimits.max())
        VariableLimits.init(-10)
        assertEquals(5, VariableLimits.max())
    }
}
