package dev.tracedown.gateway.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class HourBucketsTest {

    private val now = Instant.parse("2026-03-14T15:31:07Z")

    @Test
    fun `key names the hour containing the instant`() {
        assertEquals("2026031415", HourBuckets.key(now))
        assertEquals("2026031415", HourBuckets.key(Instant.parse("2026-03-14T15:00:00Z")))
        assertEquals("2026031415", HourBuckets.key(Instant.parse("2026-03-14T15:59:59.999Z")))
        assertEquals("2026031416", HourBuckets.key(Instant.parse("2026-03-14T16:00:00Z")))
    }

    @Test
    fun `start is the top of the hour the key names`() {
        assertEquals(Instant.parse("2026-03-14T15:00:00Z"), HourBuckets.start("2026031415"))
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), HourBuckets.start("2026010100"))
    }

    @Test
    fun `start rejects a key that is not an hour key`() {
        for (bad in listOf("", "20260314", "2026031415  ", "20260314151", "2026-031415", "abcdefghij", "2026031499")) {
            assertThrows(IllegalArgumentException::class.java) { HourBuckets.start(bad) }
        }
    }

    @Test
    fun `window ends on the hour in progress and runs oldest first`() {
        val keys = HourBuckets.window(now, 3)
        assertEquals(listOf("2026031413", "2026031414", "2026031415"), keys)
        assertEquals(listOf("2026031415"), HourBuckets.window(now, 1))
        assertTrue(HourBuckets.window(now, 0).isEmpty())
    }

    @Test
    fun `only the hour in progress is open`() {
        assertFalse(HourBuckets.isClosed("2026031415", now))
        assertTrue(HourBuckets.isClosed("2026031414", now))
        // On the stroke of the hour the hour that just ended is closed.
        val exact = Instant.parse("2026-03-14T15:00:00Z")
        assertFalse(HourBuckets.isClosed("2026031415", exact))
        assertTrue(HourBuckets.isClosed("2026031414", exact))
        // A key ahead of the clock is not closed either.
        assertFalse(HourBuckets.isClosed("2026031416", now))
    }

    @Test
    fun `splitClosed separates the window into closed hours and the live one`() {
        val (closed, open) = HourBuckets.splitClosed(HourBuckets.window(now, 4), now)
        assertEquals(listOf("2026031412", "2026031413", "2026031414"), closed)
        assertEquals(listOf("2026031415"), open)
    }

    @Test
    fun `a bucket is trusted only when it carries the seal`() {
        assertTrue(HourBuckets.isSealed(mapOf("total" to "24", "sealed" to "1")))
        // No seal: half an hour of counters looks exactly like a whole one.
        assertFalse(HourBuckets.isSealed(mapOf("total" to "24")))
        assertFalse(HourBuckets.isSealed(emptyMap()))
        assertFalse(HourBuckets.isSealed(mapOf("sealed" to "0")))
        assertFalse(HourBuckets.isSealed(mapOf("sealed" to "")))
    }

    @Test
    fun `retention covers the hours a written bucket would still be alive for`() {
        val ttl = 90000L // 25h, the hourly-bucket TTL
        assertTrue(HourBuckets.isWithinRetention(HourBuckets.key(now), now, ttl))
        assertTrue(HourBuckets.isWithinRetention(HourBuckets.key(now.minusSeconds(24 * 3600L)), now, ttl))
        assertFalse(HourBuckets.isWithinRetention(HourBuckets.key(now.minusSeconds(48 * 3600L)), now, ttl))
    }
}
