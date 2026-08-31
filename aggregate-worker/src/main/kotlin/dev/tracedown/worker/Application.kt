package dev.tracedown.worker

import dev.tracedown.common.config.DatabaseFactory
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

    // Body storage client (for deleting bodies during retention and purge)
    val storageClient = BodyStorageClient(s3Config = config.s3Config)

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
    jobScope.launchJob(RetentionJob(defaultRetentionDays = config.resultRetentionDays, storageClient = storageClient, intervalSeconds = intervals.retentionSeconds))
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

    log.info(
        "aggregate-worker started (resultRetentionDays={}, hourlyAggregateRetentionDays={})",
        config.resultRetentionDays, config.hourlyAggregateRetentionDays
    )

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        jobScope.cancel()
        dataSource.close()
        log.info("aggregate-worker shut down")
    }
}
