package dev.tracedown.common.health

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The readiness endpoint's decision logic. The point of these checks is that a
 * dependency which has gone away is *reported*, rather than a static string
 * claiming health on behalf of a dead connection pool — so the interesting
 * cases are the failing ones.
 */
class HealthCheckTest {

    @Test
    fun `a passing probe is healthy`() {
        val result = HealthCheck("db") { true }.run()
        assertTrue(result.healthy)
        assertTrue(result.required)
        assertEquals(null, result.detail)
    }

    @Test
    fun `a probe returning false fails without throwing`() {
        val result = HealthCheck("db") { false }.run()
        assertFalse(result.healthy)
        assertEquals("check returned false", result.detail)
    }

    @Test
    fun `a throwing probe fails with its message rather than propagating`() {
        val result = HealthCheck("redis") { throw IllegalStateException("connection reset") }.run()
        assertFalse(result.healthy)
        assertEquals("connection reset", result.detail)
    }

    @Test
    fun `a throw with no message still reports something`() {
        val result = HealthCheck("redis") { throw RuntimeException() }.run()
        assertFalse(result.healthy)
        assertEquals("RuntimeException", result.detail)
    }

    @Test
    fun `optional checks carry their own flag`() {
        val result = HealthCheck("cache", required = false) { false }.run()
        assertFalse(result.healthy)
        assertFalse(result.required)
    }
}
