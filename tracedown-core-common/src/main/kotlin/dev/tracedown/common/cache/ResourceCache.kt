package dev.tracedown.common.cache

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Cached resource hierarchy entry.
 * Stores the full parent chain so any resource ID can be resolved
 * to its org/workspace/project without DB lookups.
 */
@Serializable
data class CachedServiceContext(
    val serviceId: String,
    val projectId: String,
    val workspaceId: String,
    val organizationId: String,
)

@Serializable
data class CachedProjectContext(
    val projectId: String,
    val workspaceId: String,
    val organizationId: String,
)

/**
 * Redis C-backed cache for resource hierarchy resolution.
 *
 * Cache-first, DB-fallback. When Redis C is not configured, all operations
 * are no-ops and the caller falls through to DB queries.
 *
 * Keys:
 * - `res:svc:{serviceId}` → CachedServiceContext JSON
 * - `res:proj:{projectId}` → CachedProjectContext JSON
 */
class ResourceCache(
    /**
     * Provider rather than a connection: the cache is optional, so nothing
     * about constructing it should force a connect. The gateway holds Redis C
     * behind `by lazy` and an eager connect here meant an unreachable cache
     * stopped the whole API from starting.
     */
    private val redisProvider: (() -> RedisCommands<String, String>)?,
    private val ttlSeconds: Long,
) {

    /** Convenience for callers (and tests) that already hold a connection. */
    constructor(redis: RedisCommands<String, String>, ttlSeconds: Long) : this({ redis }, ttlSeconds)

    private val log = LoggerFactory.getLogger(javaClass)

    val enabled: Boolean get() = redisProvider != null

    /**
     * Resolves the connection, treating an unreachable cache exactly like a
     * disabled one — the caller falls through to the database either way.
     */
    private fun conn(): RedisCommands<String, String>? = try {
        redisProvider?.invoke()
    } catch (e: Exception) {
        log.debug("resource cache unavailable: {}", e.message)
        null
    }

    // ── Service ──

    /** Returns cached service context, or null on miss/disabled. */
    fun getService(serviceId: UUID): CachedServiceContext? {
        val redis = conn() ?: return null
        return try {
            val key = "res:svc:$serviceId"
            val value = redis.get(key) ?: return null
            // Reset TTL on read
            redis.expire(key, ttlSeconds)
            Json.decodeFromString<CachedServiceContext>(value)
        } catch (e: Exception) {
            log.debug("cache read failed for service {}: {}", serviceId, e.message)
            null
        }
    }

    /** Caches a service context. */
    fun putService(ctx: CachedServiceContext) {
        val redis = conn() ?: return
        try {
            val key = "res:svc:${ctx.serviceId}"
            redis.setex(key, ttlSeconds, Json.encodeToString(CachedServiceContext.serializer(), ctx))
        } catch (e: Exception) {
            log.debug("cache write failed for service {}: {}", ctx.serviceId, e.message)
        }
    }

    /** Invalidates a cached service entry. */
    fun invalidateService(serviceId: UUID) {
        val redis = conn() ?: return
        try {
            redis.del("res:svc:$serviceId")
        } catch (e: Exception) {
            log.debug("cache invalidate failed for service {}: {}", serviceId, e.message)
        }
    }

    // ── Project ──

    /** Returns cached project context, or null on miss/disabled. */
    fun getProject(projectId: UUID): CachedProjectContext? {
        val redis = conn() ?: return null
        return try {
            val key = "res:proj:$projectId"
            val value = redis.get(key) ?: return null
            redis.expire(key, ttlSeconds)
            Json.decodeFromString<CachedProjectContext>(value)
        } catch (e: Exception) {
            log.debug("cache read failed for project {}: {}", projectId, e.message)
            null
        }
    }

    /** Caches a project context. */
    fun putProject(ctx: CachedProjectContext) {
        val redis = conn() ?: return
        try {
            val key = "res:proj:${ctx.projectId}"
            redis.setex(key, ttlSeconds, Json.encodeToString(CachedProjectContext.serializer(), ctx))
        } catch (e: Exception) {
            log.debug("cache write failed for project {}: {}", ctx.projectId, e.message)
        }
    }

    /** Invalidates a cached project entry. */
    fun invalidateProject(projectId: UUID) {
        val redis = conn() ?: return
        try {
            redis.del("res:proj:$projectId")
        } catch (e: Exception) {
            log.debug("cache invalidate failed for project {}: {}", projectId, e.message)
        }
    }

    companion object {
        /** Creates a disabled (no-op) cache for deployments without Redis C. */
        val DISABLED = ResourceCache(null, 0)
    }
}
