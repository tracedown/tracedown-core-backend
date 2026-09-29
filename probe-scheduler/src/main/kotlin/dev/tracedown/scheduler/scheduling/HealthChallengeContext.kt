package dev.tracedown.scheduler.scheduling

import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import dev.tracedown.scheduler.dispatch.AgentLiveness
import io.lettuce.core.api.sync.RedisCommands

/**
 * Static holder for HealthChallengeJob dependencies.
 * Initialized once at startup, read by the Quartz job.
 */
object HealthChallengeContext {

    lateinit var redis: RedisCommands<String, String>
        private set
    lateinit var gatewayUrl: String
        private set

    /** Per-agent mTLS clients: each challenge dials its agent on a slug-pinned client. */
    lateinit var clientFactory: AgentMtlsClientFactory
        private set

    /** The dispatch path's record of which agents have answered a run lately. */
    lateinit var liveness: AgentLiveness
        private set

    fun init(
        redis: RedisCommands<String, String>,
        gatewayUrl: String,
        clientFactory: AgentMtlsClientFactory,
        liveness: AgentLiveness,
    ) {
        this.redis = redis
        this.gatewayUrl = gatewayUrl
        this.clientFactory = clientFactory
        this.liveness = liveness
    }
}
