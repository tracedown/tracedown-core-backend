package dev.tracedown.worker.config

import dev.tracedown.common.storage.S3Config
import io.ktor.server.application.ApplicationEnvironment
import java.time.Duration

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String,
)

/** Job interval overrides — allows shorter intervals for E2E testing. */
data class JobIntervals(
    val hourlyAggregationSeconds: Long,
    val dailyAggregationSeconds: Long,
    val retentionSeconds: Long,
    val purgeSeconds: Long,
    val sessionCleanupSeconds: Long,
)

/** Typed configuration for the aggregate worker. */
data class WorkerConfig(
    val database: DatabaseConfig,
    val redisAUrl: String,
    val redisBUrl: String,
    /** Global default retention for raw probe results in days. Zero or negative keeps forever. */
    val resultRetentionDays: Int,
    /** How long to keep hourly aggregates in days. Zero or negative keeps forever. */
    val hourlyAggregateRetentionDays: Int,
    /** How long to keep agent health-check history in days. Zero or negative keeps forever. */
    val agentHealthRetentionDays: Int,
    /** How long to keep organization audit-log entries in days. Zero or negative keeps forever. */
    val auditLogRetentionDays: Int,
    /** How long to keep notification delivery history in days. Zero or negative keeps forever. */
    val notificationLogRetentionDays: Int,
    /** Mirrors the gateway flag; disables domain re-verification when true. */
    val trustedDomainMode: Boolean,
    /**
     * How long an outbox consumer may sit behind the log without advancing
     * before the purge stops holding rows back for it. See
     * [dev.tracedown.worker.jobs.OutboxCursorPolicy]. Zero or negative restores
     * the unconditional floor — a stalled consumer then pins the outbox
     * forever, so only set that deliberately.
     */
    val outboxCursorStaleHorizon: Duration,
    /** Job execution intervals. */
    val jobIntervals: JobIntervals,
    /** S3-compatible storage config. Null if only filesystem storage is used. */
    val s3Config: S3Config?,
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): WorkerConfig {
            val config = env.config
            return WorkerConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                redisBUrl = config.property("redis.b.url").getString(),
                resultRetentionDays = config.propertyOrNull("worker.resultRetentionDays")
                    ?.getString()?.toInt() ?: 90,
                hourlyAggregateRetentionDays = config.propertyOrNull("worker.hourlyAggregateRetentionDays")
                    ?.getString()?.toInt() ?: 365,
                agentHealthRetentionDays = config.propertyOrNull("worker.agentHealthRetentionDays")
                    ?.getString()?.toInt() ?: 90,
                auditLogRetentionDays = config.propertyOrNull("worker.auditLogRetentionDays")
                    ?.getString()?.toInt() ?: 90,
                notificationLogRetentionDays = config.propertyOrNull("worker.notificationLogRetentionDays")
                    ?.getString()?.toInt() ?: 90,
                trustedDomainMode = config.propertyOrNull("worker.trustedDomainMode")
                    ?.getString()?.toBoolean() ?: true,
                outboxCursorStaleHorizon = config.propertyOrNull("worker.outboxCursorStaleHours")
                    ?.getString()?.toLongOrNull()?.let { Duration.ofHours(it) }
                    ?: dev.tracedown.worker.jobs.OutboxCursorPolicy.DEFAULT_STALE_HORIZON,
                jobIntervals = JobIntervals(
                    hourlyAggregationSeconds = config.propertyOrNull("worker.intervals.hourlyAggregation")
                        ?.getString()?.toLong() ?: 900L,
                    dailyAggregationSeconds = config.propertyOrNull("worker.intervals.dailyAggregation")
                        ?.getString()?.toLong() ?: 3600L,
                    retentionSeconds = config.propertyOrNull("worker.intervals.retention")
                        ?.getString()?.toLong() ?: 3600L,
                    purgeSeconds = config.propertyOrNull("worker.intervals.purge")
                        ?.getString()?.toLong() ?: 300L,
                    sessionCleanupSeconds = config.propertyOrNull("worker.intervals.sessionCleanup")
                        ?.getString()?.toLong() ?: 900L,
                ),
                s3Config = config.propertyOrNull("storage.s3.endpoint")?.getString()?.let { endpoint ->
                    S3Config(
                        endpoint = endpoint,
                        accessKey = config.property("storage.s3.accessKey").getString(),
                        secretKey = config.property("storage.s3.secretKey").getString(),
                    )
                },
            )
        }
    }
}
