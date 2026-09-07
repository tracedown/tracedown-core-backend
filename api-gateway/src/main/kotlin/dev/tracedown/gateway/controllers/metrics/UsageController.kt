package dev.tracedown.gateway.controllers.metrics

import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.gateway.data.UsageResponse
import io.lettuce.core.api.sync.RedisCommands
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Serves resource usage (requests + measured ingress/egress bytes + bytes
 * dispatched to probe agents) by summing
 * the per-level hourly usage buckets the metrics-service writes to Redis B
 * (`metrics:usage:{svc|proj|ws|org}:{id}:h:{yyyyMMddHH}`). Buckets are
 * immutable once their hour passes; the window is capped to the shorter of the
 * request, 7 days, and the probe-result retention period — the organization's
 * own where the platform sets one per organization (the [RetentionConfig]
 * seam), else the global value.
 */
object UsageController {

    const val MAX_WINDOW_HOURS = 7 * 24
    const val MIN_WINDOW_HOURS = 2

    private lateinit var redisProvider: () -> RedisCommands<String, String>
    private var globalRetentionHours: Int = MAX_WINDOW_HOURS
    private val redis get() = redisProvider()

    private val hourFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

    fun init(redis: () -> RedisCommands<String, String>, resultRetentionDays: Int) {
        this.redisProvider = redis
        this.globalRetentionHours = if (resultRetentionDays > 0) resultRetentionDays * 24 else MAX_WINDOW_HOURS
    }

    /** The retention cap for [orgId]: its own period where the platform sets one, else the global. */
    private fun retentionHours(orgId: UUID): Int {
        val days = PlatformDefaults.retentionConfig.resultRetentionDays(orgId)
        return if (days > 0) days * 24 else globalRetentionHours
    }

    fun forService(orgId: UUID, serviceId: UUID, requestedHours: Int): UsageResponse = usage("svc", serviceId, requestedHours, orgId)
    fun forProject(orgId: UUID, projectId: UUID, requestedHours: Int): UsageResponse = usage("proj", projectId, requestedHours, orgId)
    fun forWorkspace(orgId: UUID, workspaceId: UUID, requestedHours: Int): UsageResponse = usage("ws", workspaceId, requestedHours, orgId)
    fun forOrg(orgId: UUID, requestedHours: Int): UsageResponse = usage("org", orgId, requestedHours, orgId)

    private fun usage(level: String, id: UUID, requestedHours: Int, orgId: UUID): UsageResponse {
        val hours = requestedHours
            .coerceIn(MIN_WINDOW_HOURS, MAX_WINDOW_HOURS)
            .coerceAtMost(retentionHours(orgId))
            .coerceAtLeast(1)

        val now = Instant.now()
        // Pipeline the per-hour HGETALLs via the async view — a 7-day window is
        // 168 keys, and the async commands batch on one round trip.
        val async = redis.statefulConnection.async()
        val futures = (hours - 1 downTo 0).map { back ->
            val bucket = hourFormatter.format(now.minusSeconds(back * 3600L))
            async.hgetall("metrics:usage:$level:$id:h:$bucket")
        }

        var requests = 0L
        var ingress = 0L
        var egress = 0L
        var agentEgress = 0L
        for (future in futures) {
            val fields = future.get()
            requests += fields["requests"]?.toLongOrNull() ?: 0L
            ingress += fields["ingress"]?.toLongOrNull() ?: 0L
            egress += fields["egress"]?.toLongOrNull() ?: 0L
            agentEgress += fields["agent_egress"]?.toLongOrNull() ?: 0L
        }
        return UsageResponse(
            windowHours = hours,
            requests = requests,
            ingressBytes = ingress,
            egressBytes = egress,
            agentEgressBytes = agentEgress,
        )
    }
}
