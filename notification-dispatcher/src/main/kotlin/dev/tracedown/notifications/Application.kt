package dev.tracedown.notifications

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.installHealthEndpoints
import dev.tracedown.common.health.redisCheck
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.notifications.config.DispatcherConfig
import dev.tracedown.notifications.consumers.EmailStatusConsumer
import dev.tracedown.notifications.consumers.OutboxConsumer
import dev.tracedown.notifications.delivery.EmailDeliveryService
import dev.tracedown.notifications.delivery.WebhookCircuitBreaker
import dev.tracedown.notifications.delivery.WebhookDeliveryService
import dev.tracedown.notifications.processing.NotificationProcessor
import dev.tracedown.notifications.recipients.RecipientCooldown
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.notifications.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB, Redis, and the outbox consumer. */
fun Application.module() {
    val config = DispatcherConfig.load(environment)

    // Fail fast in production if the insecure all-zero platform key is still
    // set. This service decrypts the org and webhook variables that carry
    // webhook credentials, so a key that does not match the gateway's does not
    // announce itself at startup — it silently leaves every $o./$h. reference
    // unresolved and fails those deliveries at runtime, one alert at a time.
    // No-op in dev (see SecretGuard).
    dev.tracedown.common.config.SecretGuard.requireSecure(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "notification-dispatcher",
        mapOf("PLATFORM_AES_KEY (all-zero dev default)" to (config.aesKey == "0".repeat(64))),
    )

    // Database
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
    )

    // Decrypts org variables referenced from webhook URLs at delivery time.
    VariableCrypto.init(config.aesKey)

    // Redis: sync connection for publishing, pub/sub for nudge subscription
    val redisConn = RedisFactory.createConnection(config.redisAUrl)
    val redis = redisConn.sync()
    val pubSubConnection = RedisFactory.createPubSubConnection(config.redisAUrl)

    // Services
    val emailPublisher = EmailPublisher(redis)
    val emailDeliveryService = EmailDeliveryService(emailPublisher)
    val webhookDeliveryService = WebhookDeliveryService(
        retryBaseSeconds = config.webhookRetryBaseSeconds,
        workerCount = config.webhookWorkers,
        queueCapacity = config.webhookQueueCapacity,
        breaker = WebhookCircuitBreaker(
            failureThreshold = config.webhookBreakerFailures,
            openMillis = config.webhookBreakerOpenSeconds * 1000L,
        ),
    )
    val recipientCooldown = RecipientCooldown(redis, config.recipientCooldownSeconds)
    val processor = NotificationProcessor(emailDeliveryService, webhookDeliveryService, recipientCooldown)

    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Webhook delivery workers must be draining before anything can queue onto
    // them — retry backoff runs here, off the outbox pipeline.
    webhookDeliveryService.start(consumerScope)

    // Outbox consumer
    val consumer = OutboxConsumer(processor, pubSubConnection, config.pollIntervalMs, config.batchSize)
    consumer.start(consumerScope)

    // Email status consumer — dedicated connection because BRPOP blocks it, and
    // a blocking one so its pop timeout outlives the default command timeout.
    val statusRedisConn = RedisFactory.createBlockingConnection(
        config.redisAUrl, config.statusPopTimeoutSeconds,
    )
    val statusConsumer = EmailStatusConsumer(statusRedisConn.sync(), config.statusPopTimeoutSeconds)
    statusConsumer.start(consumerScope)

    // Both required: the outbox lives in Postgres and every nudge, cooldown and
    // outbound email envelope goes through Redis A. Probed on the publishing
    // connection, not the one parked in BRPOP.
    installHealthEndpoints(
        "notification-dispatcher",
        listOf(
            databaseCheck(dataSource),
            redisCheck("redis-a") { redis },
        ),
    )

    log.info("notification-dispatcher started")

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        consumer.stop()
        statusConsumer.stop()
        webhookDeliveryService.stop()
        pubSubConnection.close()
        statusRedisConn.close()
        redisConn.close()
        dataSource.close()
        log.info("notification-dispatcher shut down")
    }
}
