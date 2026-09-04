package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.domain.DomainVerifier
import dev.tracedown.common.domain.HttpDnsDomainVerifier
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.Users
import dev.tracedown.common.redis.RedisFactory
import dev.tracedown.common.variables.VariableLimits
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
import dev.tracedown.common.health.databaseCheck
import dev.tracedown.common.health.readinessRoute
import dev.tracedown.common.health.redisCheck
import dev.tracedown.gateway.util.CorsSettings
import dev.tracedown.gateway.util.ProxyChainObserver
import dev.tracedown.gateway.util.RateLimitConfig
import dev.tracedown.gateway.util.installRequestBodyLimit
import dev.tracedown.gateway.util.RateLimiter
import dev.tracedown.gateway.util.ResourceResolver
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.*
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.netty.EngineMain
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.PayloadTooLargeException
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

    val deploymentEnv = environment.config.propertyOrNull("deployment.environment")?.getString()

    // Which browser origins may call this API. Parsed before anything else
    // touches state, so a malformed origin list fails the boot rather than
    // registering a host nobody meant. Unset is a valid, working answer — see
    // CorsSettings.
    val cors = CorsSettings.load(environment.config)

    // Fail fast in production if the insecure dev defaults are still configured.
    // No-op in dev (see SecretGuard) — the dev key and dev JWT secret stay
    // usable there.
    //
    // The credentials go in by value rather than as a comparison against a
    // literal: the values an operator is most likely to copy are whichever ones
    // the tracked example files ship, and those have never been the two
    // literals this once tested for. SecretGuard judges them structurally.
    dev.tracedown.common.config.SecretGuard.requireSecure(
        deploymentEnv,
        "api-gateway",
        checks = emptyMap(),
        credentials = mapOf(
            "PLATFORM_AES_KEY" to appConfig.platform.aesKey,
            "JWT_SECRET" to appConfig.jwt.secret,
        ),
    )

    val dataSource = DatabaseFactory.init(
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
    // How many variables one resource may hold — an operator-set guard against
    // runaway creation, identical for every organization.
    VariableLimits.init(appConfig.systemLimits.maxVarsPerResource)

    ServiceController.init(trustedDomainMode = appConfig.platform.trustedDomainMode)
    // Same resolution the scheduler makes, from the same variable, so a script
    // is refused where it is written rather than accepted and then skipped on
    // every tick.
    ServiceController.init(
        probeTargetPolicy = dev.tracedown.common.net.ProbeTargetPolicy.resolveMode(
            configured = appConfig.platform.probeTargetPolicy,
            trustedDomainMode = appConfig.platform.trustedDomainMode,
            production = dev.tracedown.common.config.SecretGuard.isProduction(deploymentEnv),
        ),
    )
    AuthController.init(trustedDomainMode = appConfig.platform.trustedDomainMode)
    GrafanaIntegrationController.init(appConfig.platform.metricsPublicUrl)
    dev.tracedown.common.agents.AgentEnrolmentAddress.install(
        dev.tracedown.common.agents.AgentEnrolmentAddress.fixed(appConfig.platform.publicUrl),
    )

    // Redis A (operational) — lazy init, only connects when first accessed
    val redisA by lazy {
        val conn = RedisFactory.createConnection(appConfig.redis.aUrl)
        monitor.subscribe(io.ktor.server.application.ApplicationStopped) { conn.close() }
        conn.sync()
    }

    ServiceController.init { redisA }
    dev.tracedown.common.realtime.RealtimePublisher.init { redisA }

    // Redis C (resource hierarchy cache) — optional, disabled if not configured.
    // Lazy like A and B: the cache is an optimisation, and connecting to it
    // during module init let an unreachable instance stop Ktor from binding.
    val resourceCache = if (appConfig.redis.cUrl != null) {
        val redisC by lazy {
            val conn = RedisFactory.createConnection(appConfig.redis.cUrl!!)
            monitor.subscribe(io.ktor.server.application.ApplicationStopped) { conn.close() }
            conn.sync()
        }
        dev.tracedown.common.cache.ResourceCache({ redisC }, appConfig.redis.cacheTtlSeconds)
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
    // A hop count that is too low is invisible from the inside: the limiter
    // works perfectly, on a key shared by the entire deployment. Say so at
    // startup when it was never configured, and watch the forwarded chains for
    // the shape that proves it wrong.
    ProxyChainObserver.warnIfDefaultInProduction(
        production = dev.tracedown.common.config.SecretGuard.isProduction(deploymentEnv),
        explicitlySet = System.getenv("TRUSTED_PROXIES") != null,
        trustedProxies = rateLimitConfig.trustedProxies,
    )
    val proxyChainObserver = ProxyChainObserver(rateLimitConfig.trustedProxies)
    // The auth tier fails CLOSED, so the limiter's store has to be the durable
    // operational instance rather than the evictable cache: an allkeys-lru
    // Redis B is allowed to drop counters, and losing it locks logins out.
    // In the default single-instance setup this is the same server either way.
    val rateLimiter = RateLimiter(redis = { redisA }, config = rateLimitConfig)

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

    // Provider, not an instance: constructing it must not force the lazy
    // connection and drag Redis into module init (see EmailPublisher).
    val emailPublisher = EmailPublisher { redisA }

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

    // Cross-origin access. The dashboard sends credentials on every request, so
    // the response has to name an exact origin — `*` is not usable with
    // credentials — which is why the allowed origins are configured rather than
    // inferred.
    //
    // Nothing configured installs nothing at all, and that is the default an
    // operator who has never heard of this variable gets. The ordinary shape —
    // the bundled Compose stack, the single-process edition, the dev proxy —
    // serves the app and the API from one origin, where no request is
    // cross-origin and no CORS header is an answer to anything. It is also the
    // safe reading of silence: no origin is handed credentialed access because
    // a variable went unset.
    if (cors.enabled) {
        install(CORS) {
            cors.hosts.forEach { (host, scheme) -> allowHost(host, schemes = listOf(scheme)) }
            log.info("CORS: allowing {} origin(s) with credentials", cors.hosts.size)
            allowCredentials = true
            allowHeader(HttpHeaders.Authorization)
            allowHeader(HttpHeaders.ContentType)
            allowMethod(HttpMethod.Get)
            allowMethod(HttpMethod.Post)
            allowMethod(HttpMethod.Put)
            allowMethod(HttpMethod.Patch)
            allowMethod(HttpMethod.Delete)
            allowMethod(HttpMethod.Options)
            // A cross-origin client cannot read a response header it was not
            // told about, and these are the ones a client acts on.
            exposeHeader("X-RateLimit-Limit")
            exposeHeader("X-RateLimit-Remaining")
            exposeHeader(HttpHeaders.RetryAfter)
        }
    } else {
        log.info(
            "CORS not installed: {} is unset, so no cross-origin headers are emitted. " +
                "Set it only if the dashboard is served from a different origin than this API.",
            CorsSettings.ORIGINS_VAR,
        )
        // A deployment that *is* cross-origin and never set the variable would
        // otherwise learn about it only from a browser console. The browser
        // tells us: a request carrying an Origin from somewhere other than the
        // host it was sent to is exactly that deployment. Said once, then the
        // latch closes — this is a hint, not a per-request warning.
        val crossOriginHintGiven = java.util.concurrent.atomic.AtomicBoolean(false)
        install(createApplicationPlugin("CorsOriginHint") {
            onCall { call ->
                if (crossOriginHintGiven.get()) return@onCall
                val origin = call.request.headers[HttpHeaders.Origin]
                val host = call.request.headers[HttpHeaders.XForwardedHost]?.substringBefore(',')?.trim()
                    ?: call.request.headers[HttpHeaders.Host]
                if (!CorsSettings.looksCrossOrigin(origin, host)) return@onCall
                if (crossOriginHintGiven.compareAndSet(false, true)) {
                    log.warn(
                        "Received a request from origin {} for host {}, but {} is unset — " +
                            "no CORS headers are being sent, so the browser will block the response. " +
                            "List that origin in {} (comma-separated, scheme://host[:port]).",
                        origin, host, CorsSettings.ORIGINS_VAR, CorsSettings.ORIGINS_VAR,
                    )
                }
            }
        })
    }

    // Before ContentNegotiation on purpose: both transform the received body and
    // the first to run wins, so the cap has to see the raw channel.
    installRequestBodyLimit(appConfig.maxRequestBodyBytes)

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
        // A body past the cap, discovered while reading it (a chunked request
        // declares no length). Ktor's own type, so the plugin does not have to
        // invent one — but it has to be mapped here or the catch-all below turns
        // it into a 500.
        exception<PayloadTooLargeException> { call, _ ->
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                mapOf("error" to dev.tracedown.common.errors.ErrorCodes.REQUEST_BODY_TOO_LARGE),
            )
        }
        exception<ApiException> { call, cause ->
            // Most codes stand alone; the few that cannot carry `details` with
            // the specifics the client needs to say what is in the way.
            val details = cause.details
            if (details == null) {
                call.respond(cause.status, mapOf("error" to cause.code))
            } else {
                call.respond(cause.status, kotlinx.serialization.json.buildJsonObject {
                    put("error", kotlinx.serialization.json.JsonPrimitive(cause.code))
                    put("details", details)
                })
            }
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

            val tier = dev.tracedown.gateway.util.rateLimitTierFor(call.request.local.uri) ?: return@onCall

            // Key on the real client IP, taken a trusted number of proxy hops
            // back from the TCP peer so a client-supplied XFF cannot spoof it.
            val xff = call.request.headers["X-Forwarded-For"]
            val ip = dev.tracedown.gateway.util.resolveClientIp(
                xff = xff,
                directPeer = call.request.local.remoteAddress,
                trustedProxies = rateLimitConfig.trustedProxies,
            )
            // Whether that key is the caller's or a proxy's is invisible from
            // any single request; this watches the shape across many of them.
            proxyChainObserver.observe(
                resolvedIp = ip,
                forwarded = xff?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList(),
            )

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

        // Readiness alongside the existing static /ping (liveness). The
        // database is required — the gateway cannot answer anything without
        // it. Redis A is reported but not required: rate limiting and email
        // queueing both degrade rather than stop, and failing readiness on a
        // Redis blip is the restart loop this pass is removing.
        readinessRoute(
            "api-gateway",
            listOf(
                databaseCheck(dataSource),
                redisCheck("redis-a", required = false) { redisA },
            ),
        )
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
