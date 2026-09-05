package dev.tracedown.common.cache

import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import io.lettuce.core.codec.StringCodec
import io.lettuce.core.protocol.CommandArgs
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceCacheTest {

    // ── Disabled cache ──

    @Nested
    inner class DisabledCache {

        private val cache = ResourceCache.DISABLED

        @Test
        fun `enabled returns false`() {
            assertFalse(cache.enabled)
        }

        @Test
        fun `getService returns null`() {
            assertNull(cache.getService(UUID.randomUUID()))
        }

        @Test
        fun `putService is a no-op`() {
            // Should not throw
            cache.putService(CachedServiceContext(
                serviceId = UUID.randomUUID().toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            ))
        }

        @Test
        fun `invalidateService is a no-op`() {
            cache.invalidateService(UUID.randomUUID())
        }

        @Test
        fun `getProject returns null`() {
            assertNull(cache.getProject(UUID.randomUUID()))
        }

        @Test
        fun `putProject is a no-op`() {
            cache.putProject(CachedProjectContext(
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            ))
        }

        @Test
        fun `invalidateProject is a no-op`() {
            cache.invalidateProject(UUID.randomUUID())
        }
    }

    // ── Enabled cache ──

    @Nested
    inner class EnabledCache {

        private lateinit var redis: FakeRedis
        private lateinit var cache: ResourceCache

        @BeforeEach
        fun setUp() {
            redis = FakeRedis()
            cache = ResourceCache(redis.commands(), ttlSeconds = 3600L)
        }

        @Test
        fun `enabled returns true`() {
            assertTrue(cache.enabled)
        }

        @Test
        fun `putService then getService round-trips correctly`() {
            val serviceId = UUID.randomUUID()
            val ctx = CachedServiceContext(
                serviceId = serviceId.toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putService(ctx)
            val retrieved = cache.getService(serviceId)

            assertEquals(ctx, retrieved)
        }

        @Test
        fun `invalidateService removes the entry`() {
            val serviceId = UUID.randomUUID()
            val ctx = CachedServiceContext(
                serviceId = serviceId.toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putService(ctx)
            cache.invalidateService(serviceId)

            assertNull(cache.getService(serviceId))
        }

        @Test
        fun `putProject then getProject round-trips correctly`() {
            val projectId = UUID.randomUUID()
            val ctx = CachedProjectContext(
                projectId = projectId.toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putProject(ctx)
            val retrieved = cache.getProject(projectId)

            assertEquals(ctx, retrieved)
        }

        @Test
        fun `invalidateProject removes the entry`() {
            val projectId = UUID.randomUUID()
            val ctx = CachedProjectContext(
                projectId = projectId.toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putProject(ctx)
            cache.invalidateProject(projectId)

            assertNull(cache.getProject(projectId))
        }

        @Test
        fun `getService returns null on cache miss`() {
            assertNull(cache.getService(UUID.randomUUID()))
        }

        @Test
        fun `getProject returns null on cache miss`() {
            assertNull(cache.getProject(UUID.randomUUID()))
        }

        @Test
        fun `getService refreshes TTL on hit`() {
            val serviceId = UUID.randomUUID()
            val ctx = CachedServiceContext(
                serviceId = serviceId.toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putService(ctx)
            // Simulate TTL ticking down
            val key = "res:svc:$serviceId"
            redis.ttls[key] = 100L

            cache.getService(serviceId)

            // TTL should be refreshed to the configured value
            assertEquals(3600L, redis.ttls[key])
        }

        @Test
        fun `putService sets TTL via SET EX`() {
            val serviceId = UUID.randomUUID()
            val ctx = CachedServiceContext(
                serviceId = serviceId.toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            )

            cache.putService(ctx)
            val key = "res:svc:$serviceId"
            assertEquals(3600L, redis.ttls[key])
        }
    }

    // ── Redis failure handling ──

    @Nested
    inner class RedisFailureHandling {

        @Test
        fun `getService returns null when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            // Should not throw — returns null gracefully
            assertNull(cache.getService(UUID.randomUUID()))
        }

        @Test
        fun `putService does not throw when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            // Should not throw
            cache.putService(CachedServiceContext(
                serviceId = UUID.randomUUID().toString(),
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            ))
        }

        @Test
        fun `invalidateService does not throw when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            cache.invalidateService(UUID.randomUUID())
        }

        @Test
        fun `getProject returns null when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            assertNull(cache.getProject(UUID.randomUUID()))
        }

        @Test
        fun `putProject does not throw when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            cache.putProject(CachedProjectContext(
                projectId = UUID.randomUUID().toString(),
                workspaceId = UUID.randomUUID().toString(),
                organizationId = UUID.randomUUID().toString(),
            ))
        }

        @Test
        fun `invalidateProject does not throw when Redis throws`() {
            val brokenRedis = brokenCommands()
            val cache = ResourceCache(brokenRedis, ttlSeconds = 3600L)

            cache.invalidateProject(UUID.randomUUID())
        }

        /** Creates a RedisCommands proxy that throws on every method call. */
        @Suppress("UNCHECKED_CAST")
        private fun brokenCommands(): RedisCommands<String, String> {
            return Proxy.newProxyInstance(
                RedisCommands::class.java.classLoader,
                arrayOf(RedisCommands::class.java),
            ) { _, method: Method, _ ->
                throw RuntimeException("Redis connection lost: ${method.name}")
            } as RedisCommands<String, String>
        }
    }

    /**
     * Minimal fake Redis backed by in-memory maps. Implements only the methods
     * used by ResourceCache via a JDK dynamic proxy on RedisCommands.
     */
    class FakeRedis {
        val store: MutableMap<String, String> = mutableMapOf()
        val ttls: MutableMap<String, Long> = mutableMapOf()

        private companion object {
            val EX_SECONDS = Regex("\\bEX (\\d+)\\b")
        }

        @Suppress("UNCHECKED_CAST")
        fun commands(): RedisCommands<String, String> {
            return Proxy.newProxyInstance(
                RedisCommands::class.java.classLoader,
                arrayOf(RedisCommands::class.java),
            ) { _: Any, method: Method, args: Array<Any>? ->
                when (method.name) {
                    "get" -> {
                        val key = args!![0] as String
                        store[key]
                    }
                    "set" -> {
                        val key = args!![0] as String
                        val value = args[1] as String
                        store[key] = value
                        // SET key value EX <ttl>. SetArgs exposes no getter, so
                        // the expiry is read back off the rendered command
                        // ("EX 3600") rather than by assuming a layout.
                        val rendered = CommandArgs(StringCodec.UTF8)
                        (args[2] as SetArgs).build(rendered)
                        EX_SECONDS.find(rendered.toCommandString())
                            ?.let { ttls[key] = it.groupValues[1].toLong() }
                        "OK"
                    }
                    "del" -> {
                        // Lettuce del() takes vararg K... — JDK proxy receives it as args[0] = Array<String>
                        @Suppress("UNCHECKED_CAST")
                        val keys = args!![0] as Array<String>
                        var removed = 0L
                        for (key in keys) {
                            if (store.remove(key) != null) removed++
                            ttls.remove(key)
                        }
                        removed
                    }
                    "expire" -> {
                        val key = args!![0] as String
                        val seconds = args[1] as Long
                        if (store.containsKey(key)) {
                            ttls[key] = seconds
                            true
                        } else {
                            false
                        }
                    }
                    "ttl" -> {
                        val key = args!![0] as String
                        ttls[key] ?: -1L
                    }
                    else -> throw UnsupportedOperationException("FakeRedis does not support ${method.name}")
                }
            } as RedisCommands<String, String>
        }
    }
}
