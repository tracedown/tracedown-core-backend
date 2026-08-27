package dev.tracedown.realtime

import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.config.SecretGuard
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.installHealthEndpoints
import dev.tracedown.common.health.redisCheck
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.realtime.config.RealtimeConfig
import dev.tracedown.realtime.listeners.EventRouter
import dev.tracedown.realtime.routes.realtimeRoutes
import dev.tracedown.realtime.ws.ConnectionManager
import dev.tracedown.realtime.routes.BEARER_SUBPROTOCOL
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.request.header
import io.ktor.server.response.header
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.realtime.Application")

fun main(args: Array<String>) = EngineMain.main(args)

/** Ktor module — wires DB, Redis A (pub/sub), WebSocket, and routes. */
fun Application.module() {
    val config = RealtimeConfig.load(environment)

    // No insecure defaults of its own to guard; still reports the resolved
    // deployment environment so a misspelt DEPLOYMENT_ENV is visible here too.
    SecretGuard.announce(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "realtime-service",
    )

    // Database (for session validation)
    val dataSource = DatabaseFactory.init(
        jdbcUrl = config.database.url,
        username = config.database.user,
        password = config.database.password,
        maximumPoolSize = 5,
    )

    // Redis A (pub/sub for event subscription)
    val pubSubConnection = RedisFactory.createPubSubConnection(config.redisAUrl)

    // Redis A command connection — used to fan out client-originated collaborative
    // edits back through the same rt:* pipeline (so all replicas' subscribers get
    // them). Without it, relayed edits would only reach this instance's clients.
    val commandConnection = RedisFactory.createConnection(config.redisAUrl)
    RealtimePublisher.init { commandConnection.sync() }

    // When a browser authenticates via the `bearer` subprotocol, it will only
    // complete the handshake if the server echoes one of the offered
    // subprotocols. Select the "bearer" marker (never the token value) so the
    // token stays out of the request URL without breaking the handshake.
    install(createApplicationPlugin("WsSubprotocolSelect") {
        onCall { call ->
            val offered = call.request.header("Sec-WebSocket-Protocol") ?: return@onCall
            val hasBearer = offered.split(",").map { it.trim() }
                .any { it == BEARER_SUBPROTOCOL || it.startsWith("$BEARER_SUBPROTOCOL.") }
            if (hasBearer) {
                call.response.header("Sec-WebSocket-Protocol", BEARER_SUBPROTOCOL)
            }
        }
    })

    // WebSocket support
    install(WebSockets) {
        pingPeriodMillis = config.pingIntervalMs
        timeoutMillis = config.pingTimeoutMs
        maxFrameSize = Long.MAX_VALUE
    }

    // Connection manager + event router
    val connectionManager = ConnectionManager()
    val routerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val eventRouter = EventRouter(pubSubConnection, connectionManager, routerScope)
    eventRouter.start()
    connectionManager.startFlusher(routerScope)

    // Routes. /ping already exists here and answers "pong" in plain text, so
    // only readiness is added — callers depend on that body. Both dependencies
    // are required: sessions are validated against Postgres on every connect,
    // and with Redis A gone a socket connects and then receives nothing, which
    // is worse than reporting not-ready.
    routing {
        realtimeRoutes(connectionManager)
    }
    installHealthEndpoints(
        "realtime-service",
        listOf(
            databaseCheck(dataSource),
            redisCheck("redis-a") { commandConnection.sync() },
        ),
        liveness = false,
    )

    log.info("realtime-service started (pingInterval={}ms, pingTimeout={}ms)", config.pingIntervalMs, config.pingTimeoutMs)

    // Shutdown hooks
    monitor.subscribe(ApplicationStopped) {
        eventRouter.stop()
        routerScope.cancel()
        pubSubConnection.close()
        commandConnection.close()
        dataSource.close()
        log.info("realtime-service shut down")
    }
}
