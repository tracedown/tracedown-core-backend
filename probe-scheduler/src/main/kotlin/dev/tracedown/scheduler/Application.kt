package dev.tracedown.scheduler

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.installHealthEndpoints
import dev.tracedown.common.health.redisCheck
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.crypto.AgentMtlsClientFactory
import dev.tracedown.scheduler.crypto.PayloadSealing
import dev.tracedown.scheduler.crypto.RevocationChecker
import dev.tracedown.scheduler.crypto.SchedulerCertService
import dev.tracedown.scheduler.dispatch.AgentDispatchService
import dev.tracedown.scheduler.dispatch.AgentExecutionBackend
import dev.tracedown.scheduler.dispatch.ProbeExecutionBackends
import dev.tracedown.scheduler.dispatch.AgentSelector
import dev.tracedown.scheduler.dispatch.DispatchQueue
import dev.tracedown.scheduler.dispatch.QueuePolicyManager
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.scheduling.HealthChallengeContext
import dev.tracedown.scheduler.scheduling.HealthChallengeJob
import dev.tracedown.scheduler.scheduling.ProbeJobContext
import dev.tracedown.scheduler.scheduling.QuartzManager
import dev.tracedown.scheduler.scheduling.ScheduleSyncService
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.scheduler.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires all scheduler components and starts the probe loop. */
fun Application.module() {
    val config = SchedulerConfig.load(environment)

    // Fail fast in production if the insecure all-zero platform key is still set.
    // No-op in dev (see SecretGuard).
    dev.tracedown.common.config.SecretGuard.requireSecure(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "probe-scheduler",
        mapOf("PLATFORM_AES_KEY (all-zero dev default)" to (config.aesKey == "0".repeat(64))),
    )

    // Database. The pool is sized to the dispatch concurrency rather than left
    // at its default — fifty workers sharing ten connections spend the tick
    // waiting on Hikari and then time out. See SchedulerConfig.poolSizeFor.
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
        maximumPoolSize = config.dbPoolSize,
    )
    log.info(
        "database pool sized to {} connections (dispatchWorkers={}, quartzThreads={})",
        config.dbPoolSize, config.dispatchWorkers, config.threadPoolSize,
    )

    // Redis A
    val redisConn = RedisFactory.createConnection(config.redisAUrl)
    val redis = redisConn.sync()

    // Variable decryption
    VariableCrypto.init(config.aesKey)
    dev.tracedown.common.realtime.RealtimePublisher.init { redis }

    // mTLS client certificate
    val certService = SchedulerCertService(config.aesKey)
    certService.init()

    // Enforce revoked agent certificates at the TLS trust decision.
    val revocationChecker = RevocationChecker.fromDatabase()

    // One factory of per-agent, slug-pinned mTLS clients, shared by dispatch and
    // health challenges — every outbound call pins the peer to the intended agent.
    val agentClientFactory = AgentMtlsClientFactory(
        schedulerCert = certService.certificate,
        schedulerKey = certService.privateKey,
        caCert = certService.caCertificate,
        trustedCas = certService.trustedCaCertificates,
        revocationChecker = revocationChecker,
    )

    // Probe execution: the agent backend (slug-pinned mTLS dispatch) by
    // default, or whatever backend the host registered.
    // Payload sealing on top of mTLS, decided per agent at dispatch time. Null
    // only when the fleet-wide kill switch is off; an agent that has not opted
    // in is unaffected either way.
    val sealing = if (!config.probe.payloadEncryptionEnabled) {
        log.info("probe payload encryption disabled fleet-wide")
        null
    } else {
        PayloadSealing(
            schedulerCertPem = certService.certificatePem,
            schedulerKey = certService.privateKey,
        )
    }

    val executionBackend = ProbeExecutionBackends.provider?.invoke()
        ?: AgentExecutionBackend(AgentSelector(redis), AgentDispatchService(agentClientFactory, sealing))

    // Queue policy
    val queuePolicy = QueuePolicyManager(redis)

    // Result publishing
    val resultPublisher = ResultPublisher(redis)

    // Coroutine scope for async work. Dispatchers.IO, not Default: everything
    // launched here — the consistency sweep above all — runs blocking JDBC, and
    // on Default it competed for CPU-count threads with the dispatch workers.
    // The workers themselves run on the dispatch queue's own fixed pool.
    val schedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Quartz scheduler
    val quartzManager = QuartzManager(config.threadPoolSize)
    quartzManager.start()

    // Dispatch queue — decouples Quartz triggers from agent HTTP dispatch
    val dispatchQueue = DispatchQueue(
        capacity = config.dispatchQueueSize,
        workers = config.dispatchWorkers,
        quartzManager = quartzManager,
        executionBackend = executionBackend,
        queuePolicy = queuePolicy,
        resultPublisher = resultPublisher,
        probeConfig = config.probe,
        trustedDomainMode = config.trustedDomainMode,
    )
    dispatchQueue.start(schedulerScope)

    // A trigger Quartz drops for lateness is coverage lost. Record it like any
    // other shed instead of letting it vanish.
    quartzManager.onProbeMisfire(dispatchQueue::recordMisfire)

    // ProbeJob dependencies
    ProbeJobContext.init(dispatchQueue = dispatchQueue)

    // HealthChallengeJob dependencies
    HealthChallengeContext.init(
        redis = redis,
        gatewayUrl = config.gatewayUrl,
        clientFactory = agentClientFactory,
    )

    // Schedule health challenges: every 1 minute, offset to :30 so the
    // measurement never races the :00 probe burst (cron schedules all fire
    // at second 0 — a challenge at the same instant reads as degraded under
    // fleet-wide load even when the agent clears its burst fine).
    quartzManager.scheduleSystemJob(
        HealthChallengeJob::class.java,
        "health-challenge",
        "30 * * * * ?",
    )

    // Redis pub/sub for schedule nudge
    val pubSubConn = RedisFactory.createPubSubConnection(config.redisAUrl)

    // Schedule sync — bootstrap from DB, subscribe to nudge, then sweep periodically
    val syncService = ScheduleSyncService(quartzManager, config.consistencySweepIntervalSeconds, pubSubConn)
    syncService.bootstrap()
    syncService.startPubSub()

    syncService.startSweep(schedulerScope)

    // Liveness + readiness. Both dependencies are required: without Postgres
    // there is nothing to schedule, and without Redis A there is no dispatch
    // lock, no agent selection and nowhere to publish a result — a scheduler
    // that has lost either produces no coverage at all, which is exactly what
    // readiness is for saying out loud.
    installHealthEndpoints(
        "probe-scheduler",
        listOf(
            databaseCheck(dataSource),
            redisCheck("redis-a") { redis },
        ),
    )

    log.info("probe-scheduler started")

    // Shutdown hooks
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        dispatchQueue.close()
        executionBackend.close()
        syncService.stop()
        quartzManager.shutdown()
        // Closes every per-agent client the factory built (shared by dispatch
        // and health challenges).
        agentClientFactory.close()
        redisConn.close()
        dataSource.close()
        log.info("probe-scheduler shut down")
    }
}
