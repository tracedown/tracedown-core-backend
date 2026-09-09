package dev.tracedown.metrics.cache

import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Writes probe metric data to Redis B.
 *
 * Maintains three key types per service:
 * - counters: all-time monotonic counters (for Prometheus)
 * - state: current status gauges
 * - hourly buckets: windowed counters (for frontend)
 *
 * TTL behavior:
 * - On write: TTL is NOT reset. If key is new (no TTL), initial TTL is set.
 * - On read: TTL is reset by [MetricsReader].
 */
class MetricsWriter(
    private val redisB: RedisCommands<String, String>,
    private val metricsTtlSeconds: Long,
    private val hourlyBucketTtlSeconds: Long,
    private val usageBucketTtlSeconds: Long,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val hourFormatter = DateTimeFormatter.ofPattern("yyyyMMddHH").withZone(ZoneOffset.UTC)

    companion object {
        /** Maximum number of recent-probe entries kept per service. */
        const val RECENT_PROBES_MAX_SIZE = 50L

        /**
         * Field the API sets on an hourly bucket it has recomputed from
         * probe_results once the hour closed. Redis B has no persistence, so a
         * bucket without it can have been cut short by a restart; a bucket with
         * it is final and this writer leaves it alone.
         */
        const val SEALED_FIELD = "sealed"
    }

    /**
     * Records a probe result in Redis B.
     *
     * @param serviceId the service that was probed
     * @param status the probe outcome (success, failure, timeout, error)
     * @param totalResponseMs sum of all calls' response times in milliseconds
     * @param callCount number of HTTP calls in the probe
     * @param failedCalls number of calls with at least one failed assertion
     */
    fun record(serviceId: UUID, status: String, totalResponseMs: Int, callCount: Int = 1, failedCalls: Int = 0) {
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"
        val hourKey = "metrics:svc:$serviceId:h:${currentHourBucket()}"

        // All-time counters
        redisB.hincrby(counterKey, "probes_total", 1)
        redisB.hincrby(counterKey, "probes_$status", 1)
        setInitialTtlIfNew(counterKey, metricsTtlSeconds)

        // Current state
        val prevStatus = redisB.hget(stateKey, "last_status")
        if (prevStatus == status) {
            redisB.hincrby(stateKey, "last_consecutive", 1)
        } else {
            redisB.hset(stateKey, "last_consecutive", "1")
        }
        val avgResponseMs = if (callCount > 0) totalResponseMs / callCount else totalResponseMs
        redisB.hset(stateKey, mapOf(
            "last_status" to status,
            "last_response_ms" to avgResponseMs.toString(),
            "last_run_at" to Instant.now().epochSecond.toString(),
        ))
        setInitialTtlIfNew(stateKey, metricsTtlSeconds)

        // Hourly bucket. A sealed bucket is SKIPPED, not merged: the seal means
        // the API already recomputed that hour from probe_results, which is
        // where this result is stored too, so incrementing would either
        // double-count it or — worse — leave a hand-counted hour that no longer
        // matches the durable data while still claiming to. This only ever
        // fires on the hour boundary (a result whose hour key was taken just
        // before the hour rolled over, landing after the API sealed it); the
        // row itself is already in probe_results either way.
        if (redisB.hget(hourKey, SEALED_FIELD) == null) {
            redisB.hincrby(hourKey, "total", 1)
            redisB.hincrby(hourKey, status, 1)
            redisB.hincrby(hourKey, "sum_ms", totalResponseMs.toLong())
            redisB.hincrby(hourKey, "call_count", callCount.toLong())
            setInitialTtlIfNew(hourKey, hourlyBucketTtlSeconds)
        } else {
            log.debug("hour bucket {} is sealed — leaving it to the durable data", hourKey)
        }

        // Recent probes: ring buffer of recent probe points.
        // LPUSHX only appends if key exists — the API endpoint creates the key
        // from DB on first access, so we don't create a partial list here.
        val recentProbesKey = "metrics:svc:$serviceId:recent-probes"
        val avgMs = if (callCount > 0) totalResponseMs / callCount else totalResponseMs
        val entry = "$status|$avgMs|$callCount|$failedCalls|${Instant.now().epochSecond}"
        val pushed = redisB.lpushx(recentProbesKey, entry)
        if (pushed > 0) {
            redisB.ltrim(recentProbesKey, 0, RECENT_PROBES_MAX_SIZE - 1)
        }

        log.debug("recorded metric for service={} status={} avgMs={} calls={}", serviceId, status, avgResponseMs, callCount)
    }

    /**
     * Records a probe's HTTP-layer usage into hourly usage buckets at every
     * level of the hierarchy (service → project → workspace → org), so a query
     * at any level is a sum over hours, not over child resources. Buckets are
     * append-only and immutable once the hour passes; the TTL just bounds
     * storage to the max queryable window.
     */
    fun recordUsage(
        orgId: UUID,
        workspaceId: UUID?,
        projectId: UUID?,
        serviceId: UUID,
        requests: Int,
        ingressBytes: Long,
        egressBytes: Long,
        agentEgressBytes: Long,
    ) {
        val hour = currentHourBucket()
        val targets = buildList {
            add("svc" to serviceId)
            projectId?.let { add("proj" to it) }
            workspaceId?.let { add("ws" to it) }
            add("org" to orgId)
        }
        for ((level, id) in targets) {
            val key = "metrics:usage:$level:$id:h:$hour"
            redisB.hincrby(key, "requests", requests.toLong())
            redisB.hincrby(key, "ingress", ingressBytes)
            redisB.hincrby(key, "egress", egressBytes)
            redisB.hincrby(key, "agent_egress", agentEgressBytes)
            setInitialTtlIfNew(key, usageBucketTtlSeconds)
        }
    }

    private fun currentHourBucket(): String = hourFormatter.format(Instant.now())

    private fun setInitialTtlIfNew(key: String, ttlSeconds: Long) {
        val ttl = redisB.ttl(key)
        if (ttl == -1L) {
            redisB.expire(key, ttlSeconds)
        }
    }
}
