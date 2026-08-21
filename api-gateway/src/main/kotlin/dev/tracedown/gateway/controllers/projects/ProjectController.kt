package dev.tracedown.gateway.controllers.projects
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.variables.SystemVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
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
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.projects.ProjectSummary
import dev.tracedown.gateway.data.projects.UpdateProjectRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.variables.VariableLimits
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.common.variables.SystemVariableSeeder
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object ProjectController {

    /** Creates a project inside a workspace. Requires write access to the workspace. */
    @Injectable("project.create")
    fun create(orgId: UUID, workspaceId: UUID, request: CreateProjectRequest, userId: UUID): ProjectSummary {
        if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)

        // Transaction-scoped: a registered before-hook can count existing
        // projects and block atomically with the insert below.
        return Interceptors.injectableInTx("project.create", InterceptorContext(orgId = orgId, userId = userId, workspaceId = workspaceId)) {
            ResourceResolver.resolveWorkspace(workspaceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            if (!canWriteResource(cached, "workspace", workspaceId)) {
                throw NotFoundException()
            }

            val nameTaken = Projects.selectAll()
                .where {
                    (Projects.workspaceId eq workspaceId) and
                    (Projects.name eq request.name) and
                    (Projects.deleted eq false)
                }
                .any()
            if (nameTaken) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()

            Projects.insert {
                it[Projects.id] = id
                it[Projects.workspaceId] = workspaceId
                it[name] = request.name
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = now
            }

            SystemVariableSeeder.seedProject(id, now)
            AuditService.log(orgId, userId, "create.project", "project", id.toString(), entityDisplayName = request.name)
            OutboxEmit.emitResourceEvent(
                "resource.project.created", "project", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("parentId", workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:$workspaceId", orgId, "project.created", buildJsonObject { put("projectId", id.toString()) })

            projectSummary(id)
        }
    }

    /**
     * Lists projects in a workspace the user has access to.
     * Requires at least read access to the workspace.
     * Filters by project-level resource grants if no org-level workspaces.read.
     * Enriches each project with aggregated Redis metrics from its services.
     */
    fun list(orgId: UUID, workspaceId: UUID, userId: UUID, pfs: PfsParams): Page<ProjectSummary> {
        val page = transaction {
            ResourceResolver.resolveWorkspace(workspaceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceAccess(workspaceId, cached)

            val wsKey = "workspace::$workspaceId"
            val query = Projects.selectAll()
                .where { (Projects.workspaceId eq workspaceId) and (Projects.deleted eq false) }
            query.applyFilters(pfs)
            query.applySorters(pfs)
            query
                .filter { canAccessResource(cached, "project", it[Projects.id], listOf(wsKey)) }
                .map { projectSummaryFromRow(it) }
                .toPage(pfs)
        }

        return enrichWithMetrics(page)
    }

    /** Enriches each project in a page with aggregated service metrics from Redis. */
    private fun enrichWithMetrics(page: Page<ProjectSummary>): Page<ProjectSummary> {
        if (page.items.isEmpty()) return page

        val enriched = page.items.map { project ->
            val serviceIds = transaction {
                Services.selectAll()
                    .where { (Services.projectId eq UUID.fromString(project.id)) and (Services.deleted eq false) }
                    .map { it[Services.id] }
            }
            val metrics = if (serviceIds.isNotEmpty()) {
                DashboardMetricsController.getAggregatedMetrics(serviceIds)
            } else null
            project.copy(metrics = metrics, serviceCount = serviceIds.size)
        }

        return Page(items = enriched, total = page.total, page = page.page, pageSize = page.pageSize)
    }

    /** Returns a single project. Requires access to the project. */
    fun get(orgId: UUID, projectId: UUID, userId: UUID): ProjectSummary {
        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectAccess(projectId, ctx.workspaceId, cached)
            projectSummary(projectId)
        }
    }

    /** Updates a project's name. Requires write access. */
    fun update(orgId: UUID, projectId: UUID, request: UpdateProjectRequest, userId: UUID): ProjectSummary {
        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, ctx.workspaceId, cached)

            if (request.name != null) {
                if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }

            val oldName = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()?.get(Projects.name)

            Projects.update({ Projects.id eq projectId }) {
                request.name?.let { v -> it[name] = v }
            }

            AuditService.log(
                orgId, userId, "update.project", "project", projectId.toString(),
                entityDisplayName = oldName,
                diff = auditDiff(Triple("name", oldName, request.name ?: oldName)),
            )
            OutboxEmit.emitResourceEvent(
                "resource.project.updated", "project", projectId,
                buildJsonObject { put("id", projectId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:${ctx.workspaceId}", orgId, "project.updated", buildJsonObject { put("projectId", projectId.toString()) })

            projectSummary(projectId)
        }
    }

    /** Soft-deletes a project. Requires write access. */
    fun delete(orgId: UUID, projectId: UUID, userId: UUID) {
        transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, ctx.workspaceId, cached)

            val deletedName = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull()?.get(Projects.name)

            Projects.update({ Projects.id eq projectId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.project", "project", projectId.toString(), entityDisplayName = deletedName)
            OutboxEmit.emitResourceEvent(
                "resource.project.deleted", "project", projectId,
                buildJsonObject { put("id", projectId.toString()); put("orgId", orgId.toString()); put("parentId", ctx.workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:${ctx.workspaceId}", orgId, "project.deleted", buildJsonObject { put("projectId", projectId.toString()) })
        }
        ResourceResolver.invalidateProject(projectId)
    }

    // ── Variables ──

    /** Lists variables for a project. Encrypted values are masked. Metrics shown as plaintext. */
    fun listVariables(orgId: UUID, projectId: UUID, userId: UUID, pfs: PfsParams): Page<VariableSummary> {
        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectAccess(projectId, ctx.workspaceId, cached)

            val query = ProjectVariables.selectAll()
                .where { (ProjectVariables.projectId eq projectId) and (ProjectVariables.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            Page(items = pagedQuery.map { variableSummaryFromRow(it) }, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Decrypts and returns a single project variable. Secrets cannot be revealed. */
    fun revealVariable(orgId: UUID, projectId: UUID, varId: UUID, userId: UUID): VariableSummary {
        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectAccess(projectId, ctx.workspaceId, cached)

            val row = ProjectVariables.selectAll()
                .where {
                    (ProjectVariables.id eq varId) and
                    (ProjectVariables.projectId eq projectId) and
                    (ProjectVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ProjectVariables.secret]) {
                throw BadRequestException(ErrorCodes.FORBIDDEN)
            }

            variableSummaryFromRow(row, reveal = true)
        }
    }

    /** Creates a variable on a project. Type: "secret", "variable", or "metric". */
    fun createVariable(orgId: UUID, projectId: UUID, request: CreateVariableRequest, userId: UUID): VariableSummary {
        val key = dev.tracedown.gateway.data.sanitizeVariableKey(request.key)
        if (key.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (key.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (key in SystemVariables.reservedKeys("project")) throw BadRequestException(ErrorCodes.RESERVED_KEY)

        val (secret, encrypted) = parseVariableType(request.type)

        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, ctx.workspaceId, cached)

            val exists = ProjectVariables.selectAll()
                .where {
                    (ProjectVariables.projectId eq projectId) and
                    (ProjectVariables.key eq key) and
                    (ProjectVariables.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            // One resource, one cap. Counted live so deleting a variable frees
            // the slot; system-managed rows are created elsewhere and are not
            // subject to it.
            val held = ProjectVariables.selectAll()
                .where { (ProjectVariables.projectId eq projectId) and (ProjectVariables.deleted eq false) }
                .count()
            if (VariableLimits.isFull(held)) throw BadRequestException(ErrorCodes.VARIABLE_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()
            val (storedValue, iv) = when {
                secret -> VariableCrypto.encrypt(orgId, request.value, "project", key) to null
                encrypted -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            ProjectVariables.insert {
                it[ProjectVariables.id] = id
                it[ProjectVariables.projectId] = projectId
                it[createdBy] = userId
                it[ProjectVariables.key] = key
                it[value] = storedValue
                it[ProjectVariables.secret] = secret
                it[ProjectVariables.encrypted] = encrypted
                it[valueIv] = iv
                it[createdAt] = now
                it[updatedAt] = now
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.created", "variable", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("scope", "project"); put("parentId", projectId.toString()) },
            )
            RealtimePublisher.publish("project:$projectId", orgId, "variable.changed", buildJsonObject { put("resourceType", "projects"); put("resourceId", projectId.toString()) })
            variableSummary(id)
        }
    }

    /** Updates a project variable's value. Re-encrypts if encrypted type. */
    fun updateVariable(orgId: UUID, projectId: UUID, varId: UUID, request: UpdateVariableRequest, userId: UUID): VariableSummary {
        return transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, ctx.workspaceId, cached)

            val row = ProjectVariables.selectAll()
                .where {
                    (ProjectVariables.id eq varId) and
                    (ProjectVariables.projectId eq projectId) and
                    (ProjectVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ProjectVariables.systemType] == "storage") {
                throw BadRequestException(ErrorCodes.READONLY_VARIABLE)
            }

            val (storedValue, iv) = when {
                row[ProjectVariables.secret] ->
                    VariableCrypto.encrypt(orgId, request.value, "project", row[ProjectVariables.key]) to null
                row[ProjectVariables.encrypted] -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            ProjectVariables.update({ ProjectVariables.id eq varId }) {
                it[value] = storedValue
                it[valueIv] = iv
                it[updatedAt] = Instant.now()
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.updated", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "project"); put("parentId", projectId.toString()) },
            )
            RealtimePublisher.publish("project:$projectId", orgId, "variable.changed", buildJsonObject { put("resourceType", "projects"); put("resourceId", projectId.toString()) })
            variableSummary(varId)
        }
    }

    /** Soft-deletes a project variable. System variables cannot be deleted. */
    fun deleteVariable(orgId: UUID, projectId: UUID, varId: UUID, userId: UUID) {
        transaction {
            val ctx = ResourceResolver.resolveProject(projectId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            requireProjectWriteAccess(projectId, ctx.workspaceId, cached)

            val row = ProjectVariables.selectAll()
                .where {
                    (ProjectVariables.id eq varId) and
                    (ProjectVariables.projectId eq projectId) and
                    (ProjectVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[ProjectVariables.systemType] != null) {
                throw BadRequestException(ErrorCodes.SYSTEM_VARIABLE)
            }

            ProjectVariables.update({ ProjectVariables.id eq varId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            OutboxEmit.emitResourceEvent(
                "resource.variable.deleted", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "project"); put("parentId", projectId.toString()) },
            )
            RealtimePublisher.publish("project:$projectId", orgId, "variable.changed", buildJsonObject { put("resourceType", "projects"); put("resourceId", projectId.toString()) })
        }
    }

    // ── Internals ──

    private fun requireWorkspaceAccess(workspaceId: UUID, cached: CachedPermissions) {
        if (!canAccessResource(cached, "workspace", workspaceId)) {
            throw NotFoundException()
        }
    }

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

    private fun projectSummary(id: UUID): ProjectSummary {
        val row = Projects.selectAll()
            .where { (Projects.id eq id) and (Projects.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()
        return projectSummaryFromRow(row)
    }

    private fun projectSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = ProjectSummary(
        id = row[Projects.id].toString(),
        workspaceId = row[Projects.workspaceId].toString(),
        name = row[Projects.name],
        isActive = row[Projects.isActive],
        createdAt = row[Projects.createdAt].toString(),
    )

    private fun variableSummary(id: UUID): VariableSummary {
        val row = ProjectVariables.selectAll()
            .where { (ProjectVariables.id eq id) and (ProjectVariables.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()
        return variableSummaryFromRow(row)
    }

    private fun variableSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow, reveal: Boolean = false) = VariableSummary(
        id = row[ProjectVariables.id].toString(),
        key = row[ProjectVariables.key],
        value = VariableCrypto.displayValue(
            row[ProjectVariables.value],
            row[ProjectVariables.valueIv],
            row[ProjectVariables.secret],
            row[ProjectVariables.encrypted],
            reveal,
        ),
        type = variableTypeName(row[ProjectVariables.secret], row[ProjectVariables.encrypted]),
        systemType = row[ProjectVariables.systemType],
        createdAt = row[ProjectVariables.createdAt].toString(),
        updatedAt = row[ProjectVariables.updatedAt].toString(),
    )
}
