package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.agents.FleetAudience
import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.sql.SortOrder
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

        /** Path the agent is told to fetch its token from. */
        private const val TOKEN_PATH = "/internal/health/token"

        /**
         * Budget for the scheduler's own check of the token endpoint. Short: it
         * only runs when a challenge already failed, and the answer is only
         * needed to decide whether the failure can be blamed on the agent.
         */
        private const val TOKEN_PROBE_TIMEOUT_MS = 3_000L

        /** The only result that counts as a healthy round. */
        const val RESULT_PASS = "pass"

        /**
         * The round observed nothing about the agent — the token endpoint (or
         * the store behind it) was down for the scheduler too. Kept as history,
         * never as a verdict: it neither sets `last_status` nor counts toward
         * the consecutive-failure total.
         */
        const val RESULT_INCONCLUSIVE = "inconclusive"

        /**
         * Consecutive non-pass rounds required before an agent is marked
         * `failure`. Recovery is immediate on the first pass — fail slow,
         * recover fast, so a single blip does not empty the fleet and a
         * genuine outage is still caught inside two minutes.
         */
        const val FAILURE_THRESHOLD = 2

        /**
         * Decides what `probe_agents.last_status` should become, given this
         * round's [result] and the [priorResult] of the last round that
         * observed anything (inconclusive rounds excluded). `null` means leave
         * the current status alone.
         *
         * Pure so the hysteresis can be tested without a database.
         */
        fun nextStatus(result: String, priorResult: String?): String? = when {
            // Recover fast: one good round is enough to put an agent back.
            result == RESULT_PASS -> "success"
            // Fail slow: a single non-pass round is a blip, not a verdict.
            // With FAILURE_THRESHOLD = 2 the previous round has to have been
            // non-pass as well. No prior round at all counts as "not yet".
            priorResult != null && priorResult != RESULT_PASS -> "failure"
            else -> null
        }

        /**
         * Stable alert subject for the token endpoint: the per-challenge id is
         * deliberately left off, or every minute would open a new alert episode
         * instead of refreshing the one that is already showing.
         */
        fun tokenEndpointSubject(gatewayUrl: String): String =
            "$gatewayUrl$TOKEN_PATH".take(128)

        /**
         * Plain (non-mTLS) client used only to ask the gateway whether it is
         * serving health tokens. Shared: Quartz builds a fresh job instance per
         * fire, and a per-instance client would leak a connection pool a minute.
         */
        private val tokenProbeClient: HttpClient by lazy { HttpClient(CIO) }
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
        val tokenUrl = "$gatewayUrl$TOKEN_PATH/$challengeId"

        // Store token in Redis A. Guarded rather than bare: this used to throw
        // straight out of the round, abandoning every other agent's challenge
        // along with this one.
        try {
            redis.set("health:token:$challengeId", token, SetArgs().ex(TOKEN_TTL_SECONDS))
        } catch (e: Exception) {
            // The agent was never contacted, so this round learned nothing
            // about it. Blaming it here would take the whole fleet out of
            // rotation over a store the agents do not even talk to.
            log.warn("health challenge for agent {} could not store its token: {}", agent.slug, e.message)
            recordInconclusive(
                agent = agent,
                challengeId = challengeId,
                challengedAt = challengedAt,
                respondedAt = null,
                roundTripMs = null,
                gatewayUrl = gatewayUrl,
                stage = "token_store",
                detail = e.message,
            )
            return
        }

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
                returnedToken == token -> RESULT_PASS
                else -> "wrong_token"
            }

            // The agent answered and said it could not complete the challenge.
            // Passing requires it to fetch a token from the gateway, so before
            // that is held against it, check whether the endpoint answers the
            // scheduler either. If it does not, the failure belongs to the
            // platform: every agent would otherwise be convicted in the same
            // round and dispatch would find nothing left to run on.
            if (!success && !tokenEndpointReachable(tokenUrl)) {
                // The agent's own explanation — reported all along, never read.
                val agentError = body["error"]?.jsonPrimitive?.contentOrNull
                log.warn(
                    "health challenge for agent {} is inconclusive: {} is unreachable from the scheduler too (agent reported: {})",
                    agent.slug, tokenEndpointSubject(gatewayUrl), agentError ?: "no detail",
                )
                recordInconclusive(
                    agent = agent,
                    challengeId = challengeId,
                    challengedAt = challengedAt,
                    respondedAt = respondedAt,
                    roundTripMs = roundTripMs,
                    gatewayUrl = gatewayUrl,
                    stage = "token_endpoint",
                    detail = agentError,
                )
                return
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

    /**
     * Asks the gateway whether it is serving health tokens at all.
     *
     * Reading a token does not delete it (the endpoint just reads the key,
     * which lives out its 30 s TTL), so this second GET does not consume the
     * one the agent was sent. Only a 200 counts: a 404 or a 5xx means the token
     * path is not serving, which is equally not the agent's doing.
     */
    private suspend fun tokenEndpointReachable(tokenUrl: String): Boolean = try {
        withTimeout(TOKEN_PROBE_TIMEOUT_MS) {
            tokenProbeClient.get(tokenUrl).status.value == 200
        }
    } catch (e: Exception) {
        log.debug("health token endpoint unreachable from the scheduler: {}", e.message)
        false
    }

    /**
     * Records a round that observed nothing about the agent.
     *
     * The history row is kept — an operator looking at agent health should see
     * that a check was attempted — but `probe_agents` is left completely
     * untouched, so `last_status` survives and the agent stays in rotation. The
     * alert names the endpoint that was down, not the agent that could not
     * reach it.
     */
    private fun recordInconclusive(
        agent: AgentInfo,
        challengeId: String,
        challengedAt: Instant,
        respondedAt: Instant?,
        roundTripMs: Int?,
        gatewayUrl: String,
        stage: String,
        detail: String?,
    ) {
        try {
            transaction {
                AgentHealthChecks.insert {
                    it[id] = UUID.randomUUID()
                    it[probeAgentId] = agent.id
                    it[AgentHealthChecks.challengeId] = challengeId
                    it[AgentHealthChecks.challengedAt] = challengedAt
                    it[AgentHealthChecks.respondedAt] = respondedAt
                    it[AgentHealthChecks.roundTripMs] = roundTripMs
                    it[AgentHealthChecks.result] = RESULT_INCONCLUSIVE
                    it[createdAt] = Instant.now()
                }
            }
        } catch (e: Exception) {
            log.debug("failed to record inconclusive health check for {}: {}", agent.slug, e.message)
        }

        raisePlatformAgentAlert(
            SystemAlertService.HEALTH_TOKEN_UNAVAILABLE,
            tokenEndpointSubject(gatewayUrl),
            "error",
            buildJsonObject {
                put("endpoint", tokenEndpointSubject(gatewayUrl))
                put("stage", stage)
                put("at", challengedAt.toString())
                put("agentSlug", agent.slug)
                if (detail != null) put("detail", detail)
            },
        )
    }

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
        // The status actually in force after this round — either what we wrote
        // or, while hysteresis is holding, whatever was there already.
        var status = "success"
        var convicted = false

        transaction {
            // The previous round that observed anything, read in the same
            // transaction as the insert so two overlapping rounds cannot both
            // read "first failure" and both decline to convict. Inconclusive
            // rows are skipped: they are not evidence either way.
            val priorResult = AgentHealthChecks.selectAll()
                .where {
                    (AgentHealthChecks.probeAgentId eq agentId) and
                        (AgentHealthChecks.result neq RESULT_INCONCLUSIVE)
                }
                .orderBy(AgentHealthChecks.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
                ?.get(AgentHealthChecks.result)

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

            val newStatus = nextStatus(result, priorResult)
            convicted = newStatus == "failure"

            ProbeAgents.update({ ProbeAgents.id eq agentId }) {
                it[lastPing] = challengedAt
                // Null means hysteresis is holding: the observation fields are
                // still current, but one non-pass round does not change the
                // verdict — and it is the verdict that decides whether the
                // agent stays eligible for dispatch.
                if (newStatus != null) it[lastStatus] = newStatus
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = roundTripMs
                if (supportsSealed != null) it[supportsEncryptedPayload] = supportsSealed
            }

            status = newStatus
                ?: ProbeAgents.selectAll()
                    .where { ProbeAgents.id eq agentId }
                    .firstOrNull()
                    ?.get(ProbeAgents.lastStatus)
                ?: "success"

            if (newStatus == null) {
                log.info(
                    "health challenge for agent {} returned {} — holding at {} pending {} consecutive failures",
                    agentSlug, result, status, FAILURE_THRESHOLD,
                )
            }
        }

        // Publish to both summary (always subscribed) and detail (dropdown open) channels
        val eventData = buildJsonObject {
            put("agentSlug", agentSlug)
            put("status", status)
            put("lastCheck", respondedAt.toString())
            put("lastResponseMs", roundTripMs)
        }
        FleetAudience.publish(agentSlug, "health.updated", eventData)

        // Admin banner on agent trouble. Agents are platform-global, so absent a
        // router the alert goes to every org (typically a single org, self-hosted).
        // A host that operates shared agents can intercept it — its infra health is
        // not each customer's concern — via the [SystemAlertRouting] seam.
        // Raised on conviction, not on the first blip: the banner and
        // `probe_agents.last_status` should never disagree about whether an
        // agent is down.
        if (convicted) {
            raisePlatformAgentAlert(SystemAlertService.AGENT_DOWN, agentSlug, "error", buildJsonObject {
                put("agentSlug", agentSlug)
                put("at", challengedAt.toString())
                put("result", result)
            })
        } else if (result == RESULT_PASS && roundTripMs > SystemAlertService.DEGRADED_RTT_MS) {
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
