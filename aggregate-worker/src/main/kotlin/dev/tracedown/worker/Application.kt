package dev.tracedown.worker

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.config.SecretGuard
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.installHealthEndpoints
import dev.tracedown.common.health.redisCheck
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.worker.config.WorkerConfig
import dev.tracedown.worker.jobs.DailyAggregationJob
import dev.tracedown.worker.jobs.HourlyAggregationJob
import dev.tracedown.worker.jobs.OrphanUserPurgeJob
import dev.tracedown.worker.jobs.OutboxPurgeJob
import dev.tracedown.worker.jobs.PurgeJob
import dev.tracedown.worker.jobs.PurgeScheduleRepair
import dev.tracedown.worker.jobs.RetentionJob
import dev.tracedown.common.domain.HttpDnsDomainVerifier
import dev.tracedown.worker.jobs.AggregateRetentionJob
import dev.tracedown.worker.jobs.AgentHealthCleanupJob
import dev.tracedown.worker.jobs.AuditLogRetentionJob
import dev.tracedown.worker.jobs.BodyDeletionRetryJob
import dev.tracedown.worker.jobs.DomainReverifyJob
import dev.tracedown.worker.jobs.ExpiredInviteSweepJob
import dev.tracedown.worker.jobs.ExpiredTokenCleanupJob
import dev.tracedown.worker.jobs.NotificationLogRetentionJob
import dev.tracedown.worker.jobs.SessionCleanupJob
import dev.tracedown.worker.jobs.launchJob
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB and all scheduled jobs. */
fun Application.module() {
    val config = WorkerConfig.load(environment)

    // No insecure defaults of its own to guard; still reports the resolved
    // deployment environment so a misspelt DEPLOYMENT_ENV is visible here too.
    SecretGuard.announce(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "aggregate-worker",
    )

    // Database
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
        maximumPoolSize = 5,
    )

    // How long a soft-deleted row is kept before the purge may erase it. The
    // same setting the api-gateway reads: it stamps `purge_after` on delete,
    // this worker stamps it on the accounts and invites it sweeps, and the
    // purge job acts on it.
    DeletionRetention.init(config.purgeRetentionDays)

    // Rows soft-deleted before every delete path stamped a purge date were left
    // with none, so nothing would ever erase them. Repair them against the
    // configured retention before any job runs — idempotent, and it only ever
    // moves a purge date later.
    PurgeScheduleRepair.run()

    // Body storage client (for deleting bodies during retention and purge),
    // confined to platform storage: a stored URI outside it is skipped.
    val storageClient = BodyStorageClient(s3Config = config.s3Config, confinement = config.bodyConfinement)
    if (config.s3Config != null && config.bodyConfinement.s3Bucket == null) {
        log.warn(
            "STORAGE_S3_ENDPOINT is set but STORAGE_S3_BUCKET is not — s3:// body deletions are running " +
                "unconfined, exactly as before this release. Set STORAGE_S3_BUCKET (and STORAGE_S3_PREFIX) " +
                "to the same values the result-ingestor uses, so retention and purge can only ever delete " +
                "the platform's own bodies.",
        )
    }
    if (config.bodyConfinement.filesystemRoot == null) {
        log.warn(
            "STORAGE_FILESYSTEM_ROOT is not set — file:// body deletions are running unconfined, exactly " +
                "as before this release. Set it to the same value the result-ingestor uses.",
        )
    }

    // Redis B (ephemeral cache) — lazy init for metrics percentile cache
    val redisB by lazy {
        val conn = RedisFactory.createConnection(config.redisBUrl)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { conn.close() }
        conn.sync()
    }

    // Job scope
    val jobScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val intervals = config.jobIntervals

    // Aggregation is watermarked: it resumes from where it stopped instead of
    // assuming a fixed lookback covers every gap. Retention bounds how far a
    // backfill may reach — buckets whose raw rows are already gone cannot be
    // rebuilt from them.
    jobScope.launchJob(
        HourlyAggregationJob(
            intervalSeconds = intervals.hourlyAggregationSeconds,
            redisB = { redisB },
            resultRetentionDays = config.resultRetentionDays,
        )
    )
    jobScope.launchJob(
        DailyAggregationJob(
            intervalSeconds = intervals.dailyAggregationSeconds,
            resultRetentionDays = config.resultRetentionDays,
        )
    )
    jobScope.launchJob(
        RetentionJob(
            defaultRetentionDays = config.resultRetentionDays,
            storageClient = storageClient,
            defaultBodyRetentionDays = config.bodyRetentionDays,
            intervalSeconds = intervals.retentionSeconds,
        )
    )
    jobScope.launchJob(AggregateRetentionJob(hourlyRetentionDays = config.hourlyAggregateRetentionDays, intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(PurgeJob(storageClient = storageClient, intervalSeconds = intervals.purgeSeconds))
    // Finishes the body deletions retention and purge could not complete. Without
    // it a storage failure during either left the object referenced by nothing —
    // permanently outside retention and erasure.
    jobScope.launchJob(BodyDeletionRetryJob(storageClient = storageClient, intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(OrphanUserPurgeJob())
    jobScope.launchJob(ExpiredInviteSweepJob(intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(
        OutboxPurgeJob(
            intervalSeconds = intervals.retentionSeconds,
            staleHorizon = config.outboxCursorStaleHorizon,
        )
    )
    jobScope.launchJob(SessionCleanupJob(intervalSeconds = intervals.sessionCleanupSeconds))
    jobScope.launchJob(AgentHealthCleanupJob(retentionDays = config.agentHealthRetentionDays, intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(AuditLogRetentionJob(retentionDays = config.auditLogRetentionDays, intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(NotificationLogRetentionJob(retentionDays = config.notificationLogRetentionDays, intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(ExpiredTokenCleanupJob(intervalSeconds = intervals.retentionSeconds))
    jobScope.launchJob(DomainReverifyJob(verifier = HttpDnsDomainVerifier(), enabled = !config.trustedDomainMode))

    // Postgres is required — every job here is a database job. Redis B is not:
    // it only caches aggregation percentiles, and the jobs run without it. The
    // check is a provider so it does not force the lazy connection open on a
    // deployment that never touches it.
    installHealthEndpoints(
        "aggregate-worker",
        listOf(
            databaseCheck(dataSource),
            redisCheck("redis-b", required = false) { redisB },
        ),
    )

    // The effective rule, not the raw numbers: -1 reads as a number and the
    // thing an operator needs to see is whether bodies have a life of their own.
    log.info(
        "aggregate-worker started (results: {}, response bodies: {}, hourlyAggregateRetentionDays={})",
        if (config.resultRetentionDays > 0) "${config.resultRetentionDays}d" else "never expire by age",
        if (config.bodyRetentionDays > 0) {
            "${config.bodyRetentionDays}d, or with their result, whichever comes first"
        } else {
            "no window of their own — bodies follow their results"
        },
        config.hourlyAggregateRetentionDays
    )
    log.info(
        "aggregate-worker purge window: {}",
        if (config.purgeRetentionDays > 0) {
            "deleted rows are kept ${config.purgeRetentionDays} day(s) before erasure"
        } else {
            "deleted rows are erased on the next purge run"
        },
    )

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        jobScope.cancel()
        dataSource.close()
        log.info("aggregate-worker shut down")
    }
}
