package dev.tracedown.ingestor

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.config.SecretGuard
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.installHealthEndpoints
import dev.tracedown.common.health.redisCheck
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.ingestor.config.IngestorConfig
import dev.tracedown.ingestor.consumers.ProbeResultConsumer
import dev.tracedown.ingestor.services.BodyRelocator
import dev.tracedown.ingestor.services.ResultPersistenceService
import java.nio.file.Path
import io.ktor.server.application.Application
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.ingestor.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB, Redis, and the consumer loop. */
fun Application.module() {
    val config = IngestorConfig.load(environment)

    // This service has no insecure defaults of its own to guard, but it still
    // reports which deployment environment it resolved — an unset or misspelt
    // DEPLOYMENT_ENV is a fleet-wide condition and every log should say so.
    SecretGuard.announce(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "result-ingestor",
    )

    // Database
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
    )

    // Redis A
    val redisConn = RedisFactory.createConnection(config.redisAUrl)
    val redis = redisConn.sync()

    // Realtime events (system alerts raised on shed probes)
    dev.tracedown.common.realtime.RealtimePublisher.init { redis }

    // Body storage: relocate agent-uploaded bodies to server-derived, tenant-scoped
    // keys. Confined to the shared filesystem root / S3 bucket+prefix the agent
    // writes to, so an agent-supplied escape path (file:///app/... or a foreign
    // bucket) is rejected rather than dereferenced.
    val storage = config.storage
    val bodyStorageClient = BodyStorageClient(
        s3Config = storage.s3,
        confinement = BodyConfinement(
            filesystemRoot = Path.of(storage.filesystemRoot),
            s3Bucket = storage.s3Bucket,
            s3KeyPrefix = storage.s3Prefix,
        ),
    )
    ResultPersistenceService.init(BodyRelocator(bodyStorageClient))

    // Consumer. It gets a connection of its own: its pop is a blocking command,
    // and anything pipelined behind one waits for it — the realtime publisher
    // above shares the other connection and must not be held up by a quiet queue.
    // createBlockingConnection, not createConnection: the default command
    // timeout is shorter than this consumer's own pop timeout, and a quiet
    // queue must not read as an unresponsive server.
    val consumerConn = RedisFactory.createBlockingConnection(config.redisAUrl, config.popTimeoutSeconds)
    val consumer = ProbeResultConsumer(consumerConn.sync(), config.popTimeoutSeconds)
    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    consumer.start(consumerScope)

    // Both dependencies are required: this service exists to move rows from the
    // queue into the database. The check rides the non-consumer connection —
    // never the one parked in BLMOVE.
    installHealthEndpoints(
        "result-ingestor",
        listOf(
            databaseCheck(dataSource),
            redisCheck("redis-a") { redis },
        ),
    )

    log.info("result-ingestor started")

    // Shutdown hooks
    monitor.subscribe(io.ktor.server.application.ApplicationStopped) {
        // stop() hands back whatever the consumer still holds, so it runs while
        // its connection is still open.
        consumer.stop()
        consumerConn.close()
        redisConn.close()
        dataSource.close()
        log.info("result-ingestor shut down")
    }
}
