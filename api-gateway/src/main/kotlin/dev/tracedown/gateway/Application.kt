package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.domain.DomainVerifier
import dev.tracedown.common.domain.HttpDnsDomainVerifier
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.Users
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.controllers.domains.DomainController
import dev.tracedown.gateway.controllers.integrations.GrafanaIntegrationController
import dev.tracedown.gateway.cli.AgentBootstrap
import dev.tracedown.gateway.cli.AgentRemove
import dev.tracedown.gateway.cli.OrgBootstrap
import dev.tracedown.gateway.cli.RewrapOrgKeys
import dev.tracedown.gateway.jobs.SecretReencryption
import dev.tracedown.common.onboarding.OrgService
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.routes.pingRoute
import dev.tracedown.gateway.routes.v1.agents.AgentHealthResponse
import dev.tracedown.gateway.routes.v1.agents.AgentStatus
import dev.tracedown.gateway.routes.v1.agents.agentRoutes
import dev.tracedown.gateway.routes.v1.bulk.BulkDispatcher
import dev.tracedown.gateway.routes.v1.bulk.bulkRoutes
import dev.tracedown.gateway.routes.v1.apikeys.apiKeyRoutes
import dev.tracedown.gateway.routes.v1.auth.authRoutes
import dev.tracedown.gateway.routes.v1.alerts.systemAlertRoutes
import dev.tracedown.gateway.routes.v1.integrations.grafanaIntegrationRoutes
import dev.tracedown.gateway.routes.v1.notifications.notificationTemplateRoutes
import dev.tracedown.gateway.routes.v1.results.resultRoutes
import dev.tracedown.gateway.routes.v1.orgs.groupRoutes
import dev.tracedown.gateway.routes.v1.orgs.inviteRoutes
import dev.tracedown.gateway.routes.v1.orgs.orgSettingsRoutes
import dev.tracedown.gateway.routes.v1.audit.auditRoutes
import dev.tracedown.gateway.routes.v1.domains.domainRoutes
import dev.tracedown.gateway.routes.v1.me.meRoutes
import dev.tracedown.gateway.routes.v1.metrics.dashboardMetricsRoutes
import dev.tracedown.gateway.routes.v1.metrics.usageRoutes
import dev.tracedown.gateway.routes.v1.orgs.permissionRoutes
import dev.tracedown.gateway.routes.v1.orgs.resourceAccessRoutes
import dev.tracedown.gateway.routes.v1.agents.agentAdminRoutes
import dev.tracedown.gateway.routes.v1.presets.rulePresetRoutes
import dev.tracedown.gateway.routes.v1.projects.projectRoutes
import dev.tracedown.gateway.routes.v1.services.serviceRoutes
import dev.tracedown.gateway.routes.v1.silences.silenceRoutes
import dev.tracedown.gateway.routes.v1.webhooks.webhookRoutes
import dev.tracedown.gateway.routes.v1.workspaces.workspaceRoutes
import dev.tracedown.gateway.routes.internal.internalAgentRoutes
import dev.tracedown.gateway.routes.internal.internalHealthTokenRoutes
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.gateway.util.RateLimitConfig
import dev.tracedown.gateway.util.RateLimiter
import dev.tracedown.gateway.util.ResourceResolver
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.routing.openapi.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("dev.tracedown.gateway.Application")

fun main(args: Array<String>) {
    if (AgentBootstrap.handle(args)) return
    if (AgentRemove.handle(args)) return
    if (OrgBootstrap.handle(args)) return
    if (RewrapOrgKeys.handle(args)) return
    EngineMain.main(args)
}

fun Application.module() {
    val appConfig = AppConfig.load(environment)

    // Fail fast in production if the insecure dev defaults are still configured.
    // No-op in dev (see SecretGuard) — the all-zero key and dev JWT secret stay
    // usable there.
    dev.tracedown.common.config.SecretGuard.requireSecure(
        environment.config.propertyOrNull("deployment.environment")?.getString(),
        "api-gateway",
        mapOf(
            "PLATFORM_AES_KEY (all-zero dev default)" to (appConfig.platform.aesKey == "0".repeat(64)),
            "JWT_SECRET (dev default)" to (appConfig.jwt.secret == "default-dev-secret-change-in-production"),
        ),
    )

    DatabaseFactory.init(
        jdbcUrl = appConfig.database.url,
        username = appConfig.database.user,
        password = appConfig.database.password
    )

    AuthController.init(appConfig.platform.aesKey, appConfig.auth.totpIssuer)
    dev.tracedown.gateway.util.VariableCrypto.init(appConfig.platform.aesKey)
    dev.tracedown.gateway.controllers.agents.CaService.init(appConfig.platform.aesKey)

    val domainVerifier: DomainVerifier = if (appConfig.platform.trustedDomainMode) {
        DomainVerifier.Trusted
    } else {
        HttpDnsDomainVerifier()
    }
    DomainController.init(appConfig.platform.aesKey, domainVerifier)
    ServiceController.init(trustedDomainMode = appConfig.platform.trustedDomainMode)
    AuthController.init(trustedDomainMode = appConfig.platform.trustedDomainMode)
    GrafanaIntegrationController.init(appConfig.platform.metricsPublicUrl)

    // Redis A (operational) — lazy init, only connects when first accessed
    val redisA by lazy {
        val conn = RedisFactory.createConnection(appConfig.redis.aUrl)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { conn.close() }
        conn.sync()
    }

    ServiceController.init { redisA }
    dev.tracedown.common.realtime.RealtimePublisher.init { redisA }

    // Redis C (resource hierarchy cache) — optional, disabled if not configured
    val resourceCache = if (appConfig.redis.cUrl != null) {
        val redisCConn = RedisFactory.createConnection(appConfig.redis.cUrl!!)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { redisCConn.close() }
        dev.tracedown.common.cache.ResourceCache(redisCConn.sync(), appConfig.redis.cacheTtlSeconds)
    } else {
        log.info("Redis C not configured — resource cache disabled (DB-only mode)")
        dev.tracedown.common.cache.ResourceCache.DISABLED
    }
    ResourceResolver.init(resourceCache)

    // Redis B (ephemeral cache) — rate limiting
    val redisB by lazy {
        val conn = RedisFactory.createConnection(appConfig.redis.bUrl)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { conn.close() }
        conn.sync()
    }

    val rateLimitConfig = RateLimitConfig.load(environment.config)
    val rateLimiter = RateLimiter(redis = { redisB }, config = rateLimitConfig)

    dev.tracedown.gateway.controllers.metrics.DashboardMetricsController.init { redisB }
    dev.tracedown.gateway.controllers.metrics.UsageController.init({ redisB }, appConfig.systemLimits.resultRetentionDays)
    // Body storage, same root/bucket the agent writes and the ingestor
    // relocates in. Without the S3 config an s3:// body URI cannot be
    // presigned, so "view body" would fail for object-storage deployments;
    // confinement rejects any URI outside the configured location.
    val storageConf = environment.config
    val storageS3 = storageConf.propertyOrNull("storage.s3.endpoint")?.getString()
        ?.takeIf { it.isNotBlank() }
        ?.let { endpoint ->
            dev.tracedown.common.storage.S3Config(
                endpoint = endpoint,
                accessKey = storageConf.property("storage.s3.accessKey").getString(),
                secretKey = storageConf.property("storage.s3.secretKey").getString(),
            )
        }
    dev.tracedown.gateway.controllers.results.ProbeResultController.init(
        dev.tracedown.common.storage.BodyStorageClient(
            s3Config = storageS3,
            confinement = dev.tracedown.common.storage.BodyConfinement(
                filesystemRoot = java.nio.file.Path.of(
                    storageConf.propertyOrNull("storage.filesystemRoot")?.getString() ?: "/data/bodies",
                ),
                s3Bucket = storageConf.propertyOrNull("storage.s3.bucket")?.getString()?.takeIf { it.isNotBlank() },
                s3KeyPrefix = storageConf.propertyOrNull("storage.s3.prefix")?.getString() ?: "",
            ),
        )
    )

    val emailPublisher = EmailPublisher(redisA)

    if (appConfig.platform.singleOrgMode) {
        bootstrapSingleOrg(appConfig)
    }

    // One-time migration of legacy secret-variable ciphertexts to the per-org
    // envelope format. Idempotent — a no-op once everything is converted.
    SecretReencryption.runAsync()

    // Register bulk-dispatchable handlers (called directly, no re-auth).
    // Each handler serializes its own result to JsonElement so generic types
    // (e.g. List<T>) are correctly handled at compile time.
    val bulkJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    BulkDispatcher.get("/auth/me") { principal, _ ->
        bulkJson.encodeToJsonElement(AuthController.meWithPermissions(principal))
    }
    BulkDispatcher.get("/auth/orgs") { principal, _ ->
        bulkJson.encodeToJsonElement(AuthController.listOrgs(principal.userId))
    }
    BulkDispatcher.get("/workspaces") { principal, _ ->
        val orgId = principal.organizationId ?: throw dev.tracedown.gateway.util.BadRequestException(dev.tracedown.common.errors.ErrorCodes.NO_ORG_SELECTED)
        val pfs = dev.tracedown.common.pfs.PfsParams(page = 1, pageSize = 100)
        bulkJson.encodeToJsonElement(WorkspaceController.list(orgId, principal.userId, pfs))
    }
    BulkDispatcher.get("/agents/health") { _, _ ->
        val health = transaction {
            val statuses = ProbeAgents.selectAll()
                .where { ProbeAgents.isActive eq true }
                .map { row ->
                    AgentStatus(
                        agentSlug = row[ProbeAgents.slug],
                        status = row[ProbeAgents.lastStatus],
                        lastCheck = row[ProbeAgents.lastPing].toString(),
                        lastResponseMs = row[ProbeAgents.lastPongDeltaMs],
                    )
                }
            AgentHealthResponse(statuses = statuses)
        }
        bulkJson.encodeToJsonElement(health)
    }

    install(Resources)

    install(ContentNegotiation) {
        json(Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        })
    }

    // Validate every self-validating request body before its controller runs, so
    // a too-long or malformed value never reaches a handler or the database.
    install(RequestValidation) {
        validate<dev.tracedown.common.validation.Validatable> { body ->
            val errors = body.validate()
            if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
        }
    }

    install(StatusPages) {
        // A failed request validation is a 400 carrying the first error code, to
        // match the { "error": "<code>" } shape controllers already use.
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.reasons.firstOrNull() ?: "invalid_request")),
            )
        }
        exception<ApiException> { call, cause ->
            call.respond(cause.status, mapOf("error" to cause.code))
        }
        // A PFS filter/sort naming a non-allowlisted table/column is a bad
        // request, not a server error — surface the neutral code.
        exception<dev.tracedown.common.pfs.PfsValidationException> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to cause.code))
        }
        exception<Throwable> { call, cause ->
            log.error("Unhandled exception", cause)
            // Report the unhandled error to any registered observer (default no-op).
            // The handled exceptions above never reach here, so they are excluded.
            dev.tracedown.common.errors.ErrorReporter.report(
                "api-gateway", cause, call.request.uri, call.request.httpMethod.value,
            )
            call.respond(
                HttpStatusCode.InternalServerError,
                mapOf("error" to dev.tracedown.common.errors.ErrorCodes.INTERNAL_ERROR),
            )
        }
    }

    // Fresh log context per request: worker threads are pooled, so clear any
    // org left behind by a prior request before this one's auth boundary sets
    // its own (unauthenticated routes then log under the "system" bucket).
    install(createApplicationPlugin("LogContext") {
        onCall { dev.tracedown.common.logging.LogContext.clear() }
    })

    install(createApplicationPlugin("RateLimit") {
        onCall { call ->
            if (!rateLimitConfig.enabled) return@onCall

            val path = call.request.local.uri
            if (path == "/ping" || path.startsWith("/internal/")) return@onCall

            // Key on the real client IP, taken a trusted number of proxy hops
            // back from the TCP peer so a client-supplied XFF cannot spoof it.
            val ip = dev.tracedown.gateway.util.resolveClientIp(
                xff = call.request.headers["X-Forwarded-For"],
                directPeer = call.request.local.remoteAddress,
                trustedProxies = rateLimitConfig.trustedProxies,
            )

            // The data export fans out over many per-user queries, so it shares
            // the stricter auth tier rather than the general one.
            val tier = if (
                path.startsWith("/api/v1/auth/login") ||
                path.startsWith("/api/v1/auth/password-reset") ||
                path.startsWith("/api/v1/me/export")
            ) {
                RateLimiter.Tier.AUTH
            } else {
                RateLimiter.Tier.GENERAL
            }

            val result = rateLimiter.check(ip, tier)
            call.response.headers.append("X-RateLimit-Limit", result.limit.toString())
            call.response.headers.append("X-RateLimit-Remaining", result.remaining.toString())

            if (!result.allowed) {
                call.response.headers.append("Retry-After", result.retryAfterSeconds.toString())
                call.respond(HttpStatusCode.TooManyRequests, mapOf("error" to dev.tracedown.common.errors.ErrorCodes.RATE_LIMITED))
            }
        }
    })

    routing {
        val rootRoute = this
        get("/openapi.docs.json") {
            val doc = OpenApiDoc(
                info = OpenApiInfo("Tracedown API", "1.0"),
                servers = listOf(Server("http://127.0.0.1:8080", "Development"))
            ) + rootRoute
            call.respond(doc)
        }.hide()

        pingRoute()
        bulkRoutes()
        authRoutes(appConfig, emailPublisher)
        meRoutes()
        inviteRoutes(appConfig, emailPublisher)
        groupRoutes()
        orgSettingsRoutes(appConfig)
        permissionRoutes()
        resourceAccessRoutes()
        rulePresetRoutes()
        agentAdminRoutes()
        workspaceRoutes()
        projectRoutes()
        serviceRoutes()
        webhookRoutes()
        domainRoutes()
        silenceRoutes()
        grafanaIntegrationRoutes()
        systemAlertRoutes()
        notificationTemplateRoutes()
        apiKeyRoutes()
        resultRoutes()
        dashboardMetricsRoutes()
        usageRoutes()
        agentRoutes()
        auditRoutes()
        internalAgentRoutes()
        internalHealthTokenRoutes { redisA }
    }
}

private fun bootstrapSingleOrg(appConfig: AppConfig) {
    transaction {
        val userExists = Users.selectAll()
            .limit(1)
            .any()

        if (userExists) {
            log.info("bootstrap: user already exists, skipping")
            return@transaction
        }

        log.info("bootstrap: creating default org and demo user")

        val userId = UUID.randomUUID()
        val passwordHash = BCrypt.withDefaults().hashToString(12, appConfig.platform.demoUserPassword.toCharArray())

        Users.insert {
            it[id] = userId
            it[email] = appConfig.platform.demoUserEmail
            it[Users.passwordHash] = passwordHash
            it[displayName] = "Admin"
            it[isActive] = true
            it[deleted] = false
            it[createdAt] = Instant.now()
        }

        val orgResult = OrgService.createOrg(
            name = "Default",
            ownerId = userId,
            defaultGroups = appConfig.platform.defaultGroups,
        )

        dev.tracedown.common.audit.AuditService.log(
            orgResult.orgId, userId, "bootstrap.create-org", "org", orgResult.orgId.toString(),
            entityDisplayName = "Default",
            comment = "Single-org bootstrap"
        )

        if (appConfig.platform.seed.enabled) {
            val seedCfg = appConfig.platform.seed
            OrgBootstrap.seedData(orgResult.orgId, orgResult.workspaceId, seedCfg)
            log.info("bootstrap: seeded project '{}' with service '{}'", seedCfg.projectName, seedCfg.serviceName)
        }

        log.info("bootstrap: created demo user '{}'", appConfig.platform.demoUserEmail)
    }
}
