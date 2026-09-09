package dev.tracedown.metrics.cache

import io.lettuce.core.api.sync.RedisCommands
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class MetricsWriterTest {

    private lateinit var redis: FakeRedis
    private lateinit var writer: MetricsWriter

    companion object {
        private const val METRICS_TTL = 86400L
        private const val HOURLY_TTL = 172800L
    }

    @BeforeEach
    fun setUp() {
        redis = FakeRedis()
        writer = MetricsWriter(redis.commands(), METRICS_TTL, HOURLY_TTL, 604800L)
    }

    @Test
    fun `record increments probes_total and probes_status counters`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 120)

        val counters = redis.hashes["metrics:svc:$serviceId:counters"]!!
        assertEquals("1", counters["probes_total"])
        assertEquals("1", counters["probes_success"])

        writer.record(serviceId, "failure", 500)

        assertEquals("2", counters["probes_total"])
        assertEquals("1", counters["probes_success"])
        assertEquals("1", counters["probes_failure"])
    }

    @Test
    fun `record updates last_status last_response_ms and last_run_at in state hash`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 142)

        val state = redis.hashes["metrics:svc:$serviceId:state"]!!
        assertEquals("success", state["last_status"])
        assertEquals("142", state["last_response_ms"])
        assertNotNull(state["last_run_at"])

        val runAt = state["last_run_at"]!!.toLong()
        val now = Instant.now().epochSecond
        assertTrue(runAt in (now - 5)..now, "last_run_at should be close to current time")
    }

    @Test
    fun `record increments last_consecutive when status is same`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 100)
        writer.record(serviceId, "success", 110)
        writer.record(serviceId, "success", 120)

        val state = redis.hashes["metrics:svc:$serviceId:state"]!!
        assertEquals("3", state["last_consecutive"])
    }

    @Test
    fun `record resets last_consecutive to 1 when status changes`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 100)
        writer.record(serviceId, "success", 110)
        writer.record(serviceId, "success", 120)

        val state = redis.hashes["metrics:svc:$serviceId:state"]!!
        assertEquals("3", state["last_consecutive"])

        writer.record(serviceId, "failure", 500)
        assertEquals("1", state["last_consecutive"])
        assertEquals("failure", state["last_status"])
    }

    @Test
    fun `record creates hourly bucket with correct key format`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 200)

        val hourBucket = DateTimeFormatter.ofPattern("yyyyMMddHH")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val hourKey = "metrics:svc:$serviceId:h:$hourBucket"

        val bucket = redis.hashes[hourKey]!!
        assertEquals("1", bucket["total"])
        assertEquals("1", bucket["success"])
        assertEquals("200", bucket["sum_ms"])
    }

    @Test
    fun `record sets initial TTL on new keys but does not reset on existing keys`() {
        val serviceId = UUID.randomUUID()
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"

        writer.record(serviceId, "success", 100)

        assertEquals(METRICS_TTL, redis.ttls[counterKey])
        assertEquals(METRICS_TTL, redis.ttls[stateKey])

        // Simulate TTL ticking down
        redis.ttls[counterKey] = 50000L
        redis.ttls[stateKey] = 50000L

        writer.record(serviceId, "success", 110)

        // TTL should NOT be reset — still 50000
        assertEquals(50000L, redis.ttls[counterKey])
        assertEquals(50000L, redis.ttls[stateKey])
    }

    @Test
    fun `record sets initial TTL on hourly bucket key`() {
        val serviceId = UUID.randomUUID()
        writer.record(serviceId, "success", 100)

        val hourBucket = DateTimeFormatter.ofPattern("yyyyMMddHH")
            .withZone(ZoneOffset.UTC)
            .format(Instant.now())
        val hourKey = "metrics:svc:$serviceId:h:$hourBucket"

        assertEquals(HOURLY_TTL, redis.ttls[hourKey])
    }

    @Test
    fun `record leaves a sealed hourly bucket alone`() {
        val serviceId = UUID.randomUUID()
        val hourKey = "metrics:svc:$serviceId:h:${currentHourBucket()}"

        // The hour closed and the API recomputed it from probe_results.
        redis.hashes[hourKey] = mutableMapOf(
            "total" to "24", "success" to "22", "failure" to "2",
            "timeout" to "0", "sum_ms" to "2400", "call_count" to "24",
            "sealed" to "1",
        )

        // A result whose hour key was taken just before the hour rolled over.
        writer.record(serviceId, "success", 200)

        val bucket = redis.hashes[hourKey]!!
        assertEquals("24", bucket["total"])
        assertEquals("22", bucket["success"])
        assertEquals("2400", bucket["sum_ms"])
        assertEquals("1", bucket["sealed"])

        // The rest of the write is unaffected — only the sealed hour is skipped.
        assertEquals("1", redis.hashes["metrics:svc:$serviceId:counters"]!!["probes_total"])
        assertEquals("success", redis.hashes["metrics:svc:$serviceId:state"]!!["last_status"])
    }

    private fun currentHourBucket(): String = DateTimeFormatter.ofPattern("yyyyMMddHH")
        .withZone(ZoneOffset.UTC)
        .format(Instant.now())

    /**
     * Minimal fake Redis backed by in-memory maps. Implements only the methods
     * used by MetricsWriter via a JDK dynamic proxy on RedisCommands.
     */
    class FakeRedis {
        val hashes: MutableMap<String, MutableMap<String, String>> = mutableMapOf()
        val ttls: MutableMap<String, Long> = mutableMapOf()

        @Suppress("UNCHECKED_CAST")
        fun commands(): RedisCommands<String, String> {
            return Proxy.newProxyInstance(
                RedisCommands::class.java.classLoader,
                arrayOf(RedisCommands::class.java),
            ) { _: Any, method: Method, args: Array<Any>? ->
                when (method.name) {
                    "hincrby" -> {
                        val key = args!![0] as String
                        val field = args[1] as String
                        val amount = args[2] as Long
                        val hash = hashes.getOrPut(key) { mutableMapOf() }
                        val current = hash[field]?.toLong() ?: 0L
                        val newVal = current + amount
                        hash[field] = newVal.toString()
                        newVal
                    }
                    "hget" -> {
                        val key = args!![0] as String
                        val field = args[1] as String
                        hashes[key]?.get(field)
                    }
                    "hset" -> {
                        if (args!!.size == 3 && args[1] is String) {
                            val key = args[0] as String
                            val field = args[1] as String
                            val value = args[2] as String
                            val hash = hashes.getOrPut(key) { mutableMapOf() }
                            val had = hash.containsKey(field)
                            hash[field] = value
                            !had
                        } else {
                            // hset(key, map) variant
                            val key = args[0] as String
                            val map = args[1] as Map<String, String>
                            val hash = hashes.getOrPut(key) { mutableMapOf() }
                            var added = 0L
                            map.forEach { (k, v) ->
                                if (!hash.containsKey(k)) added++
                                hash[k] = v
                            }
                            added
                        }
                    }
                    "hgetall" -> {
                        val key = args!![0] as String
                        hashes[key]?.toMap() ?: emptyMap<String, String>()
                    }
                    "ttl" -> {
                        val key = args!![0] as String
                        ttls[key] ?: -1L
                    }
                    "expire" -> {
                        val key = args!![0] as String
                        val seconds = args[1] as Long
                        ttls[key] = seconds
                        true
                    }
                    // Recent-probes ring buffer: LPUSHX only appends when the key
                    // already exists, which it never does in these tests, so it is
                    // a no-op returning 0; LTRIM then returns OK.
                    "lpushx" -> 0L
                    "ltrim" -> "OK"
                    else -> throw UnsupportedOperationException("FakeRedis does not support ${method.name}")
                }
            } as RedisCommands<String, String>
        }
    }
}
