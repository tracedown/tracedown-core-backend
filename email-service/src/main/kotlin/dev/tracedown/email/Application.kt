package dev.tracedown.email

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.email.createTransport
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.email.config.EmailServiceConfig
import dev.tracedown.email.consumers.EmailQueueConsumer
import dev.tracedown.email.processing.EmailProcessor
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.email.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires Redis, email transport, and the queue consumer. */
fun Application.module() {
    val config = EmailServiceConfig.load(environment)
    val deployEnv = environment.config.propertyOrNull("deployment.environment")?.getString()

    // Fail fast in production if no real provider is configured: the dev `console`
    // transport logs message contents (reset links, invite tokens) instead of
    // sending. No-op in dev, where console is the intended default.
    dev.tracedown.common.config.SecretGuard.requireSecure(
        deployEnv,
        "email-service",
        mapOf(
            "EMAIL_PROVIDER (console/unset — dev transport)" to
                (config.email.provider.isBlank() || config.email.provider == "console"),
        ),
    )

    // Email transport. Full body logging (console) is a dev-only aid — never in prod.
    val emailTransport = createTransport(
        config.email,
        logBodies = !dev.tracedown.common.config.SecretGuard.isProduction(deployEnv),
    )

    // The suppression list is the only thing this service reads from the
    // database; it owns no schema and runs no migrations.
    DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
    )

    // Redis connection for BRPOP + idempotency SET NX
    val redisConn = RedisFactory.createConnection(config.redisAUrl)
    val redis = redisConn.sync()

    // Processor + Consumer
    val processor = EmailProcessor(emailTransport, redis)
    val consumer = EmailQueueConsumer(redis, processor, config.popTimeoutSeconds)
    val consumerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    consumer.start(consumerScope)

    log.info("email-service started (provider={})", config.email.provider)

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        consumer.stop()
        redisConn.close()
        emailTransport.close()
        log.info("email-service shut down")
    }
}
