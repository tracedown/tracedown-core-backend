package dev.tracedown.worker.config

import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.S3Config
import io.ktor.server.application.ApplicationEnvironment
import java.nio.file.Path
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
    /**
     * Global default retention for raw probe results in days. Negative never
     * expires a result by age; zero is refused at startup.
     */
    val resultRetentionDays: Int,
    /**
     * Global default retention for stored response bodies in days, independent
     * of [resultRetentionDays]. Defaults to -1: unset, bodies simply follow
     * their results the way they did before the two windows were split, and the
     * body pass does not run at all.
     *
     * A body is only reachable through its `probe_steps` row, so it can never
     * outlive the result that owns it. With both windows positive the effective
     * body lifetime is the smaller of the two. Zero is refused at startup.
     *
     * Bodies in an `in_place` body store (`probe_steps.body_store_id` set) are
     * the store owner's and are outside both windows.
     */
    val bodyRetentionDays: Int,
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
    /**
     * Where platform storage lives — the same root, bucket and prefix the ingestor
     * relocates bodies into. Deletions are confined to it: a stored URI outside it
     * names a body the platform does not own and is skipped, never deleted.
     */
    val bodyConfinement: BodyConfinement,
) {
    companion object {

        /**
         * Reads a retention window, refusing the one value that has no honest
         * reading.
         *
         * A window is a number of days (`> 0`), or negative for "never expire by
         * age". Zero is neither: it reads as "expire immediately" to the cutoff
         * arithmetic and as "keep forever" to most operators who type it, and
         * one of those deletes every result the platform holds. Rather than pick,
         * the worker refuses to start and says which variable to fix — a startup
         * failure is visible, a silently emptied database is not.
         */
        private fun retentionDays(
            config: io.ktor.server.config.ApplicationConfig,
            path: String,
            variable: String,
            default: Int,
        ): Int {
            val raw = config.propertyOrNull(path)?.getString()?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            val days = raw.toIntOrNull()
                ?: throw IllegalArgumentException("$variable must be a whole number of days, not \"$raw\"")
            if (days == 0) {
                throw IllegalArgumentException(
                    "$variable must not be 0 — use -1 to never expire by age, or a positive number of days",
                )
            }
            return days
        }

        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): WorkerConfig = load(env.config)

        /** Loads configuration from a resolved config tree. */
        fun load(config: io.ktor.server.config.ApplicationConfig): WorkerConfig {
            return WorkerConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString(),
                ),
                redisAUrl = config.property("redis.a.url").getString(),
                redisBUrl = config.property("redis.b.url").getString(),
                resultRetentionDays = retentionDays(config, "worker.resultRetentionDays", "RESULT_RETENTION_DAYS", 90),
                bodyRetentionDays = retentionDays(config, "worker.bodyRetentionDays", "BODY_RETENTION_DAYS", -1),
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
                        region = config.propertyOrNull("storage.s3.region")?.getString()?.takeIf { it.isNotBlank() } ?: "auto",
                        timeoutSeconds = config.propertyOrNull("storage.s3.timeoutSeconds")?.getString()?.toLongOrNull() ?: 30L,
                    )
                },
                bodyConfinement = run {
                    val root = config.propertyOrNull("storage.filesystemRoot")?.getString()?.takeIf { it.isNotBlank() }
                    val bucket = config.propertyOrNull("storage.s3.bucket")?.getString()?.takeIf { it.isNotBlank() }
                    // Deletions are confined to platform storage — but only to
                    // what the operator actually named. A worker upgraded into
                    // this release without STORAGE_S3_BUCKET or
                    // STORAGE_FILESYSTEM_ROOT keeps deleting as it did, rather
                    // than purging rows and orphaning every object behind them;
                    // Application says so at startup.
                    BodyConfinement(
                        filesystemRoot = root?.let { Path.of(it) },
                        s3Bucket = bucket,
                        s3KeyPrefix = config.propertyOrNull("storage.s3.prefix")?.getString() ?: "",
                        unconfinedSchemes = buildSet {
                            if (root == null) add("file")
                            if (bucket == null) add("s3")
                        },
                    )
                },
            )
        }
    }
}
