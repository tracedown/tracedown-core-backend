package dev.tracedown.scheduler.results

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Publishes probe results to the Redis queue for the result-ingestor.
 *
 * Each envelope carries a `resultId` minted here, once, before it is queued.
 * That id becomes the `probe_results` primary key, which is what makes the
 * ingestor's at-least-once delivery safe: a redelivered envelope carries the
 * same id and the second insert is refused by the key rather than duplicating
 * the run. A `jobId` may cover several envelopes (one per agent in
 * `simultaneous` probe mode), so it is not that identity — `resultId` is.
 *
 * Each envelope also carries `startedAt`: when the run this result describes
 * actually began, stamped here by the scheduler. Without it the ingestor had
 * only its own clock, so under a queue backlog every result was filed at the
 * time it was *read* rather than the time it was *taken* — wrong bucket, wrong
 * status-since marker, downtime inflated by the depth of the backlog. Being a
 * property of the message also makes it stable across the ingestor's
 * at-least-once redelivery: two deliveries of one run cannot disagree about
 * when it happened.
 */
class ResultPublisher(private val redis: RedisCommands<String, String>) {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val QUEUE_KEY = "probe_results_queue"
    }

    /**
     * Publishes a probe result to the Redis queue.
     *
     * @return the result id assigned to this envelope — the row it will become
     * @param jobId identifier for this probe run; shared by every envelope of a
     *   run that fanned out across agents, so it is not a per-result identity
     * @param serviceId the service that was probed
     * @param agentId the agent that executed the probe (null for skipped probes)
     * @param projectId the service's project
     * @param workspaceId the service's workspace
     * @param organizationId the service's organization
     * @param rawResult the raw ProbeResult from the agent (or synthetic error/skip)
     * @param agentEgressBytes UTF-8 bytes of the request body sent to the agent
     *   for this dispatch (0 when nothing was dispatched)
     * @param startedAt when this run began — the instant the scheduler handed
     *   the script to the executor, or the instant a tick was shed. Never the
     *   time the envelope is built downstream of that.
     */
    fun publish(
        jobId: UUID,
        serviceId: UUID,
        agentId: Long?,
        projectId: UUID,
        workspaceId: UUID,
        organizationId: UUID,
        rawResult: JsonObject,
        startedAt: Instant,
        agentEgressBytes: Long = 0L,
    ): UUID {
        // Minted before the push, not after the pop: the id has to be a property
        // of the message so that every delivery of it — including one the
        // ingestor is handed a second time after a crash — resolves to the same
        // row.
        val resultId = UUID.randomUUID()
        val envelope = buildJsonObject {
            put("resultId", resultId.toString())
            put("jobId", jobId.toString())
            put("serviceId", serviceId.toString())
            if (agentId != null) put("probeAgentId", agentId)
            put("projectId", projectId.toString())
            put("workspaceId", workspaceId.toString())
            put("organizationId", organizationId.toString())
            put("rawResult", rawResult)
            put("startedAt", startedAt.toString())
            put("agentEgressBytes", agentEgressBytes)
        }

        redis.lpush(QUEUE_KEY, envelope.toString())
        log.debug("published result {} for service {} agent {}", resultId, serviceId, agentId)
        return resultId
    }
}
