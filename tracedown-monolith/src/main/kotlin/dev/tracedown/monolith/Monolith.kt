package dev.tracedown.monolith

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.gateway.cli.AgentBootstrap
import dev.tracedown.gateway.cli.AgentRemove
import dev.tracedown.gateway.cli.OrgBootstrap
import dev.tracedown.gateway.cli.RewrapOrgKeys
import dev.tracedown.gateway.controllers.agents.CaService
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.scheduler.dispatch.ProbeExecutionBackends
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

/**
 * The Tracedown monolith: the whole platform in one JVM, from one jar.
 *
 * Boots, in order: schema migration (Flyway against filesystem-extracted
 * migrations — its classpath scanner cannot see inside a merged jar), the
 * internal CA (in-process, no ca-init step), every service on its usual port,
 * and last the scheduler with an embedded in-process Lace executor replacing
 * external probe agents. The frontend bundle, when baked in, is served from
 * the gateway's origin.
 *
 * Requires DATABASE_URL / DATABASE_USER / DATABASE_PASSWORD and REDIS_A_URL.
 * REDIS_B_URL defaults to REDIS_A_URL. Per-service ports come from
 * GATEWAY_PORT / METRICS_PORT / REALTIME_PORT (and default to the standard
 * ports for the rest); the shared PORT variable is deliberately ignored —
 * with every service in one process it cannot mean anything.
 */
private val log = LoggerFactory.getLogger("dev.tracedown.monolith")

private data class Service(
    val module: String,
    val portEnv: String?,
    val defaultPort: Int,
)

private val services = listOf(
    Service("api-gateway", "GATEWAY_PORT", 20714),
    Service("result-ingestor", null, 20820),
    Service("notification-dispatcher", null, 20830),
    Service("email-service", null, 20840),
    Service("metrics-service", "METRICS_PORT", 20850),
    Service("aggregate-worker", null, 20860),
    Service("realtime-service", "REALTIME_PORT", 20870),
    // The scheduler starts last: it needs the CA and the local executor seam.
    Service("probe-scheduler", null, 20810),
)

fun main(args: Array<String>) {
    // The gateway's CLI tools ride along — one binary does everything.
    if (AgentBootstrap.handle(args)) return
    if (AgentRemove.handle(args)) return
    if (OrgBootstrap.handle(args)) return
    if (RewrapOrgKeys.handle(args)) return

    val dbUrl = requireEnv("DATABASE_URL")
    val dbUser = requireEnv("DATABASE_USER")
    val dbPassword = System.getenv("DATABASE_PASSWORD") ?: ""
    val redisA = requireEnv("REDIS_A_URL")
    val redisB = System.getenv("REDIS_B_URL") ?: redisA
    val aesKey = System.getenv("PLATFORM_AES_KEY") ?: "0".repeat(64)
    val storageRoot = System.getenv("STORAGE_FILESYSTEM_ROOT") ?: "/data/bodies"

    migrate(dbUrl, dbUser, dbPassword)

    // The CA must exist before the scheduler mints its client certificate.
    // In separate-process deployments a ca-init step does this; here it is a
    // direct call.
    DatabaseFactory.init(dbUrl, dbUser, dbPassword)
    VariableCrypto.init(aesKey)
    CaService.init(aesKey)
    transaction { CaService.ensureCaRoot() }
    log.info("internal CA ready")

    // Probes execute in-process — no agents, no mTLS dispatch.
    ProbeExecutionBackends.provider = { LocalLaceExecutionBackend(storageRoot) }

    val servers = mutableListOf<EmbeddedServer<*, *>>()
    services.forEach { svc ->
        val port = svc.portEnv?.let { System.getenv(it)?.toIntOrNull() } ?: svc.defaultPort
        val config = serviceConfig(svc, port, dbUrl, dbUser, dbPassword, redisA, redisB)
        val server = embeddedServer(
            Netty,
            applicationEnvironment { this.config = HoconApplicationConfig(config) },
            configure = { connector { this.port = port } },
        )
        server.start(wait = false)
        servers.add(server)
        log.info("started {} on port {}", svc.module, port)
    }

    log.info("tracedown monolith up — gateway on port {}", System.getenv("GATEWAY_PORT") ?: 20714)

    val shutdown = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread {
        log.info("shutting down")
        servers.reversed().forEach { runCatching { it.stop(2_000, 5_000) } }
        shutdown.countDown()
    })
    shutdown.await()
}

/** Builds one service's config from its namespaced conf copy. */
private fun serviceConfig(
    svc: Service,
    port: Int,
    dbUrl: String,
    dbUser: String,
    dbPassword: String,
    redisA: String,
    redisB: String,
): Config {
    var cfg = ConfigFactory.parseResources("conf/${svc.module}/application.conf").resolve()

    // One source of truth for the shared infrastructure, PORT ignored (see
    // the file docs).
    cfg = cfg
        .withValue("ktor.deployment.port", ConfigValueFactory.fromAnyRef(port))
        .withValue("database.url", ConfigValueFactory.fromAnyRef(dbUrl))
        .withValue("database.user", ConfigValueFactory.fromAnyRef(dbUser))
        .withValue("database.password", ConfigValueFactory.fromAnyRef(dbPassword))
        .withValue("redis.a.url", ConfigValueFactory.fromAnyRef(redisA))
    if (cfg.hasPath("redis.b")) {
        cfg = cfg.withValue("redis.b.url", ConfigValueFactory.fromAnyRef(redisB))
    }

    when (svc.module) {
        "api-gateway" -> {
            // The frontend rides on the gateway's origin.
            val modules = cfg.getStringList("ktor.application.modules") +
                "dev.tracedown.monolith.FrontendModuleKt.monolithFrontend"
            cfg = cfg.withValue("ktor.application.modules", ConfigValueFactory.fromIterable(modules))
            // …which makes this edition same-origin by construction: the app and
            // the API are one process on one port, so no request from the
            // dashboard is cross-origin and no CORS header answers anything.
            // Nothing to configure — the gateway emits no CORS headers unless
            // API_CORS_ORIGINS names an origin, which an operator serving the
            // dashboard from somewhere else can still do.
        }
        "probe-scheduler" -> {
            val gatewayPort = System.getenv("GATEWAY_PORT")?.toIntOrNull() ?: 20714
            cfg = cfg.withValue(
                "scheduler.gatewayUrl",
                ConfigValueFactory.fromAnyRef(System.getenv("GATEWAY_URL") ?: "http://127.0.0.1:$gatewayPort"),
            )
            // The scheduler sizes its connection pool from its dispatch
            // concurrency, and the standalone default (50 in flight) is aimed at
            // a fleet of agents. Here probes run in-process on one box, so a
            // pool of that size would take connections from every other service
            // sharing this database for concurrency the host cannot use anyway.
            // An explicit env override still wins.
            if (System.getenv("SCHEDULER_DISPATCH_WORKERS") == null) {
                cfg = cfg.withValue("scheduler.dispatchWorkers", ConfigValueFactory.fromAnyRef(8))
            }
        }
    }
    return cfg
}

/**
 * Runs Flyway against migrations extracted to a temp directory: the merged
 * jar defeats Flyway's classpath scanner (it finds nothing and reports
 * success), but per-file resource streams work — a build-time manifest lists
 * every migration to extract.
 */
private fun migrate(dbUrl: String, dbUser: String, dbPassword: String) {
    val loader = object {}.javaClass.classLoader
    val manifest = loader.getResourceAsStream("flyway-manifest.txt")
        ?.bufferedReader()?.readLines()?.filter { it.isNotBlank() }
        ?: error("flyway-manifest.txt missing from the jar — broken build")

    val tmp = Files.createTempDirectory("tracedown-migrations-").toFile()
    manifest.forEach { path ->
        val target = File(tmp, path)
        target.parentFile.mkdirs()
        loader.getResourceAsStream(path)?.use { input ->
            target.outputStream().use { input.copyTo(it) }
        } ?: error("migration resource missing from the jar: $path")
    }

    val extracted = tmp.walkTopDown().count { it.isFile }
    log.info(
        "extracted {} of {} migration files to {} (initial_schema: {}, migrations: {})",
        extracted, manifest.size, tmp,
        File(tmp, "db/initial_schema").listFiles()?.size ?: -1,
        File(tmp, "db/migrations").listFiles()?.size ?: -1,
    )

    val dataSource = connectWithRetry(dbUrl, dbUser, dbPassword)
    try {
        val result = Flyway.configure()
            .dataSource(dataSource)
            .locations(
                "filesystem:${File(tmp, "db/initial_schema").absolutePath}",
                "filesystem:${File(tmp, "db/migrations").absolutePath}",
            )
            .table("flyway_schema_history")
            .load()
            .migrate()
        log.info("migration complete: {} migrations applied", result.migrationsExecuted)
    } finally {
        dataSource.close()
        tmp.deleteRecursively()
    }
}

private fun connectWithRetry(jdbcUrl: String, username: String, password: String): HikariDataSource {
    val maxRetries = 30
    for (attempt in 1..maxRetries) {
        try {
            val ds = HikariDataSource(HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = username
                this.password = password
                maximumPoolSize = 2
                isAutoCommit = true
                connectionTimeout = 5000
            })
            ds.connection.use { it.isValid(2) }
            return ds
        } catch (e: Exception) {
            if (attempt == maxRetries) throw e
            log.info("database not ready (attempt {}/{}) — retrying", attempt, maxRetries)
            Thread.sleep(2000)
        }
    }
    throw IllegalStateException("unreachable")
}

private fun requireEnv(name: String): String =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: error("required environment variable $name is not set")
