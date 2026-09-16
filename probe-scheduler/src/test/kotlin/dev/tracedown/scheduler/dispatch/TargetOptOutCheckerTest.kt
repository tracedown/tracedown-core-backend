package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.domain.TargetOptOut
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.protocol.CommandArgs
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The opt-out answer is cached in Redis A so a one-minute schedule asks DNS
 * once an hour rather than sixty times — and so a fleet of such schedules does
 * not look like a flood to the very operator who asked to be left alone. The
 * cache must never become a gate: with Redis gone the question is still asked,
 * and dispatch still decides.
 */
class TargetOptOutCheckerTest {

    /** Counts what the resolver was actually asked. */
    private class CountingLookup(private val published: Set<String>) : (String) -> List<String> {
        val asked = mutableListOf<String>()
        override fun invoke(name: String): List<String> {
            asked.add(name)
            return if (name in published) listOf("") else emptyList()
        }
    }

    private fun lookup(vararg publishedHosts: String) =
        CountingLookup(publishedHosts.map { TargetOptOut.recordName(it) }.toSet())

    @Test
    fun `a host that publishes the record is refused and remembered`() {
        val redis = FakeRedis()
        val resolver = lookup("example.com")
        val checker = TargetOptOutChecker(redis.commands(), resolver)

        assertTrue(checker.optedOut("example.com"))
        assertEquals(
            TargetOptOutChecker.CACHED_YES,
            redis.store["${TargetOptOutChecker.CACHE_PREFIX}example.com"],
        )
        assertEquals(
            TargetOptOutChecker.CACHE_TTL_SECONDS,
            redis.ttls["${TargetOptOutChecker.CACHE_PREFIX}example.com"],
        )
    }

    @Test
    fun `a cached refusal is not looked up again`() {
        val redis = FakeRedis()
        redis.store["${TargetOptOutChecker.CACHE_PREFIX}example.com"] = TargetOptOutChecker.CACHED_YES
        val resolver = lookup()
        val checker = TargetOptOutChecker(redis.commands(), resolver)

        assertTrue(checker.optedOut("example.com"))
        assertTrue(resolver.asked.isEmpty(), "a cached answer must cost no DNS round-trip")
    }

    @Test
    fun `a host without the record is remembered too, for the same hour`() {
        val redis = FakeRedis()
        val resolver = lookup()
        val checker = TargetOptOutChecker(redis.commands(), resolver)

        assertFalse(checker.optedOut("api.example.com"))
        val key = "${TargetOptOutChecker.CACHE_PREFIX}api.example.com"
        assertEquals(TargetOptOutChecker.CACHED_NO, redis.store[key])
        assertEquals(TargetOptOutChecker.CACHE_TTL_SECONDS, redis.ttls[key])

        val afterFirst = resolver.asked.size
        assertEquals(TargetOptOut.candidates("api.example.com").size, afterFirst, "one walk of the name and its parent")

        // The negative answer is what every probed host hits, every tick: left
        // uncached, the cache would save nothing at all.
        assertFalse(checker.optedOut("api.example.com"))
        assertEquals(afterFirst, resolver.asked.size, "the second tick must ask nothing")
    }

    @Test
    fun `a Redis outage falls through to the resolver rather than blocking dispatch`() {
        val resolver = lookup("example.com")
        val checker = TargetOptOutChecker(brokenRedis(), resolver)

        assertTrue(checker.optedOut("example.com"))
        assertFalse(checker.optedOut("elsewhere.example"))
    }

    @Test
    fun `no cache at all is a supported configuration`() {
        val resolver = lookup("example.com")
        val checker = TargetOptOutChecker(null, resolver)

        assertTrue(checker.optedOut("example.com"))
    }

    @Test
    fun `the TTL is configurable and is what the key gets`() {
        val redis = FakeRedis()
        TargetOptOutChecker(redis.commands(), lookup(), ttlSeconds = 60L).optedOut("example.com")
        assertEquals(60L, redis.ttls["${TargetOptOutChecker.CACHE_PREFIX}example.com"])
    }

    /** A RedisCommands proxy that throws on every call. */
    @Suppress("UNCHECKED_CAST")
    private fun brokenRedis(): RedisCommands<String, String> = Proxy.newProxyInstance(
        RedisCommands::class.java.classLoader,
        arrayOf(RedisCommands::class.java),
    ) { _, method: Method, _ -> throw RuntimeException("Redis connection lost: ${method.name}") }
        as RedisCommands<String, String>

    /** In-memory stand-in for Redis A, over the two commands this uses. */
    private class FakeRedis {
        val store: MutableMap<String, String> = mutableMapOf()
        val ttls: MutableMap<String, Long> = mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        fun commands(): RedisCommands<String, String> = Proxy.newProxyInstance(
            RedisCommands::class.java.classLoader,
            arrayOf(RedisCommands::class.java),
        ) { _: Any, method: Method, args: Array<Any>? ->
            when (method.name) {
                "get" -> store[args!![0] as String]
                "set" -> {
                    val key = args!![0] as String
                    store[key] = args[1] as String
                    // SetArgs exposes no getter, so the expiry is read back off
                    // the rendered command rather than by assuming a layout.
                    val rendered = CommandArgs(StringCodec.UTF8)
                    (args[2] as SetArgs).build(rendered)
                    EX_SECONDS.find(rendered.toCommandString())?.let { ttls[key] = it.groupValues[1].toLong() }
                    "OK"
                }
                else -> throw UnsupportedOperationException("FakeRedis does not support ${method.name}")
            }
        } as RedisCommands<String, String>

        private companion object {
            val EX_SECONDS = Regex("\\bEX (\\d+)\\b")
        }
    }
}
