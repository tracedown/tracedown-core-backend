package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * Quartz job that runs health challenges against all active agents.
 *
 * Fires every 1 minute. For each active agent:
 * 1. Generate a challengeId and one-time token
 * 2. Store token in Redis A with 30s TTL
 * 3. POST to agent's /health/challenge with {challengeId, tokenUrl}
 * 4. Agent runs a Lace script to fetch token from gateway, returns it
 * 5. Validate returned token, record result
 */
class HealthChallengeJob : Job {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TOKEN_BYTES = 32
        private const val TOKEN_TTL_SECONDS = 30L
        private const val CHALLENGE_TIMEOUT_MS = 10_000L
    }

    override fun execute(context: JobExecutionContext) {
        val redis = HealthChallengeContext.redis
        val gatewayUrl = HealthChallengeContext.gatewayUrl
        val clientFactory = HealthChallengeContext.clientFactory

        val agents = transaction {
            ProbeAgents.selectAll()
                .where { ProbeAgents.isActive eq true }
                .map { row ->
                    AgentInfo(
                        id = row[ProbeAgents.id],
                        slug = row[ProbeAgents.slug],
                        uri = row[ProbeAgents.agentUri],
                    )
                }
        }

        if (agents.isEmpty()) return

        runBlocking {
            agents.map { agent ->
                async {
                    challengeAgent(agent, redis, gatewayUrl, clientFactory)
                }
            }.awaitAll()
        }
    }

    private suspend fun challengeAgent(
        agent: AgentInfo,
        redis: RedisCommands<String, String>,
        gatewayUrl: String,
        clientFactory: AgentMtlsClientFactory,
    ) {
        val challengeId = generateHex(32)
        val token = generateHex(TOKEN_BYTES)
        val challengedAt = Instant.now()

        // Store token in Redis A
        redis.set("health:token:$challengeId", token, SetArgs().ex(TOKEN_TTL_SECONDS))

        val tokenUrl = "$gatewayUrl/internal/health/token/$challengeId"

        try {
            // Pin the challenge to this agent's certificate identity — a health
            // probe must reach the agent it claims to, not a peer answering for it.
            val httpClient = clientFactory.client(agent.slug)
            val response = withTimeout(CHALLENGE_TIMEOUT_MS) {
                httpClient.post("${agent.uri}/health/challenge") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"challenge_id":"$challengeId","token_url":"$tokenUrl"}""")
                }
            }

            val respondedAt = Instant.now()
            val roundTripMs = (respondedAt.toEpochMilli() - challengedAt.toEpochMilli()).toInt()
            val body = Json.decodeFromString<JsonObject>(response.bodyAsText())

            val success = body["success"]?.jsonPrimitive?.boolean ?: false
            val returnedToken = body["token"]?.jsonPrimitive?.content
            // Capability, not configuration: an agent that cannot open a sealed
            // dispatch says so here, and the sealing decision refuses to act on
            // the operator's toggle without it. Absent (an older agent that does
            // not send the field) reads as false, which is the safe direction.
            val supportsSealed = body["payloadEncryption"]?.jsonPrimitive?.boolean
                ?: body["payload_encryption"]?.jsonPrimitive?.boolean ?: false

            val result = when {
                !success -> "fail"
                returnedToken == token -> "pass"
                else -> "wrong_token"
            }

            recordResult(agent.id, agent.slug, challengeId, challengedAt, respondedAt, roundTripMs, result, supportsSealed)
            log.debug("health challenge {} for agent {}: {}", challengeId, agent.slug, result)
        } catch (e: Exception) {
            val respondedAt = Instant.now()
            val roundTripMs = (respondedAt.toEpochMilli() - challengedAt.toEpochMilli()).toInt()
            val result = if (e is kotlinx.coroutines.TimeoutCancellationException) "timeout" else "fail"

            // No answer came back, so nothing was learned about capability —
            // null leaves the last observation in place rather than clearing it
            // on a timeout.
            recordResult(agent.id, agent.slug, challengeId, challengedAt, respondedAt, roundTripMs, result, null)
            log.warn("health challenge for agent {} failed: {}", agent.slug, e.message)
        }
    }

    /** Global UUID used for agent health events (agents are not org-scoped). */
    private val GLOBAL_ORG = UUID(0, 0)

    private fun recordResult(
        agentId: Long,
        agentSlug: String,
        challengeId: String,
        challengedAt: Instant,
        respondedAt: Instant,
        roundTripMs: Int,
        result: String,
        supportsSealed: Boolean?,
    ) {
        val status = if (result == "pass") "success" else "failure"
        transaction {
            AgentHealthChecks.insert {
                it[id] = UUID.randomUUID()
                it[probeAgentId] = agentId
                it[AgentHealthChecks.challengeId] = challengeId
                it[AgentHealthChecks.challengedAt] = challengedAt
                it[AgentHealthChecks.respondedAt] = respondedAt
                it[AgentHealthChecks.roundTripMs] = roundTripMs
                it[AgentHealthChecks.result] = result
                it[createdAt] = Instant.now()
            }

            ProbeAgents.update({ ProbeAgents.id eq agentId }) {
                it[lastPing] = challengedAt
                it[lastStatus] = status
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = roundTripMs
                if (supportsSealed != null) it[supportsEncryptedPayload] = supportsSealed
            }
        }

        // Publish to both summary (always subscribed) and detail (dropdown open) channels
        val eventData = buildJsonObject {
            put("agentSlug", agentSlug)
            put("status", status)
            put("lastCheck", respondedAt.toString())
            put("lastResponseMs", roundTripMs)
        }
        RealtimePublisher.publish("agents:summary", GLOBAL_ORG, "health.updated", eventData)
        RealtimePublisher.publish("agents", GLOBAL_ORG, "health.updated", eventData)

        // Admin banner on agent trouble. Agents are platform-global, so absent a
        // router the alert goes to every org (typically a single org, self-hosted).
        // A host that operates shared agents can intercept it — its infra health is
        // not each customer's concern — via the [SystemAlertRouting] seam.
        if (result != "pass") {
            raisePlatformAgentAlert(SystemAlertService.AGENT_DOWN, agentSlug, "error", buildJsonObject {
                put("agentSlug", agentSlug)
                put("at", challengedAt.toString())
                put("result", result)
            })
        } else if (roundTripMs > SystemAlertService.DEGRADED_RTT_MS) {
            raisePlatformAgentAlert(SystemAlertService.AGENT_DEGRADED, agentSlug, "warning", buildJsonObject {
                put("agentSlug", agentSlug)
                put("at", challengedAt.toString())
                put("roundTripMs", roundTripMs)
            })
        }
    }

    /**
     * Offers a shared-agent health alert to the routing seam once; if unclaimed,
     * falls back to the historical per-org broadcast. Consulted once per
     * condition, not per org, so a host router logs/records it a single time.
     */
    private fun raisePlatformAgentAlert(
        alertType: String,
        agentSlug: String,
        severity: String,
        data: kotlinx.serialization.json.JsonObject,
    ) {
        val handled = SystemAlertRouting.handled(
            AlertContext(
                alertType = alertType,
                subject = agentSlug,
                orgId = null,
                orgScoped = false,
                severity = severity,
                data = data,
            )
        )
        if (!handled) raiseForAllOrgs(alertType, agentSlug, severity, data)
    }

    private fun raiseForAllOrgs(alertType: String, subject: String, severity: String, data: kotlinx.serialization.json.JsonObject) {
        val orgIds = try {
            transaction {
                Organizations.selectAll()
                    .where { Organizations.deleted eq false }
                    .map { it[Organizations.id] }
            }
        } catch (e: Exception) {
            log.debug("failed to load orgs for alert {}: {}", alertType, e.message)
            return
        }
        for (orgId in orgIds) {
            SystemAlertService.raise(orgId, alertType, subject, severity, data)
        }
    }

    private fun generateHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        SecureRandom().nextBytes(buf)
        return buf.joinToString("") { "%02x".format(it) }
    }

    private data class AgentInfo(val id: Long, val slug: String, val uri: String)
}
