package dev.tracedown.metrics.listeners

import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.metrics.cache.MetricsWriter
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Subscribes to the "notify:nudge" pub/sub channel on Redis A.
 *
 * On each nudge (published by result-ingestor after persisting a probe result),
 * parses the JSON payload and updates metric counters in Redis B via [MetricsWriter].
 */
class NudgeListener(
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
    private val metricsWriter: MetricsWriter,
    private val scope: CoroutineScope,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Per-workspace accumulator flushed as one `metrics.delta` realtime event
     * every [DELTA_FLUSH_MS]. Workspace views fold the delta into their local
     * hourly buckets — one event per interval regardless of fleet size,
     * instead of per-probe events on the workspace channel.
     */
    private open class DeltaCounters {
        var total: Long = 0; var success: Long = 0; var failure: Long = 0
        var timeout: Long = 0; var error: Long = 0; var sumMs: Long = 0; var callCount: Long = 0

        fun record(status: String, responseMs: Int, calls: Int) {
            total++
            when (status) {
                "success" -> success++
                "failure" -> failure++
                "timeout" -> timeout++
                // A run that did not evaluate (agent fault, executor fault).
                // Counted on its own so a delta made only of these does not
                // read as all-green to a card that infers status from the
                // failure and timeout counts alone.
                "error" -> error++
            }
            sumMs += responseMs
            callCount += calls
        }
    }

    private class WsDelta : DeltaCounters() {
        /** Per-project breakdown so workspace views can tick project cards. */
        val projects = HashMap<String, DeltaCounters>()
    }

    private val wsDeltas = java.util.concurrent.ConcurrentHashMap<Pair<UUID, UUID>, WsDelta>()

    /** Subscribes to the nudge channel and starts the delta flusher. */
    fun start() {
        pubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String?, message: String?) {
                if (message.isNullOrBlank()) return
                scope.launch { handleNudge(message) }
            }
        })
        pubSubConnection.sync().subscribe("notify:nudge")
        log.info("nudge listener subscribed to notify:nudge")

        scope.launch {
            while (isActive) {
                delay(DELTA_FLUSH_MS)
                flushDeltas()
            }
        }
    }

    private fun flushDeltas() {
        val keys = wsDeltas.keys.toList()
        for (key in keys) {
            val delta = wsDeltas.remove(key) ?: continue
            val (orgId, workspaceId) = key
            val payload = synchronized(delta) {
                buildJsonObject {
                    put("total", delta.total)
                    put("success", delta.success)
                    put("failure", delta.failure)
                    put("timeout", delta.timeout)
                    put("error", delta.error)
                    put("sumMs", delta.sumMs)
                    put("callCount", delta.callCount)
                    put("projects", buildJsonObject {
                        for ((projId, p) in delta.projects) {
                            put(projId, buildJsonObject {
                                put("total", p.total)
                                put("success", p.success)
                                put("failure", p.failure)
                                put("timeout", p.timeout)
                                put("error", p.error)
                                put("sumMs", p.sumMs)
                                put("callCount", p.callCount)
                            })
                        }
                    })
                }
            }
            RealtimePublisher.publish("workspace:$workspaceId", orgId, "metrics.delta", payload)
        }
    }

    /** Unsubscribes from the nudge channel. */
    fun stop() {
        try {
            pubSubConnection.sync().unsubscribe("notify:nudge")
        } catch (_: Exception) {
            // best-effort cleanup
        }
    }

    private fun handleNudge(message: String) {
        try {
            val json = Json.parseToJsonElement(message).jsonObject
            val serviceId = UUID.fromString(json["serviceId"]?.jsonPrimitive?.content ?: return)
            val status = json["status"]?.jsonPrimitive?.content ?: return
            val responseMs = json["totalResponseMs"]?.jsonPrimitive?.intOrNull ?: 0
            val callCount = json["callCount"]?.jsonPrimitive?.intOrNull ?: 1
            val failedCalls = json["failedCalls"]?.jsonPrimitive?.intOrNull ?: 0

            metricsWriter.record(serviceId, status, responseMs, callCount, failedCalls)

            // Usage counters (requests + measured HTTP-layer bytes) at every scope.
            json["orgId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let { orgStr ->
                metricsWriter.recordUsage(
                    orgId = UUID.fromString(orgStr),
                    workspaceId = json["workspaceId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                    projectId = json["projectId"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }?.let(UUID::fromString),
                    serviceId = serviceId,
                    requests = callCount,
                    ingressBytes = json["ingressBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    egressBytes = json["egressBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                    agentEgressBytes = json["agentEgressBytes"]?.jsonPrimitive?.longOrNull ?: 0L,
                )
            }

            // Publish realtime events for WebSocket clients
            val orgId = json["orgId"]?.jsonPrimitive?.content
            val projectId = json["projectId"]?.jsonPrimitive?.content
            if (orgId != null) {
                val orgUuid = UUID.fromString(orgId)

                // Accumulate the workspace roll-up (flushed as metrics.delta)
                json["workspaceId"]?.jsonPrimitive?.content
                    ?.takeIf { it.isNotBlank() }
                    ?.let { wsId ->
                        val delta = wsDeltas.getOrPut(orgUuid to UUID.fromString(wsId)) { WsDelta() }
                        synchronized(delta) {
                            delta.record(status, responseMs, callCount)
                            if (projectId != null) {
                                delta.projects.getOrPut(projectId) { DeltaCounters() }
                                    .record(status, responseMs, callCount)
                            }
                        }
                    }
                val avgMs = if (callCount > 0) responseMs / callCount else responseMs
                val eventData = buildJsonObject {
                    put("serviceId", serviceId.toString())
                    put("status", status)
                    put("avgResponseMs", avgMs)
                    put("callCount", callCount)
                    put("failedCalls", failedCalls)
                    put("timestamp", System.currentTimeMillis() / 1000)
                    // Live failure-preview detail, when the ingestor sent it
                    json["failedAssertions"]?.let { put("failedAssertions", it) }
                }

                // Project channel — dashboard updates (counters, status, category)
                if (projectId != null) {
                    RealtimePublisher.publish("project:$projectId", orgUuid, "probe.completed", eventData)
                }

                // Service channel — expanded card updates (recent probes)
                RealtimePublisher.publish("service:$serviceId", orgUuid, "probe.completed", eventData)
            }
        } catch (e: Exception) {
            log.warn("failed to process nudge: {}", e.message)
        }
    }

    private companion object {
        const val DELTA_FLUSH_MS = 3_000L
    }
}
