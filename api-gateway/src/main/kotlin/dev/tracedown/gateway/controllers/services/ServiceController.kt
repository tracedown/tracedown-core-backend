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
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.VariableRevealPolicy
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
import dev.tracedown.common.models.Projects
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
import dev.tracedown.gateway.data.services.ScopedToggleResult
import dev.tracedown.gateway.data.services.SKIPPED_DETAIL_LIMIT
import dev.tracedown.gateway.data.services.SkippedService
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

    /**
     * Which addresses a probe may target. Set once at startup via [init]; the
     * permissive mode by default so an embedding that never calls it keeps the
     * behaviour a self-hosted install has always had.
     */
    private var probeTargetPolicy: dev.tracedown.common.net.ProbeTargetPolicy.Mode =
        dev.tracedown.common.net.ProbeTargetPolicy.Mode.ALLOW_PRIVATE

    fun init(probeTargetPolicy: dev.tracedown.common.net.ProbeTargetPolicy.Mode) {
        this.probeTargetPolicy = probeTargetPolicy
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
                it[saveResponseBodies] = request.saveResponseBodies ?: true
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

    /**
     * Updates a service: its configuration, its Lace script, or both. Requires
     * write access.
     *
     * **Both halves of an editor's save land in one transaction, or neither
     * does.** They used to be two requests — an unversioned config PATCH and a
     * version-checked script PATCH — issued in that order, so a save that lost a
     * race committed the config and only then learned the script was stale. The
     * result was a service wearing one editor's schedule and another's script,
     * which no one had ever reviewed together. Ordering the two requests would
     * only move the window; being one transaction closes it.
     *
     * [UpdateServiceRequest.version] is the version the editor loaded. Present,
     * it is checked against the row and a mismatch is a 409 with nothing
     * written; absent, the save is last-writer-wins, which is left available for
     * a non-interactive caller changing a single field. A save carrying a script
     * must supply it.
     *
     * A successful save bumps the version. The scheduler treats that column as
     * its "this service changed" marker, so a schedule edit now moves it too and
     * the consistency sweep sees the change even if the nudge is missed.
     */
    fun update(orgId: UUID, serviceId: UUID, request: UpdateServiceRequest, userId: UUID): ServiceSummary {
        // A script write must say what it is replacing. Rejected before the
        // transaction: it is a malformed request, not a conflict.
        if (request.script != null && request.version == null) {
            throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        }
        // Parsing is pure and can be slow — keep it off the transaction.
        if (request.script != null && validateScript(request.script).isNotEmpty()) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

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
                .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            val currentVersion = old[Services.version]
            // Optimistic concurrency. Read inside the transaction, so the row
            // this compares against is the row the update below writes.
            if (request.version != null && request.version != currentVersion) {
                throw ConflictException(ErrorCodes.VERSION_CONFLICT)
            }

            // What the service will look like once this save lands — the policy
            // below judges that, not the halves. A save changing schedule and
            // script together used to have its new schedule checked against the
            // OLD script.
            val effectiveScript = request.script ?: old[Services.script]
            val effectiveSchedule = request.schedule ?: old[Services.schedule]

            // Save-time twin of the scheduler's unverified-domain policy: a
            // script that dispatch would refuse (over the 3-call limit with
            // unverified targets) is rejected here with a clear error instead
            // of silently never running, and a schedule below the floor is
            // rejected rather than silently throttled. Bodies limits stay
            // dispatch-side — they don't make a script un-runnable.
            // Save-time half of the probe-target policy. Syntactic only — no DNS
            // in a request handler, and a host that is still a variable may
            // simply not be set yet — so this catches the literal cases (an
            // address, an internal-only name, a scheme that is not HTTP) and
            // leaves the rest to dispatch, which judges the concrete URL. The
            // point is that a script this install will never run says so when it
            // is written, rather than being accepted and silently skipped on
            // every tick.
            if (request.script != null) {
                val targets = dev.tracedown.common.net.ProbeTargetPolicy.evaluateSyntax(
                    effectiveScript,
                    resolveScopedVarsForPolicy(serviceId, ctx.projectId, ctx.workspaceId, orgId),
                    probeTargetPolicy,
                )
                if (!targets.allowed) {
                    log.info(
                        "service {} script rejected: target {} ({})",
                        serviceId, targets.url, targets.reason,
                    )
                    throw BadRequestException(ErrorCodes.BLOCKED_PROBE_TARGET)
                }
            }

            val intervalTooShort =
                DomainPolicy.minIntervalMinutes(effectiveSchedule) < DomainPolicy.MIN_INTERVAL_MINUTES
            val policyRelevant = request.script != null ||
                (request.schedule != null && intervalTooShort)
            if (!trustedDomainMode && policyRelevant) {
                val policy = DomainPolicy.evaluate(
                    effectiveScript,
                    resolveScopedVarsForPolicy(serviceId, ctx.projectId, ctx.workspaceId, orgId),
                    orgId,
                )
                if (!policy.covered) {
                    // includes() against an unverified target is a scraping oracle —
                    // refuse it at save time (dispatch would refuse it anyway).
                    if (request.script != null && policy.usesIncludes) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_INCLUDES)
                    }
                    if (request.script != null && policy.callCount > DomainPolicy.MAX_CALLS) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_CALL_LIMIT)
                    }
                    if (intervalTooShort) {
                        throw BadRequestException(ErrorCodes.UNVERIFIED_DOMAIN_INTERVAL)
                    }
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
                request.saveResponseBodies?.let { v -> it[saveResponseBodies] = v }
                request.script?.let { v -> it[script] = v }
                it[version] = currentVersion + 1
            }

            // The script change gets its own entry, with its own diff: it is the
            // part of a service anyone ever reads history for.
            if (request.script != null) {
                AuditService.log(
                    orgId, userId, "update.service.script", "service", serviceId.toString(),
                    entityDisplayName = old[Services.name],
                    diff = kotlinx.serialization.json.buildJsonObject {
                        put("version", kotlinx.serialization.json.buildJsonObject {
                            put("from", kotlinx.serialization.json.JsonPrimitive(currentVersion))
                            put("to", kotlinx.serialization.json.JsonPrimitive(currentVersion + 1))
                        })
                        put(
                            "scriptDiff",
                            kotlinx.serialization.json.JsonPrimitive(
                                LineDiff.unified(old[Services.script], request.script),
                            ),
                        )
                    }.toString(),
                )
            }

            // Only when the save actually carried configuration. A script-only
            // save through the script endpoint would otherwise log a second,
            // entirely empty "update.service" entry next to the script one.
            if (touchesConfig(request)) {
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
                        Triple(
                            "saveResponseBodies", old[Services.saveResponseBodies],
                            request.saveResponseBodies ?: old[Services.saveResponseBodies],
                        ),
                    ),
                )
            }

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
     *
     * The script-only spelling of [update], and nothing more — the version check,
     * the unverified-domain policy, the audit entry and the version bump all live
     * there, so this endpoint and an editor's combined save can never diverge on
     * what a script write means.
     */
    fun updateScript(orgId: UUID, serviceId: UUID, request: UpdateScriptRequest, userId: UUID): ServiceSummary =
        update(
            orgId,
            serviceId,
            UpdateServiceRequest(script = request.script, version = request.version),
            userId,
        )

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

    /**
     * Enables or disables every service in a project.
     *
     * @see toggleScope for why this is not a loop over [toggle] on the client.
     */
    fun toggleProjectServices(orgId: UUID, projectId: UUID, isActive: Boolean, userId: UUID): ScopedToggleResult {
        val ctx = ResourceResolver.resolveProject(projectId, orgId)
        return toggleScope(orgId, userId, isActive, "project", projectId) { cached ->
            requireProjectWriteAccess(ctx.projectId, ctx.workspaceId, cached)
            servicesIn(listOf(ctx.projectId), ctx.workspaceId)
        }
    }

    /**
     * Enables or disables every service in every project of a workspace.
     *
     * @see toggleScope
     */
    fun toggleWorkspaceServices(orgId: UUID, workspaceId: UUID, isActive: Boolean, userId: UUID): ScopedToggleResult {
        ResourceResolver.resolveWorkspace(workspaceId, orgId)
        return toggleScope(orgId, userId, isActive, "workspace", workspaceId) { cached ->
            requireWorkspaceWriteAccess(workspaceId, cached)
            val projectIds = Projects.selectAll()
                .where { (Projects.workspaceId eq workspaceId) and (Projects.deleted eq false) }
                .map { it[Projects.id] }
            servicesIn(projectIds, workspaceId)
        }
    }

    /** A service the scope swept up, with the parents its permission check needs. */
    private data class ScopedService(
        val id: UUID,
        val name: String,
        val projectId: UUID,
        val workspaceId: UUID,
        val isActive: Boolean,
        val script: String,
    )

    /** Every non-deleted service under [projectIds]. Ambient transaction. */
    private fun servicesIn(projectIds: List<UUID>, workspaceId: UUID): List<ScopedService> {
        if (projectIds.isEmpty()) return emptyList()
        return Services.selectAll()
            .where { (Services.projectId inList projectIds) and (Services.deleted eq false) }
            .map {
                ScopedService(
                    id = it[Services.id],
                    name = it[Services.name],
                    projectId = it[Services.projectId],
                    workspaceId = workspaceId,
                    isActive = it[Services.isActive],
                    script = it[Services.script],
                )
            }
    }

    /**
     * The shared body of the scoped toggles: resolve the scope's services, then
     * apply the ordinary single-service toggle to each one that needs it.
     *
     * ## Why this exists
     *
     * Toggling one service at a time stops being workable somewhere around a
     * dozen, and a project can hold hundreds. The batch endpoint is no answer:
     * it caps at ten sub-requests and each is its own transaction, so switching
     * a project back on becomes twenty round-trips that can half-fail. Anything
     * that switches many services off at once — an operator quieting a noisy
     * environment, a host application acting on its own policy — otherwise
     * leaves no practical way back.
     *
     * ## Why it re-enters `service.toggle` per service
     *
     * Every guard, hook and side effect that governs toggling one service has to
     * govern toggling two hundred, and the only way to be sure of that is to run
     * the same operation rather than a parallel implementation of it. So each
     * service goes through [Interceptors] under the same `service.toggle` key,
     * writes the same audit row, and emits the same outbox and realtime events.
     * A host application that gates that operation gates this one for free, and
     * cannot be circumvented by reaching for the scope instead.
     *
     * All of it runs in ONE transaction, so a hook that refuses part-way leaves
     * nothing changed rather than a project half switched on. Nested
     * `injectableInTx` calls join this transaction rather than opening their own.
     *
     * Services already in the requested state are passed over entirely, hooks
     * included: asking to enable what is already enabled must not be put to the
     * hooks, or a re-run could be refused where the first run succeeded.
     */
    private fun toggleScope(
        orgId: UUID,
        userId: UUID,
        isActive: Boolean,
        scopeType: String,
        scopeId: UUID,
        resolve: (CachedPermissions) -> List<ScopedService>,
    ): ScopedToggleResult {
        val skipped = mutableListOf<SkippedService>()
        val changedIds = mutableListOf<Pair<UUID, UUID>>()
        var matched = 0
        var unchanged = 0
        var skippedTotal = 0
        val skippedByReason = mutableMapOf<String, Int>()

        // Counts every skip; keeps only the first [SKIPPED_DETAIL_LIMIT] by name.
        // Capped as it is built rather than trimmed at the end, so a scope of
        // thousands never materialises thousands of rows to throw most away.
        // The per-reason tally is not capped — it is a handful of integers, and
        // it is the part that survives being useful at any size.
        fun skip(svc: ScopedService, reason: String) {
            skippedTotal++
            skippedByReason.merge(reason, 1, Int::plus)
            if (skipped.size < SKIPPED_DETAIL_LIMIT) {
                skipped += SkippedService(svc.id.toString(), svc.name, reason)
            }
        }

        transaction {
            val cached = requireCachedPermissions(orgId, userId)
            val services = resolve(cached)
            matched = services.size

            for (svc in services) {
                if (svc.isActive == isActive) {
                    unchanged++
                    continue
                }
                // Scope write access is not service write access: a member can
                // hold the project and still be denied one service inside it.
                if (!canWriteResource(cached, "service", svc.id, listOf("project::${svc.projectId}", "workspace::${svc.workspaceId}"))) {
                    skip(svc, "forbidden")
                    continue
                }
                if (isActive) {
                    // Same precondition as the single toggle: a service with no
                    // script, or one that no longer validates, must not reach
                    // dispatch. Skipped rather than fatal — one stale script in a
                    // project of two hundred should not veto the other 199.
                    if (svc.script.isBlank()) {
                        skip(svc, "script_missing")
                        continue
                    }
                    if (validateScript(svc.script).isNotEmpty()) {
                        skip(svc, "script_invalid")
                        continue
                    }
                }

                Interceptors.injectableInTx(
                    "service.toggle",
                    InterceptorContext(
                        orgId = orgId, userId = userId, workspaceId = svc.workspaceId,
                        projectId = svc.projectId, serviceId = svc.id,
                        extra = mutableMapOf("isActive" to isActive),
                    ),
                ) {
                    Services.update({ (Services.id eq svc.id) and (Services.projectId eq svc.projectId) }) {
                        it[Services.isActive] = isActive
                    }
                    AuditService.log(
                        orgId, userId, if (isActive) "enable.service" else "disable.service",
                        "service", svc.id.toString(),
                        entityDisplayName = svc.name,
                        diff = auditDiff(Triple("isActive", !isActive, isActive)),
                    )
                    OutboxEmit.emitResourceEvent(
                        "resource.service.updated", "service", svc.id,
                        buildJsonObject {
                            put("id", svc.id.toString()); put("orgId", orgId.toString())
                            put("parentId", svc.projectId.toString())
                        },
                    )
                }
                changedIds += svc.id to svc.projectId
            }

            // The scope itself is the fact an operator will look for later — the
            // per-service rows say what moved, this says one person asked for all
            // of it at once.
            if (changedIds.isNotEmpty()) {
                AuditService.log(
                    orgId, userId,
                    if (isActive) "enable.services.scope" else "disable.services.scope",
                    scopeType, scopeId.toString(),
                    comment = "${changedIds.size} of $matched service(s)",
                )
            }
        }

        // After commit: nothing here may run against work that rolled back.
        for ((serviceId, projectId) in changedIds) {
            publishNudge(serviceId)
            RealtimePublisher.publish(
                "project:$projectId", orgId, "service.updated",
                buildJsonObject { put("serviceId", serviceId.toString()) },
            )
        }

        return ScopedToggleResult(
            matched = matched,
            changed = changedIds.size,
            unchanged = unchanged,
            skipped = skipped,
            skippedTotal = skippedTotal,
            skippedByReason = skippedByReason,
        )
    }

    /** True when the save carries at least one configuration field. */
    private fun touchesConfig(request: UpdateServiceRequest): Boolean =
        request.name != null || request.label != null || request.schedule != null ||
        request.probeMode != null || request.queuePolicy != null ||
        request.serviceWindow != null || request.saveResponseBodies != null

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

    /**
     * Decrypts and returns a single service variable. Secrets cannot be
     * revealed, and reveal is a write-level operation — see [VariableRevealPolicy].
     */
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

            val canWrite = canWriteResource(
                cached, "service", ctx.serviceId,
                listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"),
            )
            when (VariableRevealPolicy.decide(row[ServiceVariables.secret], canWrite)) {
                VariableRevealPolicy.Decision.REFUSED_SECRET ->
                    throw BadRequestException(ErrorCodes.FORBIDDEN)
                VariableRevealPolicy.Decision.REFUSED_READ_ONLY ->
                    throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
                VariableRevealPolicy.Decision.REVEAL -> Unit
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

    private fun requireWorkspaceWriteAccess(workspaceId: UUID, cached: CachedPermissions) {
        if (!canWriteResource(cached, "workspace", workspaceId, emptyList())) {
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
        saveResponseBodies = row[Services.saveResponseBodies],
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
