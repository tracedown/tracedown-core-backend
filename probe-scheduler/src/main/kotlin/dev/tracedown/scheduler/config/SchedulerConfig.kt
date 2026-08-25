package dev.tracedown.scheduler.config

import io.ktor.server.application.ApplicationEnvironment

/**
 * Typed configuration for the probe-scheduler, loaded from application.conf.
 */
data class SchedulerConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val aesKey: String,
    val gatewayUrl: String,
    val consistencySweepIntervalSeconds: Long,
    val threadPoolSize: Int,
    /** Maximum number of pending dispatches in the dispatch queue. */
    val dispatchQueueSize: Int,
    /** Number of concurrent dispatcher coroutines draining the queue. */
    val dispatchWorkers: Int,
    /** When true, domain verification is a no-op and no probe limits apply. */
    val trustedDomainMode: Boolean,
    val probe: ProbeConfig,
) {
    data class DatabaseConfig(
        val url: String,
        val user: String,
        val password: String,
    )

    /** Probe execution limits per Lace spec §10. */
    data class ProbeConfig(
        /** Default per-request timeout in ms when service has no override. */
        val defaultTimeoutMs: Int,
        /** System-wide maximum timeout in ms (executor.maxTimeoutMs). Service overrides clamped to this. */
        val maxTimeoutMs: Int,
        /** Maximum redirect hops (executor.maxRedirects). */
        val maxRedirects: Int,
        /**
         * Fleet-wide kill switch for payload sealing. Whether a given dispatch
         * is sealed is a per-agent setting; this only exists so an operator can
         * turn the whole mechanism off from the environment, without editing
         * rows, if it ever misbehaves in production.
         */
        val payloadEncryptionEnabled: Boolean = true,
    )

    companion object {
        fun load(environment: ApplicationEnvironment): SchedulerConfig {
            val config = environment.config
            return SchedulerConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                aesKey = config.property("platform.aesKey").getString(),
                gatewayUrl = config.propertyOrNull("scheduler.gatewayUrl")
                    ?.getString() ?: "http://localhost:8080",
                consistencySweepIntervalSeconds = config.propertyOrNull("scheduler.consistencySweepIntervalSeconds")
                    ?.getString()?.toLongOrNull() ?: 300,
                threadPoolSize = config.propertyOrNull("scheduler.threadPoolSize")
                    ?.getString()?.toIntOrNull() ?: 10,
                dispatchQueueSize = config.propertyOrNull("scheduler.dispatchQueueSize")
                    ?.getString()?.toIntOrNull() ?: 100_000,
                dispatchWorkers = config.propertyOrNull("scheduler.dispatchWorkers")
                    ?.getString()?.toIntOrNull() ?: 50,
                trustedDomainMode = config.propertyOrNull("scheduler.trustedDomainMode")
                    ?.getString()?.toBoolean() ?: true,
                probe = ProbeConfig(
                    defaultTimeoutMs = config.propertyOrNull("probe.defaultTimeoutMs")
                        ?.getString()?.toIntOrNull() ?: 30_000,
                    maxTimeoutMs = config.propertyOrNull("probe.maxTimeoutMs")
                        ?.getString()?.toIntOrNull() ?: 300_000,
                    maxRedirects = config.propertyOrNull("probe.maxRedirects")
                        ?.getString()?.toIntOrNull() ?: 10,
                    payloadEncryptionEnabled = config.propertyOrNull("probe.payloadEncryptionEnabled")
                        ?.getString()?.toBooleanStrictOrNull() ?: true,
                ),
            )
        }
    }
}
