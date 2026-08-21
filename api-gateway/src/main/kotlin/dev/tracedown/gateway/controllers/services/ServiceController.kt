package dev.tracedown.gateway.controllers.services

import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.util.LineDiff
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ServiceVariables
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ServiceAllowedAgents
import org.jetbrains.exposed.sql.deleteWhere
import dev.tracedown.common.domain.DomainPolicy
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyFilters
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.pfs.applySorters
import dev.tracedown.common.pfs.toPage
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.parseVariableType
import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.services.ServiceSnapshot
import dev.tracedown.gateway.data.services.FailedAssertion
import dev.tracedown.gateway.data.services.LastFailureInfo
import dev.tracedown.gateway.data.services.ScriptValidationError
import dev.tracedown.gateway.data.services.ServiceSummary
import dev.tracedown.gateway.data.services.ToggleServiceRequest
import dev.tracedown.gateway.data.services.UpdateScriptRequest
import dev.tracedown.gateway.data.services.UpdateServiceRequest
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.variables.VariableLimits
import dev.tracedown.common.variables.SystemVariables
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.common.variables.SystemVariableSeeder
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.common.realtime.RealtimePublisher
import dev.tracedown.gateway.util.ServiceContext
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireCachedPermissions
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object ServiceController {

    private const val RECENT_PROBES_LIMIT = 10

    /** Mirrors platform.trustedDomainMode; set once at startup via [init]. */
    private var trustedDomainMode: Boolean = true

    fun init(trustedDomainMode: Boolean) {
        this.trustedDomainMode = trustedDomainMode
    }

    private val log = org.slf4j.LoggerFactory.getLogger(ServiceController::class.java)
    private val validProbeModes = setOf("consecutive", "simultaneous", "random")
    private val validQueuePolicies = setOf("skip", "enqueue_once")

    private var redisProvider: (() -> io.lettuce.core.api.sync.RedisCommands<String, String>)? = null

    /** Injects the Redis connection provider for schedule nudge publishing. */
    fun init(redisProvider: () -> io.lettuce.core.api.sync.RedisCommands<String, String>) {
        this.redisProvider = redisProvider
    }

    /** Publishes a schedule nudge so the scheduler picks up changes immediately. */
    private fun publishNudge(serviceId: UUID) {
        try {
            redisProvider?.invoke()?.publish("schedule:nudge", serviceId.toString())
        } catch (e: Exception) {
            log.warn("failed to publish schedule:nudge for {}: {}", serviceId, e.message)
        }
    }

    /** Publishes a run-now trigger so the scheduler dispatches one immediate probe. */
    private fun publishTriggerRun(serviceId: UUID) {
        try {
            redisProvider?.invoke()?.publish("probe:trigger", serviceId.toString())
        } catch (e: Exception) {
            log.warn("failed to publish probe:trigger for {}: {}", serviceId, e.message)
        }
    }

    /**
     * Requests an immediate one-off probe run for a service. Requires write access.
     * The actual dispatch (lock, queue policy, active/script guards) happens in the
     * scheduler when it receives the `probe:trigger` message.
     */
    fun triggerRun(orgId: UUID, serviceId: UUID, userId: UUID) {
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val svcRow = Services.selectAll()
                .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            AuditService.log(orgId, userId, "run.service", "service", serviceId.toString(), entityDisplayName = svcRow[Services.name])
        }
        publishTriggerRun(serviceId)
    }

    /** Combined detail + recent probe points for the service live channel. */
    fun snapshot(orgId: UUID, serviceId: UUID, userId: UUID): ServiceSnapshot {
        val service = get(orgId, serviceId, userId)
        val recentProbes = DashboardMetricsController.getServiceRecentProbes(serviceId, RECENT_PROBES_LIMIT)
        return ServiceSnapshot(service, recentProbes)
    }

    /** Creates a service inside a project. Requires write access to the project. */
    @Injectable("service.create")
    fun create(orgId: UUID, projectId: UUID, request: CreateServiceRequest, userId: UUID): ServiceSummary {
        if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)

        // Transaction-scoped: a registered before-hook can count existing
        // services and block atomically with the insert below.
        return Interceptors.injectableInTx(
            "service.create",
            InterceptorContext(orgId = orgId, userId = userId, projectId = projectId),
        ) {
            val pCtx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, pCtx.workspaceId, cached)

            val nameTaken = Services.selectAll()
                .where {
                    (Services.projectId eq projectId) and
                    (Services.name eq request.name) and
                    (Services.deleted eq false)
                }
                .any()
            if (nameTaken) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()

            Services.insert {
                it[Services.id] = id
                it[Services.projectId] = projectId
                it[name] = request.name
                it[label] = request.label
                it[schedule] = request.schedule ?: "*/5 * * * *"
                it[isActive] = false
                it[deleted] = false
                it[createdAt] = now
            }

            SystemVariableSeeder.seedService(id, now)
            AuditService.log(orgId, userId, "create.service", "service", id.toString(), entityDisplayName = request.name)
            OutboxEmit.emitResourceEvent(
                "resource.service.created", "service", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("parentId", projectId.toString()) },
            )

            // The workspace view shows per-project service counts — nudge it too.
            RealtimePublisher.publish("workspace:${pCtx.workspaceId}", orgId, "project.updated",
                buildJsonObject { put("projectId", projectId.toString()) })

            serviceSummary(id)
        }.also {
            publishNudge(UUID.fromString(it.id))
            RealtimePublisher.publish("project:$projectId", orgId, "service.created",
                buildJsonObject { put("serviceId", it.id) })
        }
    }

    /**
     * Lists services in a project the user has access to.
     * Requires access to the parent workspace and project.
     * Filters by service-level resource grants with downward inheritance.
     */
    fun list(orgId: UUID, projectId: UUID, userId: UUID, pfs: PfsParams): Page<ServiceSummary> {
        val page = transaction {
            val pCtx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectAccess(projectId, pCtx.workspaceId, cached)

            val parentChain = listOf("project::$projectId", "workspace::${pCtx.workspaceId}")
            val query = Services
                .join(ProbeResults, JoinType.LEFT, Services.lastRunId, ProbeResults.id)
                .select(Services.columns)
                .where { (Services.projectId eq projectId) and (Services.deleted eq false) }
            query.applyFilters(pfs)
            query.applySorters(pfs)
            query
                .filter { canAccessResource(cached, "service", it[Services.id], parentChain) }
                .map { serviceSummaryFromRow(it) }
                .toPage(pfs)
        }

        return enrichWithMetrics(page)
    }

    /** Returns a single service, enriched like the list rows. */
    fun get(orgId: UUID, serviceId: UUID, userId: UUID): ServiceSummary {
        val summary = transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)
            serviceSummary(serviceId)
        }
        // Same enrichment the list applies — consumers replace list rows with
        // this payload, so a leaner shape would erase state client-side.
        val lastFailure = if (summary.lastStatus != null && summary.lastStatus != "success") {
            extractLastFailure(serviceId)
        } else null
        return summary.copy(
            metrics = DashboardMetricsController.getServiceMetrics(serviceId),
            lastFailure = lastFailure,
        )
    }

    /** Updates service configuration. Requires write access. */
    fun update(orgId: UUID, serviceId: UUID, request: UpdateServiceRequest, userId: UUID): ServiceSummary {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            if (request.name != null) {
                if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }
            if (request.probeMode != null && request.probeMode !in validProbeModes) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
            if (request.queuePolicy != null && request.queuePolicy !in validQueuePolicies) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
            // Reject malformed windows here — the scheduler silently ignores
            // them, which would read as "maintenance window set" while active.
            // Format: RRULE[/durationMinutes[/timezone]] — timezone names
            // contain slashes, so split as rrule / duration / rest.
            if (!request.serviceWindow.isNullOrBlank()) {
                val spec = request.serviceWindow.trim()
                val parts = spec.split('/')
                if (parts.size > 1) {
                    val duration = parts[1].toLongOrNull()
                    if (duration == null || duration !in 1..1440) {
                        throw BadRequestException(ErrorCodes.FIELD_INVALID)
                    }
                }
                if (parts.size > 2) {
                    val zone = parts.drop(2).joinToString("/")
                    if (zone !in java.time.ZoneId.getAvailableZoneIds()) {
                        throw BadRequestException(ErrorCodes.FIELD_INVALID)
                    }
                }
                try {
                    RecurrenceRule(parts[0])
                } catch (_: Exception) {
                    throw BadRequestException(ErrorCodes.FIELD_INVALID)
                }
            }

            val old = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull() ?: throw NotFoundException()

            // A schedule below the unverified-domain floor is rejected here,
            // mirroring dispatch (which would silently throttle instead).
            if (!trustedDomainMode && request.schedule != null &&
                DomainPolicy.minIntervalMinutes(request.schedule) < DomainPolicy.MIN_INTERVAL_MINUTES
            ) {
                val policy = DomainPolicy.evaluate(
                    old[Services.script],
                    resolveScopedVarsForPolicy(serviceId, ctx.projectId, ctx.workspaceId, orgId),
                    orgId,
                )
                if (!policy.covered) {
                    throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_INTERVAL)
                }
            }

            Services.update({ (Services.id eq serviceId) and (Services.projectId eq ctx.projectId) }) {
                request.name?.let { v -> it[name] = v }
                request.label?.let { v -> it[label] = v }
                request.schedule?.let { v -> it[schedule] = v }
                request.probeMode?.let { v -> it[probeMode] = v }
                request.queuePolicy?.let { v -> it[queuePolicy] = v }
                // Blank clears the window (the request field is null when unchanged).
                request.serviceWindow?.let { v -> it[serviceWindow] = v.trim().ifBlank { null } }
            }

            AuditService.log(
                orgId, userId, "update.service", "service", serviceId.toString(),
                entityDisplayName = old[Services.name],
                diff = auditDiff(
                    Triple("name", old[Services.name], request.name ?: old[Services.name]),
                    Triple("label", old[Services.label], request.label ?: old[Services.label]),
                    Triple("schedule", old[Services.schedule], request.schedule ?: old[Services.schedule]),
                    Triple("probeMode", old[Services.probeMode], request.probeMode ?: old[Services.probeMode]),
                    Triple("queuePolicy", old[Services.queuePolicy], request.queuePolicy ?: old[Services.queuePolicy]),
                    Triple(
                        "serviceWindow", old[Services.serviceWindow],
                        request.serviceWindow?.trim()?.ifBlank { null } ?: old[Services.serviceWindow],
                    ),
                ),
            )

            OutboxEmit.emitResourceEvent(
                "resource.service.updated", "service", serviceId,
                buildJsonObject { put("id", serviceId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.projectId.toString()) },
            )
            serviceSummary(serviceId) to ctx.projectId
        }.let { (summary, projectId) ->
            publishNudge(serviceId)
            RealtimePublisher.publish("project:$projectId", orgId, "service.updated",
                buildJsonObject { put("serviceId", serviceId.toString()) })
            summary
        }
    }

    /** Soft-deletes a service. Requires write access. */
    fun delete(orgId: UUID, serviceId: UUID, userId: UUID) {
        val (projectId, workspaceId) = transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val deletedName = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()?.get(Services.name)

            Services.update({ Services.id eq serviceId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.service", "service", serviceId.toString(), entityDisplayName = deletedName)
            OutboxEmit.emitResourceEvent(
                "resource.service.deleted", "service", serviceId,
                buildJsonObject { put("id", serviceId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.projectId.toString()) },
            )
            ctx.projectId to ctx.workspaceId
        }
        ResourceResolver.invalidateService(serviceId)
        publishNudge(serviceId)
        RealtimePublisher.publish("project:$projectId", orgId, "service.deleted",
            buildJsonObject { put("serviceId", serviceId.toString()) })
        // The workspace view shows per-project service counts — nudge it too.
        RealtimePublisher.publish("workspace:$workspaceId", orgId, "project.updated",
            buildJsonObject { put("projectId", projectId.toString()) })
    }

    /**
     * Updates a service's Lace script. Parses and validates before saving.
     * Increments the service version on success.
     */
    fun updateScript(orgId: UUID, serviceId: UUID, request: UpdateScriptRequest, userId: UUID): ServiceSummary {
        // Validate the script
        val errors = validateScript(request.script)
        if (errors.isNotEmpty()) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        val ctx = ResourceResolver.resolveService(serviceId, orgId)
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val service = Services.selectAll()
                .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            val currentVersion = service[Services.version]
            if (currentVersion != request.version) {
                throw ConflictException(ErrorCodes.VERSION_CONFLICT)
            }

            // Save-time twin of the scheduler's unverified-domain policy: a
            // script that dispatch would refuse (over the 3-call limit with
            // unverified targets) is rejected here with a clear error instead
            // of silently never running. Bodies/interval limits stay
            // dispatch-side — they don't make a script un-runnable.
            if (!trustedDomainMode) {
                val policy = DomainPolicy.evaluate(
                    request.script,
                    resolveScopedVarsForPolicy(serviceId, ctx.projectId, ctx.workspaceId, orgId),
                    orgId,
                )
                if (!policy.covered) {
                    // includes() against an unverified target is a scraping oracle —
                    // refuse it at save time (dispatch would refuse it anyway).
                    if (policy.usesIncludes) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_INCLUDES)
                    }
                    if (policy.callCount > DomainPolicy.MAX_CALLS) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_CALL_LIMIT)
                    }
                    if (DomainPolicy.minIntervalMinutes(service[Services.schedule]) < DomainPolicy.MIN_INTERVAL_MINUTES) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_INTERVAL)
                    }
                }
            }

            Services.update({ (Services.id eq serviceId) and (Services.projectId eq ctx.projectId) }) {
                it[script] = request.script
                it[version] = currentVersion + 1
            }

            AuditService.log(
                orgId, userId, "update.service.script", "service", serviceId.toString(),
                entityDisplayName = service[Services.name],
                diff = kotlinx.serialization.json.buildJsonObject {
                    put("version", kotlinx.serialization.json.buildJsonObject {
                        put("from", kotlinx.serialization.json.JsonPrimitive(currentVersion))
                        put("to", kotlinx.serialization.json.JsonPrimitive(currentVersion + 1))
                    })
                    put(
                        "scriptDiff",
                        kotlinx.serialization.json.JsonPrimitive(
                            LineDiff.unified(service[Services.script], request.script),
                        ),
                    )
                }.toString(),
            )

            OutboxEmit.emitResourceEvent(
                "resource.service.updated", "service", serviceId,
                buildJsonObject { put("id", serviceId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.projectId.toString()) },
            )
            serviceSummary(serviceId)
        }.also {
            publishNudge(serviceId)
            RealtimePublisher.publish("project:${ctx.projectId}", orgId, "service.updated",
                buildJsonObject { put("serviceId", serviceId.toString()) })
        }
    }

    /**
     * Enables or disables a service. Enabling requires a valid non-empty script.
     *
     * Injectable so a host can gate the transition — e.g. cap how many services
     * may be enabled at once — atomically with the flip (`extra["isActive"]`
     * carries the requested direction).
     */
    @Injectable("service.toggle")
    fun toggle(orgId: UUID, serviceId: UUID, request: ToggleServiceRequest, userId: UUID): ServiceSummary {
        val ctx = ResourceResolver.resolveService(serviceId, orgId)
        return Interceptors.injectableInTx(
            "service.toggle",
            InterceptorContext(
                orgId = orgId, userId = userId, workspaceId = ctx.workspaceId,
                projectId = ctx.projectId, serviceId = serviceId,
                extra = mutableMapOf("isActive" to request.isActive),
            ),
        ) {
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            if (request.isActive) {
                // Enabling — validate that the script is non-empty and valid
                val service = Services.selectAll()
                    .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                    .firstOrNull() ?: throw NotFoundException()

                val script = service[Services.script]
                if (script.isBlank()) {
                    throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                }

                val errors = validateScript(script)
                if (errors.isNotEmpty()) {
                    throw BadRequestException(ErrorCodes.FIELD_INVALID)
                }
            }

            Services.update({ (Services.id eq serviceId) and (Services.projectId eq ctx.projectId) }) {
                it[isActive] = request.isActive
            }

            val action = if (request.isActive) "enable.service" else "disable.service"
            val svcName = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()?.get(Services.name)
            AuditService.log(
                orgId, userId, action, "service", serviceId.toString(),
                entityDisplayName = svcName,
                diff = auditDiff(Triple("isActive", !request.isActive, request.isActive)),
            )

            OutboxEmit.emitResourceEvent(
                "resource.service.updated", "service", serviceId,
                buildJsonObject { put("id", serviceId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.projectId.toString()) },
            )
            serviceSummary(serviceId)
        }.also {
            publishNudge(serviceId)
            RealtimePublisher.publish("project:${ctx.projectId}", orgId, "service.updated",
                buildJsonObject { put("serviceId", serviceId.toString()) })
        }
    }

    private fun validateScript(script: String): List<ScriptValidationError> {
        return try {
            val ast = dev.lacelang.validator.parse(script)
            val sink = dev.lacelang.validator.validate(ast)
            sink.errors.map { d ->
                ScriptValidationError(
                    code = d.code,
                    callIndex = d.callIndex,
                    field = d.field,
                    detail = d.detail,
                )
            }
        } catch (e: Exception) {
            listOf(ScriptValidationError(code = "PARSE_ERROR", detail = e.message))
        }
    }

    /** Enriches each service in a page with metrics and last failure info. */
    private fun enrichWithMetrics(page: Page<ServiceSummary>): Page<ServiceSummary> {
        if (page.items.isEmpty()) return page

        val enriched = page.items.map { service ->
            val serviceUuid = UUID.fromString(service.id)
            val metrics = DashboardMetricsController.getServiceMetrics(serviceUuid)
            val lastFailure = if (service.lastStatus != null && service.lastStatus != "success") {
                extractLastFailure(serviceUuid)
            } else null
            service.copy(metrics = metrics, lastFailure = lastFailure)
        }

        return Page(items = enriched, total = page.total, page = page.page, pageSize = page.pageSize)
    }

    /**
     * Extracts failed assertion info from the last probe result's rawResult.
     * Parses rawResult.calls[].assertions[] for failed assertions.
     */
    private fun extractLastFailure(serviceId: UUID): LastFailureInfo? {
        val rawResult = transaction {
            val service = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull() ?: return@transaction null
            val lastRunId = service[Services.lastRunId] ?: return@transaction null
            ProbeResults.selectAll()
                .where { ProbeResults.id eq lastRunId }
                .firstOrNull()
                ?.get(ProbeResults.rawResult)
        } ?: return null

        return try {
            val calls = rawResult.jsonObject["calls"]?.jsonArray ?: return null
            val failed = mutableListOf<FailedAssertion>()
            for (call in calls) {
                val assertions = call.jsonObject["assertions"]?.jsonArray ?: continue
                for (assertion in assertions) {
                    val obj = assertion.jsonObject
                    val outcome = obj["outcome"]?.jsonPrimitive?.contentOrNull
                    if (outcome == "failed") {
                        failed.add(FailedAssertion(
                            scope = obj["scope"]?.jsonPrimitive?.contentOrNull ?: "unknown",
                            expected = obj["expected"]?.jsonPrimitive?.contentOrNull,
                            actual = obj["actual"]?.jsonPrimitive?.contentOrNull,
                        ))
                    }
                }
            }
            if (failed.isEmpty()) null else LastFailureInfo(assertions = failed)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Flat variable map for [DomainPolicy] URL substitution at save time —
     * scoped keys (`s.key`, `p.key`, ...) matching raw-script references.
     * Encrypted values are skipped: their hosts can't be checked here and
     * dispatch re-evaluates with real values anyway.
     */
    private fun resolveScopedVarsForPolicy(
        serviceId: UUID,
        projectId: UUID,
        workspaceId: UUID,
        orgId: UUID,
    ): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        OrgVariables.selectAll()
            .where { (OrgVariables.organizationId eq orgId) and (OrgVariables.deleted eq false) and (OrgVariables.encrypted eq false) }
            .forEach { vars["o.${it[OrgVariables.key]}"] = it[OrgVariables.value] }
        WorkspaceVariables.selectAll()
            .where { (WorkspaceVariables.workspaceId eq workspaceId) and (WorkspaceVariables.deleted eq false) and (WorkspaceVariables.encrypted eq false) }
            .forEach { vars["w.${it[WorkspaceVariables.key]}"] = it[WorkspaceVariables.value] }
        ProjectVariables.selectAll()
            .where { (ProjectVariables.projectId eq projectId) and (ProjectVariables.deleted eq false) and (ProjectVariables.encrypted eq false) }
            .forEach { vars["p.${it[ProjectVariables.key]}"] = it[ProjectVariables.value] }
        ServiceVariables.selectAll()
            .where { (ServiceVariables.serviceId eq serviceId) and (ServiceVariables.deleted eq false) and (ServiceVariables.encrypted eq false) }
            .forEach { vars["s.${it[ServiceVariables.key]}"] = it[ServiceVariables.value] }
        return vars
    }

    // ── Allowed agents ──

    /** Slugs the service is restricted to; empty = all agents (default). */
    fun listAllowedAgents(orgId: UUID, serviceId: UUID, userId: UUID): List<String> {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            (ServiceAllowedAgents innerJoin ProbeAgents)
                .select(ProbeAgents.slug)
                .where { ServiceAllowedAgents.serviceId eq serviceId }
                .map { it[ProbeAgents.slug] }
                .sorted()
        }
    }

    /** Replaces the allowed-agent set; an empty list restores "all agents". */
    fun setAllowedAgents(orgId: UUID, serviceId: UUID, slugs: List<String>, userId: UUID): List<String> {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val agentIds = if (slugs.isEmpty()) emptyList() else {
                val rows = ProbeAgents.selectAll()
                    .where { (ProbeAgents.slug inList slugs) and (ProbeAgents.deleted eq false) }
                    .map { it[ProbeAgents.id] to it[ProbeAgents.slug] }
                if (rows.size != slugs.distinct().size) {
                    throw BadRequestException(ErrorCodes.FIELD_INVALID)
                }
                rows.map { it.first }
            }

            val oldSlugs = listAllowedAgentsInternal(serviceId)

            ServiceAllowedAgents.deleteWhere { ServiceAllowedAgents.serviceId eq serviceId }
            agentIds.forEach { agentId ->
                ServiceAllowedAgents.insert {
                    it[id] = UUID.randomUUID()
                    it[ServiceAllowedAgents.serviceId] = serviceId
                    it[probeAgentId] = agentId
                }
            }
            val svcName = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()?.get(Services.name)
            AuditService.log(
                orgId, userId, "update.service_agents", "service", serviceId.toString(),
                entityDisplayName = svcName,
                diff = auditDiff(Triple(
                    "allowedAgents",
                    oldSlugs.ifEmpty { listOf("(all)") }.joinToString(","),
                    slugs.sorted().ifEmpty { listOf("(all)") }.joinToString(","),
                )),
            )
            listAllowedAgentsInternal(serviceId)
        }
    }

    private fun listAllowedAgentsInternal(serviceId: UUID): List<String> {
        return (ServiceAllowedAgents innerJoin ProbeAgents)
            .select(ProbeAgents.slug)
            .where { ServiceAllowedAgents.serviceId eq serviceId }
            .map { it[ProbeAgents.slug] }
            .sorted()
    }

    // ── Variables ──

    /** Lists variables for a service. Encrypted values are masked. */
    fun listVariables(orgId: UUID, serviceId: UUID, userId: UUID, pfs: PfsParams): Page<VariableSummary> {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val query = ServiceVariables.selectAll()
                .where { (ServiceVariables.serviceId eq serviceId) and (ServiceVariables.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            Page(items = pagedQuery.map { variableSummaryFromRow(it) }, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Decrypts and returns a single service variable. Secrets cannot be revealed. */
    fun revealVariable(orgId: UUID, serviceId: UUID, varId: UUID, userId: UUID): VariableSummary {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val row = ServiceVariables.selectAll()
                .where {
                    (ServiceVariables.id eq varId) and
                    (ServiceVariables.serviceId eq serviceId) and
                    (ServiceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ServiceVariables.secret]) {
                throw BadRequestException(ErrorCodes.FORBIDDEN)
            }

            variableSummaryFromRow(row, reveal = true)
        }
    }

    /** Creates a variable on a service. */
    fun createVariable(orgId: UUID, serviceId: UUID, request: CreateVariableRequest, userId: UUID): VariableSummary {
        val key = dev.tracedown.gateway.data.sanitizeVariableKey(request.key)
        if (key.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (key.length > 193) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (key in SystemVariables.reservedKeys("service")) throw BadRequestException(ErrorCodes.RESERVED_KEY)

        val (secret, encrypted) = parseVariableType(request.type)

        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val exists = ServiceVariables.selectAll()
                .where {
                    (ServiceVariables.serviceId eq serviceId) and
                    (ServiceVariables.key eq key) and
                    (ServiceVariables.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            // One resource, one cap. Counted live so deleting a variable frees
            // the slot; system-managed rows are created elsewhere and are not
            // subject to it.
            val held = ServiceVariables.selectAll()
                .where { (ServiceVariables.serviceId eq serviceId) and (ServiceVariables.deleted eq false) }
                .count()
            if (VariableLimits.isFull(held)) throw BadRequestException(ErrorCodes.VARIABLE_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()
            val (storedValue, iv) = when {
                secret -> VariableCrypto.encrypt(orgId, request.value, "service", key) to null
                encrypted -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            ServiceVariables.insert {
                it[ServiceVariables.id] = id
                it[ServiceVariables.serviceId] = serviceId
                it[createdBy] = userId
                it[ServiceVariables.key] = key
                it[value] = storedValue
                it[ServiceVariables.secret] = secret
                it[ServiceVariables.encrypted] = encrypted
                it[valueIv] = iv
                it[createdAt] = now
                it[updatedAt] = now
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.created", "variable", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("scope", "service"); put("parentId", serviceId.toString()) },
            )
            RealtimePublisher.publish("service:$serviceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "services"); put("resourceId", serviceId.toString()) })
            variableSummary(id)
        }
    }

    /** Updates a service variable's value. */
    fun updateVariable(orgId: UUID, serviceId: UUID, varId: UUID, request: UpdateVariableRequest, userId: UUID): VariableSummary {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val row = ServiceVariables.selectAll()
                .where {
                    (ServiceVariables.id eq varId) and
                    (ServiceVariables.serviceId eq serviceId) and
                    (ServiceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ServiceVariables.systemType] == "storage") {
                throw BadRequestException(ErrorCodes.READONLY_VARIABLE)
            }

            val (storedValue, iv) = when {
                row[ServiceVariables.secret] ->
                    VariableCrypto.encrypt(orgId, request.value, "service", row[ServiceVariables.key]) to null
                row[ServiceVariables.encrypted] -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            ServiceVariables.update({ ServiceVariables.id eq varId }) {
                it[value] = storedValue
                it[valueIv] = iv
                it[updatedAt] = Instant.now()
            }

            // Handle companion creation/deletion for config toggles
            val varKey = row[ServiceVariables.key]
            val def = SystemVariables.SERVICE.find { it.key == varKey }
            if (def != null && def.companions.isNotEmpty()) {
                val now = Instant.now()
                if (request.value == "true") {
                    for (companionKey in def.companions) {
                        val exists = ServiceVariables.selectAll()
                            .where {
                                (ServiceVariables.serviceId eq serviceId) and
                                (ServiceVariables.key eq companionKey) and
                                (ServiceVariables.deleted eq false)
                            }.any()
                        if (!exists) {
                            ServiceVariables.insert {
                                it[id] = UUID.randomUUID()
                                it[ServiceVariables.serviceId] = serviceId
                                it[createdBy] = null
                                it[key] = companionKey
                                it[value] = "{}"
                                it[secret] = false
                                it[encrypted] = false
                                it[systemType] = "storage"
                                it[deleted] = false
                                it[createdAt] = now
                                it[updatedAt] = now
                            }
                        }
                    }
                } else {
                    ServiceVariables.update({
                        (ServiceVariables.serviceId eq serviceId) and
                        (ServiceVariables.key inList def.companions) and
                        (ServiceVariables.deleted eq false)
                    }) {
                        it[deleted] = true
                        it[deletedAt] = now
                    }
                }
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.updated", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "service"); put("parentId", serviceId.toString()) },
            )
            RealtimePublisher.publish("service:$serviceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "services"); put("resourceId", serviceId.toString()) })
            variableSummary(varId)
        }
    }

    /** Soft-deletes a service variable. */
    fun deleteVariable(orgId: UUID, serviceId: UUID, varId: UUID, userId: UUID) {
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireServiceWriteAccess(ctx.serviceId, ctx.projectId, ctx.workspaceId, cached)

            val row = ServiceVariables.selectAll()
                .where {
                    (ServiceVariables.id eq varId) and
                    (ServiceVariables.serviceId eq serviceId) and
                    (ServiceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ServiceVariables.systemType] != null) {
                throw BadRequestException(ErrorCodes.SYSTEM_VARIABLE)
            }

            ServiceVariables.update({ ServiceVariables.id eq varId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            OutboxEmit.emitResourceEvent(
                "resource.variable.deleted", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "service"); put("parentId", serviceId.toString()) },
            )
            RealtimePublisher.publish("service:$serviceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "services"); put("resourceId", serviceId.toString()) })
        }
    }

    // ── Internals ──

    private fun requireProjectAccess(projectId: UUID, workspaceId: UUID, cached: CachedPermissions) {
        val parentChain = listOf("workspace::$workspaceId")
        if (!canAccessResource(cached, "project", projectId, parentChain)) {
            throw NotFoundException()
        }
    }

    private fun requireProjectWriteAccess(projectId: UUID, workspaceId: UUID, cached: CachedPermissions) {
        val parentChain = listOf("workspace::$workspaceId")
        if (!canWriteResource(cached, "project", projectId, parentChain)) {
            throw NotFoundException()
        }
    }

    private fun requireServiceAccess(serviceId: UUID, projectId: UUID, workspaceId: UUID, cached: CachedPermissions) {
        val parentChain = listOf("project::$projectId", "workspace::$workspaceId")
        if (!canAccessResource(cached, "service", serviceId, parentChain)) {
            throw NotFoundException()
        }
    }

    private fun requireServiceWriteAccess(serviceId: UUID, projectId: UUID, workspaceId: UUID, cached: CachedPermissions) {
        val parentChain = listOf("project::$projectId", "workspace::$workspaceId")
        if (!canWriteResource(cached, "service", serviceId, parentChain)) {
            throw NotFoundException()
        }
    }

    private fun serviceSummary(id: UUID, projectId: UUID? = null): ServiceSummary {
        val query = if (projectId != null) {
            Services.selectAll().where { (Services.id eq id) and (Services.projectId eq projectId) and (Services.deleted eq false) }
        } else {
            Services.selectAll().where { (Services.id eq id) and (Services.deleted eq false) }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return serviceSummaryFromRow(row)
    }

    private fun serviceSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = ServiceSummary(
        id = row[Services.id].toString(),
        projectId = row[Services.projectId].toString(),
        name = row[Services.name],
        label = row[Services.label],
        script = row[Services.script],
        schedule = row[Services.schedule],
        probeMode = row[Services.probeMode],
        queuePolicy = row[Services.queuePolicy],
        serviceWindow = row[Services.serviceWindow],
        isActive = row[Services.isActive],
        lastStatus = row[Services.lastStatus],
        lastStatusSince = row[Services.lastStatusSince]?.toString(),
        version = row[Services.version],
        createdAt = row[Services.createdAt].toString(),
    )

    private fun variableSummary(id: UUID): VariableSummary {
        val row = ServiceVariables.selectAll()
            .where { (ServiceVariables.id eq id) and (ServiceVariables.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()
        return variableSummaryFromRow(row)
    }

    private fun variableSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow, reveal: Boolean = false) = VariableSummary(
        id = row[ServiceVariables.id].toString(),
        key = row[ServiceVariables.key],
        value = VariableCrypto.displayValue(
            row[ServiceVariables.value],
            row[ServiceVariables.valueIv],
            row[ServiceVariables.secret],
            row[ServiceVariables.encrypted],
            reveal,
        ),
        type = variableTypeName(row[ServiceVariables.secret], row[ServiceVariables.encrypted]),
        systemType = row[ServiceVariables.systemType],
        createdAt = row[ServiceVariables.createdAt].toString(),
        updatedAt = row[ServiceVariables.updatedAt].toString(),
    )
}
