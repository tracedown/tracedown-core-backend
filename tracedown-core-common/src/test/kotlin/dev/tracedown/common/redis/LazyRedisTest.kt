package dev.tracedown.common.redis

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

/** A Redis that is not there answers at once, and is not asked again while backing off. */
class LazyRedisTest {

    @Test
    fun `an unreachable Redis fails fast, then fails at once while backing off`() {
        // Nothing listens on port 1.
        val redis = LazyRedis("redis://127.0.0.1:1", backoff = Duration.ofMinutes(5))
        val first = System.nanoTime()
        assertThrows(Exception::class.java) { redis.commands() }
        assertTrue(Duration.ofNanos(System.nanoTime() - first) < Duration.ofSeconds(10), "the first attempt was retried")

        val second = System.nanoTime()
        val refusal = assertThrows(IllegalStateException::class.java) { redis.commands() }
        assertTrue(Duration.ofNanos(System.nanoTime() - second) < Duration.ofMillis(200), "a backed-off call tried again")
        assertTrue("not trying again" in refusal.message!!)
    }
}
