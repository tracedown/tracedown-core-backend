package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.agents.DegradationRule
import dev.tracedown.common.agents.DegradationRule.Verdict
import dev.tracedown.common.agents.FleetAudience
import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import dev.tracedown.scheduler.dispatch.AgentLiveness
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.quartz.Job
import org.quartz.JobExecutionContext
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max

/**
 * Quartz job that runs health challenges against all active agents.
 *
 * Fires every 1 minute. For each active agent:
 * 1. Generate a challengeId and one-time token
 * 2. Store token in Redis A with 30s TTL
 * 3. POST to agent's /health/challenge with {challengeId, tokenUrl}
 * 4. Agent runs a Lace script to fetch token from gateway, returns it
 * 5. Validate returned token, record result
 *
 * The round is then settled as a whole. Every agent's row is written first;
 * only afterwards, with every outcome in hand, are alerts decided — because
 * one of the questions is whether the *round* was slow rather than any agent
 * in it, and that cannot be answered one agent at a time.
 */
class HealthChallengeJob : Job {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val TOKEN_BYTES = 32
        private const val TOKEN_TTL_SECONDS = 30L

        /**
         * The floor of the challenge budget. An agent with no history, or a
         * near one, gets exactly this.
         */
        const val CHALLENGE_TIMEOUT_MS = 10_000L

        /**
         * The ceiling. The token the agent fetches lives [TOKEN_TTL_SECONDS];
         * a budget that outlived it would time the agent out on a token that
         * had already expired and call that the agent's fault.
         */
        const val CHALLENGE_TIMEOUT_MAX_MS = 25_000L

        /**
         * How many of an agent's own typical round trips it is allowed before
         * the round is a timeout. Fixed at ten seconds, the budget was under
         * seven medians for an agent in Singapore, whose passing rounds were
         * observed out past seven seconds — and two of those in a row took it
         * out of rotation while it was running probes fine.
         */
        const val CHALLENGE_BUDGET_FACTOR = 10

        /** Path the agent is told to fetch its token from. */
        private const val TOKEN_PATH = "/internal/health/token"

        /**
         * Budget for the scheduler's own check of the token endpoint. Short: it
         * only runs when a challenge already failed, and the answer is only
         * needed to decide whether the failure can be blamed on the agent.
         */
        private const val TOKEN_PROBE_TIMEOUT_MS = 3_000L

        /** The only result that counts as a healthy round. */
        const val RESULT_PASS = DegradationRule.RESULT_PASS

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
         * round's [result], the [priorResult] of the last round that observed
         * anything (inconclusive rounds excluded), and whether the agent has
         * been [provenAlive] since that prior round — it answered a dispatched
         * run with a result of its own. `null` means leave the current status
         * alone.
         *
         * Two failed challenges convict only an agent that has also produced
         * nothing else in between. The challenge is one narrow path, and an
         * agent whose runs are coming back is not down whatever that path
         * says; convicting it would take a working agent out of rotation on
         * the word of the one check that happened to fail.
         *
         * Pure so the hysteresis can be tested without a database.
         */
        fun nextStatus(result: String, priorResult: String?, provenAlive: Boolean = false): String? = when {
            // Recover fast: one good round is enough to put an agent back.
            result == RESULT_PASS -> "success"
            // Fail slow: a single non-pass round is a blip, not a verdict.
            // With FAILURE_THRESHOLD = 2 the previous round has to have been
            // non-pass as well. No prior round at all counts as "not yet".
            priorResult != null && priorResult != RESULT_PASS && !provenAlive -> "failure"
            else -> null
        }

        /**
         * How long this round waits for an agent whose recent passing rounds
         * have median [baselineMs] (null when there are too few to say):
         * [CHALLENGE_BUDGET_FACTOR] medians, never under [CHALLENGE_TIMEOUT_MS]
         * and never over [CHALLENGE_TIMEOUT_MAX_MS].
         */
        fun challengeBudgetMs(baselineMs: Int?): Long {
            val scaled = (baselineMs ?: 0).toLong() * CHALLENGE_BUDGET_FACTOR
            return scaled.coerceIn(CHALLENGE_TIMEOUT_MS, CHALLENGE_TIMEOUT_MAX_MS)
        }

        /**
         * Whether a round in which [slow] of [judged] passing agents came back
         * above their own threshold is the platform's doing rather than any
         * agent's: at least two agents, and at least half of them, in the
         * same minute. Agents on different continents do not all slow down at
         * once on their own.
         */
        fun isPlatformRound(judged: Int, slow: Int): Boolean =
            judged >= 2 && slow >= max(2, ceil(judged / 2.0).toInt())

        /**
         * Stable alert subject for the token endpoint: the per-challenge id is
         * deliberately left off, or every minute would open a new alert episode
         * instead of refreshing the one that is already showing.
         */
        fun tokenEndpointSubject(gatewayUrl: String): String =
            "$gatewayUrl$TOKEN_PATH".take(128)

        /**
         * The alerts one settled round raises, given every agent's [outcomes]
         * and whether the round as a whole was slow ([platformRound]). Pure:
         * this is the whole alerting policy, and it is what the tests pin.
         *
         * - Down and degraded are raised on every round they hold, marked
         *   recurring after the first, so an org banner keeps refreshing while
         *   an operator feed records the episode once.
         * - Recovery is raised once, at the transition, and only to the
         *   routing seam: it closes a feed entry and is not a banner.
         * - In a platform round no agent is called degraded for it. The round
         *   itself is the subject, raised by the caller.
         */
        fun decide(outcomes: List<RoundOutcome>, platformRound: Boolean): List<Raise> {
            val raises = mutableListOf<Raise>()
            for (o in outcomes) {
                val slug = o.agent.slug
                val wasDown = o.statusBefore == "failure"
                if (o.statusAfter == "failure") {
                    raises += Raise(SystemAlertService.AGENT_DOWN, slug, "error", recurring = wasDown, data = buildJsonObject {
                        put("agentSlug", slug)
                        put("at", o.challengedAt.toString())
                        put("result", o.result)
                    })
                } else if (wasDown) {
                    raises += Raise(SystemAlertService.AGENT_RECOVERED, slug, "info", broadcast = false, data = buildJsonObject {
                        put("agentSlug", slug)
                        put("at", o.challengedAt.toString())
                        put("from", "down")
                    })
                }

                // Only a passing round is judged for slowness; on any other
                // result the verdict is the carried-over reading of the last
                // passing one, which was already dealt with when it happened.
                if (o.result != RESULT_PASS) continue
                if (o.verdict.degraded && !platformRound) {
                    raises += Raise(SystemAlertService.AGENT_DEGRADED, slug, "warning", recurring = o.priorVerdict.degraded, data = buildJsonObject {
                        put("agentSlug", slug)
                        put("at", o.challengedAt.toString())
                        put("roundTripMs", o.roundTripMs)
                        o.agentElapsedMs?.let { put("agentMs", it) }
                        o.transportMs?.let { put("transportMs", it) }
                        o.verdict.baselineMs?.let { put("baselineMs", it) }
                        put("thresholdMs", o.verdict.thresholdMs)
                    })
                } else if (!o.verdict.degraded && o.priorVerdict.degraded) {
                    raises += Raise(SystemAlertService.AGENT_RECOVERED, slug, "info", broadcast = false, data = buildJsonObject {
                        put("agentSlug", slug)
                        put("at", o.challengedAt.toString())
                        put("from", "degraded")
                        put("roundTripMs", o.roundTripMs)
                    })
                }
            }
            return raises
        }

        /**
         * Plain (non-mTLS) client used only to ask the gateway whether it is
         * serving health tokens. Shared: Quartz builds a fresh job instance per
         * fire, and a per-instance client would leak a connection pool a minute.
         */
        private val tokenProbeClient: HttpClient by lazy { HttpClient(CIO) }

        /**
         * Whether the previous round was a platform round, so a second one in
         * a row is raised as recurring rather than as a new episode. Per
         * replica and lost on restart, which costs at most one duplicate entry.
         */
        @Volatile
        private var lastRoundWasPlatformSlow = false
    }

    data class AgentInfo(val id: Long, val slug: String, val uri: String)

    /** What one round concluded about one agent. */
    data class RoundOutcome(
        val agent: AgentInfo,
        val challengedAt: Instant,
        val result: String,
        val roundTripMs: Int,
        /** The agent's own time on the challenge, when it reported one. */
        val agentElapsedMs: Int?,
        /** `probe_agents.last_status` before this round. */
        val statusBefore: String?,
        /** The status in force after this round. */
        val statusAfter: String,
        /** This round's verdict (a passing round's own; otherwise the carried-over one). */
        val verdict: Verdict,
        /** The verdict of the last passing round before this one. */
        val priorVerdict: Verdict,
    ) {
        /** The round trip less the agent's own time: the path to the agent and its handshake. */
        val transportMs: Int? get() = agentElapsedMs?.let { (roundTripMs - it).coerceAtLeast(0) }
    }

    /** One alert a settled round raises. */
    data class Raise(
        val alertType: String,
        val subject: String,
        val severity: String,
        val data: JsonObject,
        val recurring: Boolean = false,
        /** False keeps it to the routing seam: never an org banner. */
        val broadcast: Boolean = true,
    )

    override fun execute(context: JobExecutionContext) {
        val redis = HealthChallengeContext.redis
        val gatewayUrl = HealthChallengeContext.gatewayUrl
        val clientFactory = HealthChallengeContext.clientFactory
        val liveness = HealthChallengeContext.liveness

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

        // Each agent's standing before the round, in one statement: the
        // baseline sizes its challenge budget, and the verdict of its last
        // passing round is what a degraded alert is a transition from.
        val priorVerdicts = try {
            transaction { DegradationRule.verdicts(agents.map { it.id }) }
        } catch (e: Exception) {
            log.warn("could not read agent baselines before the health round: {}", e.message)
            emptyMap()
        }

        val outcomes = runBlocking {
            agents.map { agent ->
                async {
                    challengeAgent(
                        agent, priorVerdicts[agent.id] ?: DegradationRule.UNKNOWN,
                        redis, gatewayUrl, clientFactory, liveness,
                    )
                }
            }.awaitAll()
        }.filterNotNull()

        settleRound(outcomes, gatewayUrl)
    }

    private suspend fun challengeAgent(
        agent: AgentInfo,
        priorVerdict: Verdict,
        redis: RedisCommands<String, String>,
        gatewayUrl: String,
        clientFactory: AgentMtlsClientFactory,
        liveness: AgentLiveness,
    ): RoundOutcome? {
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
            return null
        }

        try {
            // Pin the challenge to this agent's certificate identity — a health
            // probe must reach the agent it claims to, not a peer answering for it.
            val httpClient = clientFactory.client(agent.slug)
            val response = withTimeout(challengeBudgetMs(priorVerdict.baselineMs)) {
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
            // The agent's own clock on the challenge: how long its fetch of
            // the token took. Everything else in the round trip is the path to
            // the agent and the handshake, which the agent cannot see.
            val agentElapsedMs = body["elapsed_ms"]?.jsonPrimitive?.intOrNull
                ?: body["elapsedMs"]?.jsonPrimitive?.intOrNull
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
                return null
            }

            log.debug("health challenge {} for agent {}: {}", challengeId, agent.slug, result)
            return recordResult(agent, challengeId, challengedAt, respondedAt, roundTripMs, agentElapsedMs, result, supportsSealed, priorVerdict, liveness)
        } catch (e: Exception) {
            val respondedAt = Instant.now()
            val roundTripMs = (respondedAt.toEpochMilli() - challengedAt.toEpochMilli()).toInt()
            val result = if (e is kotlinx.coroutines.TimeoutCancellationException) "timeout" else "fail"

            log.warn("health challenge for agent {} failed: {}", agent.slug, e.message)
            // No answer came back, so nothing was learned about capability —
            // null leaves the last observation in place rather than clearing it
            // on a timeout.
            return recordResult(agent, challengeId, challengedAt, respondedAt, roundTripMs, null, result, null, priorVerdict, liveness)
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
            Raise(
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
        )
    }

    private fun recordResult(
        agent: AgentInfo,
        challengeId: String,
        challengedAt: Instant,
        respondedAt: Instant,
        roundTripMs: Int,
        agentElapsedMs: Int?,
        result: String,
        supportsSealed: Boolean?,
        priorVerdict: Verdict,
        liveness: AgentLiveness,
    ): RoundOutcome {
        val agentId = agent.id
        val agentSlug = agent.slug
        var statusBefore: String? = null
        // The status actually in force after this round — either what we wrote
        // or, while hysteresis is holding, whatever was there already.
        var statusAfter = "success"
        var heldOnEvidence = false
        // What the degradation rule concluded about this round. Also published
        // with the realtime event, because the dashboard replaces the agent's
        // whole row from it — without the verdict the row would fall back to a
        // fixed ceiling until the next poll and cry wolf over a distant agent.
        var verdict = DegradationRule.UNKNOWN

        transaction {
            // The previous round that observed anything, read in the same
            // transaction as the insert so two overlapping rounds cannot both
            // read "first failure" and both decline to convict. Inconclusive
            // rows are skipped: they are not evidence either way.
            val prior = AgentHealthChecks.selectAll()
                .where {
                    (AgentHealthChecks.probeAgentId eq agentId) and
                        (AgentHealthChecks.result neq RESULT_INCONCLUSIVE)
                }
                .orderBy(AgentHealthChecks.createdAt to SortOrder.DESC)
                .limit(1)
                .firstOrNull()
            val priorResult = prior?.get(AgentHealthChecks.result)

            // Slowness is judged against this agent's own recent rounds, read
            // before this one is written so the sample never includes itself.
            // A round that did not pass is not judged at all — the agent keeps
            // the verdict of its last passing round, the same one every reader
            // of its health is served.
            verdict = if (result == RESULT_PASS) {
                DegradationRule.verdictForNewRound(agentId, roundTripMs)
            } else {
                DegradationRule.verdictFor(agentId)
            }

            AgentHealthChecks.insert {
                it[id] = UUID.randomUUID()
                it[probeAgentId] = agentId
                it[AgentHealthChecks.challengeId] = challengeId
                it[AgentHealthChecks.challengedAt] = challengedAt
                it[AgentHealthChecks.respondedAt] = respondedAt
                it[AgentHealthChecks.roundTripMs] = roundTripMs
                it[AgentHealthChecks.agentElapsedMs] = agentElapsedMs
                it[AgentHealthChecks.result] = result
                it[createdAt] = Instant.now()
            }

            statusBefore = ProbeAgents.selectAll()
                .where { ProbeAgents.id eq agentId }
                .firstOrNull()
                ?.get(ProbeAgents.lastStatus)

            // Two failed challenges in a row are a conviction only for an
            // agent that has produced nothing else since the first: an agent
            // whose dispatched runs have come back in the meantime is alive
            // on better evidence than the challenge path can give.
            val provenAlive = result != RESULT_PASS &&
                priorResult != null && priorResult != RESULT_PASS &&
                liveness.seenSince(agentId, prior[AgentHealthChecks.challengedAt])
            val newStatus = nextStatus(result, priorResult, provenAlive)
            heldOnEvidence = provenAlive && newStatus == null

            ProbeAgents.update({ ProbeAgents.id eq agentId }) {
                it[lastPing] = challengedAt
                // Null means hysteresis is holding: the observation fields are
                // still current, but one non-pass round does not change the
                // verdict — and it is the verdict that decides whether the
                // agent stays eligible for dispatch.
                if (newStatus != null) it[lastStatus] = newStatus
                // The leg the agent cannot see: the round trip less its own
                // time. Zero when the agent reported no time of its own.
                it[lastPingDelayMs] = agentElapsedMs?.let { ms -> (roundTripMs - ms).coerceAtLeast(0) } ?: 0
                it[lastPongDeltaMs] = roundTripMs
                if (supportsSealed != null) it[supportsEncryptedPayload] = supportsSealed
            }

            statusAfter = newStatus ?: statusBefore ?: "success"

            if (newStatus == null) {
                if (heldOnEvidence) {
                    log.info(
                        "health challenge for agent {} returned {} again — holding at {}: the agent has answered a dispatched run since the first failure",
                        agentSlug, result, statusAfter,
                    )
                } else {
                    log.info(
                        "health challenge for agent {} returned {} — holding at {} pending {} consecutive failures",
                        agentSlug, result, statusAfter, FAILURE_THRESHOLD,
                    )
                }
            }
        }

        val outcome = RoundOutcome(
            agent = agent,
            challengedAt = challengedAt,
            result = result,
            roundTripMs = roundTripMs,
            agentElapsedMs = agentElapsedMs,
            statusBefore = statusBefore,
            statusAfter = statusAfter,
            verdict = verdict,
            priorVerdict = priorVerdict,
        )

        // Publish to both summary (always subscribed) and detail (dropdown open) channels
        val eventData = buildJsonObject {
            put("agentSlug", agentSlug)
            put("status", statusAfter)
            put("lastCheck", respondedAt.toString())
            put("lastResponseMs", roundTripMs)
            outcome.agentElapsedMs?.let { put("agentMs", it) }
            outcome.transportMs?.let { put("transportMs", it) }
            put("degraded", verdict.degraded)
            put("baselineMs", verdict.baselineMs)
            put("degradedThresholdMs", verdict.thresholdMs)
        }
        FleetAudience.publish(agentSlug, "health.updated", eventData)

        return outcome
    }

    /**
     * Decides the round's alerts from every agent's outcome.
     *
     * First the round itself: if most of the fleet came back slow in the same
     * minute, that is one platform alert, and no agent is called degraded for
     * it. Then each agent's own transitions, per [decide].
     */
    private fun settleRound(outcomes: List<RoundOutcome>, gatewayUrl: String) {
        val judged = outcomes.filter { it.result == RESULT_PASS }
        val slow = judged.filter { it.roundTripMs > it.verdict.thresholdMs }
        val platformRound = isPlatformRound(judged.size, slow.size)

        if (platformRound) {
            val subject = tokenEndpointSubject(gatewayUrl)
            log.warn(
                "health round was slow platform-wide: {} of {} agents over their threshold ({})",
                slow.size, judged.size, slow.joinToString(" ") { "${it.agent.slug}=${it.roundTripMs}ms" },
            )
            raisePlatformAgentAlert(
                Raise(
                    SystemAlertService.HEALTH_ROUND_SLOW,
                    subject,
                    "warning",
                    buildJsonObject {
                        put("endpoint", subject)
                        put("at", judged.minOf { it.challengedAt }.toString())
                        put("slowAgents", slow.size)
                        put("agents", judged.size)
                        put("medianRoundTripMs", judged.map { it.roundTripMs }.sorted()[judged.size / 2])
                    },
                    recurring = lastRoundWasPlatformSlow,
                )
            )
        }
        lastRoundWasPlatformSlow = platformRound

        decide(outcomes, platformRound).forEach { raisePlatformAgentAlert(it) }
    }

    /**
     * Offers a shared-agent health alert to the routing seam once; if unclaimed
     * and the raise allows it, falls back to the historical per-org broadcast.
     * Consulted once per condition, not per org, so a host router
     * logs/records it a single time.
     */
    private fun raisePlatformAgentAlert(raise: Raise) {
        val handled = SystemAlertRouting.handled(
            AlertContext(
                alertType = raise.alertType,
                subject = raise.subject,
                orgId = null,
                orgScoped = false,
                severity = raise.severity,
                data = raise.data,
                recurring = raise.recurring,
            )
        )
        if (!handled && raise.broadcast) raiseForAllOrgs(raise.alertType, raise.subject, raise.severity, raise.data)
    }

    private fun raiseForAllOrgs(alertType: String, subject: String, severity: String, data: JsonObject) {
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
}
