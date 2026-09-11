package dev.tracedown.ingestor.services

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BodyNotStoredReasonTest {

    @Test
    fun `a body the scheduler withheld is recorded under the scheduler's reason`() {
        assertEquals("unverifiedTarget", BodyNotStoredReason.resolve("notRequested", "unverifiedTarget", false))
    }

    @Test
    fun `a body the service did not ask for stays notRequested`() {
        assertEquals("notRequested", BodyNotStoredReason.resolve("notRequested", null, false))
        assertEquals("notRequested", BodyNotStoredReason.resolve("notRequested", "", false))
    }

    @Test
    fun `the executor's other reasons are never overridden`() {
        assertEquals("bodyTooLarge", BodyNotStoredReason.resolve("bodyTooLarge", "unverifiedTarget", false))
        assertEquals("timeout", BodyNotStoredReason.resolve("timeout", null, true))
    }

    @Test
    fun `a captured body that could not be relocated is storageUnavailable`() {
        assertEquals("storageUnavailable", BodyNotStoredReason.resolve(null, null, true))
    }

    @Test
    fun `a stored body has no reason`() {
        assertNull(BodyNotStoredReason.resolve(null, null, false))
    }

    @Test
    fun `a location outside the agent's store is named as such`() {
        assertEquals("outsideAssignedStore", BodyNotStoredReason.resolve(null, null, false, outsideAssignedStore = true))
        // The executor's own reason still wins: no body was captured at all.
        assertEquals("bodyTooLarge", BodyNotStoredReason.resolve("bodyTooLarge", null, false, outsideAssignedStore = true))
    }
}
