package dev.tracedown.common.health

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("dev.tracedown.common.health.HealthRoutes")

/**
 * Shared liveness and readiness endpoints.
 *
 * Every backend service is a Ktor server — the queue consumers and the job
 * runner included, they simply had no routes — so this is a route
 * registration, not a second HTTP server bolted onto a worker.
 *
 * The two endpoints answer different questions and an orchestrator must not
 * confuse them:
 *
 * - `GET /ping` — **liveness**. The process is up and its event loop is
 *   serving. Deliberately static: nothing it touches can fail, so a probe
 *   wired to it never restarts a service over a dependency outage it cannot
 *   fix by restarting.
 * - `GET /health` — **readiness**. Actually exercises the dependencies:
 *   borrows a connection from the pool and validates it, `PING`s Redis. A
 *   [HealthCheck] marked required fails the endpoint with 503; one not marked
 *   required is reported but downgrades the answer to `degraded` with 200,
 *   for dependencies the service is designed to survive without.
 *
 * Neither endpoint authenticates — a readiness probe cannot hold credentials —
 * so the body names which dependency failed and nothing more. The *reason* (a
 * driver exception, which can carry a JDBC URL or a host name) is logged
 * instead of returned.
 *
 * Responses are written directly as JSON rather than through `call.respond`,
 * because most of these services have no ContentNegotiation installed and
 * have no other reason to.
 */
class HealthCheck(
    val name: String,
    /** When true, a failure means 503. When false, it only degrades the report. */
    val required: Boolean = true,
    private val probe: () -> Boolean,
) {
    /** Runs the probe, turning any throw into a failure with its message. */
    internal fun run(): Result = try {
        if (probe()) Result(name, required, true, null)
        else Result(name, required, false, "check returned false")
    } catch (e: Exception) {
        Result(name, required, false, e.message ?: e::class.java.simpleName)
    }

    internal data class Result(
        val name: String,
        val required: Boolean,
        val healthy: Boolean,
        val detail: String?,
    )
}

/**
 * Readiness check for a JDBC pool. Borrows a real connection and validates it,
 * so a pool that has lost the database reports unhealthy instead of being
 * assumed good because the process is running.
 */
fun databaseCheck(
    dataSource: DataSource,
    name: String = "database",
    required: Boolean = true,
    validationSeconds: Int = 2,
): HealthCheck = HealthCheck(name, required) {
    dataSource.connection.use { it.isValid(validationSeconds) }
}

/**
 * Readiness check for Redis. Takes a provider so a lazily-connected instance
 * stays lazy until something actually asks about it — and so the check never
 * lands on a connection reserved for a blocking read.
 */
fun redisCheck(
    name: String = "redis",
    required: Boolean = true,
    redis: () -> RedisCommands<String, String>,
): HealthCheck = HealthCheck(name, required) {
    redis().ping().equals("PONG", ignoreCase = true)
}

/** Registers `GET /health` (readiness) on an existing route tree. */
fun Route.readinessRoute(service: String, checks: List<HealthCheck>) {
    get("/health") {
        val results = checks.map { it.run() }
        val failedRequired = results.any { it.required && !it.healthy }
        val failedOptional = results.any { !it.required && !it.healthy }
        val status = when {
            failedRequired -> "unhealthy"
            failedOptional -> "degraded"
            else -> "ok"
        }
        results.filterNot { it.healthy }.forEach {
            log.warn(
                "readiness check '{}' failed for {} ({}): {}",
                it.name, service, if (it.required) "required" else "optional", it.detail,
            )
        }
        val body = buildJsonObject {
            put("status", status)
            put("service", service)
            putJsonObject("checks") {
                results.forEach { result ->
                    putJsonObject(result.name) {
                        put("status", if (result.healthy) "ok" else "failed")
                        put("required", result.required)
                    }
                }
            }
        }
        call.respondText(
            body.toString(),
            ContentType.Application.Json,
            if (failedRequired) HttpStatusCode.ServiceUnavailable else HttpStatusCode.OK,
        )
    }
}

/** Registers `GET /ping` (liveness) on an existing route tree. */
fun Route.livenessRoute() {
    get("/ping") {
        call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
    }
}

/**
 * Installs the health endpoints on a service that has no route tree of its own
 * — one line in a worker's `module()`.
 *
 * [liveness] is left on for services with no other routes and turned off for
 * the two that already serve their own `/ping` (api-gateway, realtime-service),
 * whose existing response shape callers depend on.
 */
fun Application.installHealthEndpoints(
    service: String,
    checks: List<HealthCheck> = emptyList(),
    liveness: Boolean = true,
) {
    routing {
        if (liveness) livenessRoute()
        readinessRoute(service, checks)
    }
}
