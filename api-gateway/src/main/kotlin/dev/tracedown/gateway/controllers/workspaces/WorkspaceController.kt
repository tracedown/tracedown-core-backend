package dev.tracedown.gateway.controllers.workspaces
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.VariableRevealPolicy
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.parseVariableType
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.UpdateWorkspaceRequest
import dev.tracedown.gateway.data.workspaces.WorkspaceSummary
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyFilters
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.pfs.applySorters
import dev.tracedown.common.pfs.toPage
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.variables.VariableLimits
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.common.variables.SystemVariables
import dev.tracedown.common.variables.SystemVariableSeeder
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireCachedPermissions
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

object WorkspaceController {

    /** Creates a new workspace in the organization. Requires org-level workspaces.write. */
    @Injectable("workspace.create")
    fun create(orgId: UUID, request: CreateWorkspaceRequest, userId: UUID): WorkspaceSummary {
        if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)

        // Transaction-scoped: a registered before-hook can count existing
        // workspaces and block atomically with the insert below.
        return Interceptors.injectableInTx("workspace.create", InterceptorContext(orgId = orgId, userId = userId)) {
            requireOrgWrite(orgId, userId) { it.workspaces }

            val nameTaken = Workspaces.selectAll()
                .where {
                    (Workspaces.organizationId eq orgId) and
                    (Workspaces.name eq request.name) and
                    (Workspaces.deleted eq false)
                }
                .any()
            if (nameTaken) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()

            Workspaces.insert {
                it[Workspaces.id] = id
                it[organizationId] = orgId
                it[name] = request.name
                it[deleted] = false
                it[createdAt] = now
            }

            SystemVariableSeeder.seedWorkspace(id, now)
            AuditService.log(orgId, userId, "create.workspace", "workspace", id.toString(), entityDisplayName = request.name)
            OutboxEmit.emitResourceEvent(
                "resource.workspace.created", "workspace", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "workspace.created", buildJsonObject { put("workspaceId", id.toString()) })

            workspaceSummary(id)
        }
    }

    /**
     * Lists workspaces the user has access to.
     * Owner and users with org-level workspaces.read see all.
     * Others see only workspaces with resource-level grants.
     */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<WorkspaceSummary> {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)

            val query = Workspaces.selectAll()
                .where { (Workspaces.organizationId eq orgId) and (Workspaces.deleted eq false) }
            query.applyFilters(pfs)
            query.applySorters(pfs)
            query
                .filter { canAccessResource(cached, "workspace", it[Workspaces.id]) }
                .map { workspaceSummaryFromRow(it) }
                .toPage(pfs)
        }
    }

    /** Returns a single workspace. Requires access to the workspace. */
    fun get(orgId: UUID, workspaceId: UUID, userId: UUID): WorkspaceSummary {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceAccess(workspaceId, orgId, cached)
            workspaceSummary(workspaceId, orgId)
        }
    }

    /** Updates a workspace's name. Requires write access to the workspace. */
    fun update(orgId: UUID, workspaceId: UUID, request: UpdateWorkspaceRequest, userId: UUID): WorkspaceSummary {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceWriteAccess(workspaceId, orgId, cached)

            if (request.name != null) {
                if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }

            val oldName = Workspaces.selectAll()
                .where { Workspaces.id eq workspaceId }
                .firstOrNull()?.get(Workspaces.name)

            Workspaces.update({ (Workspaces.id eq workspaceId) and (Workspaces.organizationId eq orgId) }) {
                request.name?.let { v -> it[name] = v }
            }

            AuditService.log(
                orgId, userId, "update.workspace", "workspace", workspaceId.toString(),
                entityDisplayName = oldName,
                diff = auditDiff(Triple("name", oldName, request.name ?: oldName)),
            )
            OutboxEmit.emitResourceEvent(
                "resource.workspace.updated", "workspace", workspaceId,
                buildJsonObject { put("id", workspaceId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "workspace.updated", buildJsonObject { put("workspaceId", workspaceId.toString()) })

            workspaceSummary(workspaceId)
        }
    }

    /** Soft-deletes a workspace. Requires write access to the workspace. */
    fun delete(orgId: UUID, workspaceId: UUID, userId: UUID) {
        transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceWriteAccess(workspaceId, orgId, cached)

            val deletedName = Workspaces.selectAll()
                .where { Workspaces.id eq workspaceId }
                .firstOrNull()?.get(Workspaces.name)

            Workspaces.update({ Workspaces.id eq workspaceId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.workspace", "workspace", workspaceId.toString(), entityDisplayName = deletedName)
            OutboxEmit.emitResourceEvent(
                "resource.workspace.deleted", "workspace", workspaceId,
                buildJsonObject { put("id", workspaceId.toString()); put("orgId", orgId.toString()) },
            )
            RealtimePublisher.publish("org:$orgId", orgId, "workspace.deleted", buildJsonObject { put("workspaceId", workspaceId.toString()) })
        }
    }

    // ── Variables ──

    /** Lists all variables for a workspace. Encrypted values are masked. Metrics shown as plaintext. */
    fun listVariables(orgId: UUID, workspaceId: UUID, userId: UUID, pfs: PfsParams): Page<VariableSummary> {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceAccess(workspaceId, orgId, cached)

            val query = WorkspaceVariables.selectAll()
                .where { (WorkspaceVariables.workspaceId eq workspaceId) and (WorkspaceVariables.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            Page(items = pagedQuery.map { variableSummaryFromRow(it) }, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /**
     * Decrypts and returns a single variable's value.
     * Only works for "variable" type. Secrets cannot be revealed. Metrics are
     * always plain. Reveal is a write-level operation — see [VariableRevealPolicy].
     */
    fun revealVariable(orgId: UUID, workspaceId: UUID, varId: UUID, userId: UUID): VariableSummary {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceAccess(workspaceId, orgId, cached)

            val row = WorkspaceVariables.selectAll()
                .where {
                    (WorkspaceVariables.id eq varId) and
                    (WorkspaceVariables.workspaceId eq workspaceId) and
                    (WorkspaceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            val canWrite = canWriteResource(cached, "workspace", workspaceId)
            when (VariableRevealPolicy.decide(row[WorkspaceVariables.secret], canWrite)) {
                VariableRevealPolicy.Decision.REFUSED_SECRET ->
                    throw BadRequestException(ErrorCodes.FORBIDDEN)
                VariableRevealPolicy.Decision.REFUSED_READ_ONLY ->
                    throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
                VariableRevealPolicy.Decision.REVEAL -> Unit
            }

            variableSummaryFromRow(row, reveal = true)
        }
    }

    /**
     * Creates a variable on a workspace.
     * Type must be "secret", "variable", or "metric".
     * Secret and variable values are encrypted before storage.
     */
    fun createVariable(orgId: UUID, workspaceId: UUID, request: CreateVariableRequest, userId: UUID): VariableSummary {
        val key = dev.tracedown.gateway.data.sanitizeVariableKey(request.key)
        if (key.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (key.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (key in SystemVariables.reservedKeys("workspace")) throw BadRequestException(ErrorCodes.RESERVED_KEY)

        val (secret, encrypted) = parseVariableType(request.type)

        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceWriteAccess(workspaceId, orgId, cached)

            val exists = WorkspaceVariables.selectAll()
                .where {
                    (WorkspaceVariables.workspaceId eq workspaceId) and
                    (WorkspaceVariables.key eq key) and
                    (WorkspaceVariables.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            // One resource, one cap. Counted live so deleting a variable frees
            // the slot; system-managed rows are created elsewhere and are not
            // subject to it.
            val held = WorkspaceVariables.selectAll()
                .where { (WorkspaceVariables.workspaceId eq workspaceId) and (WorkspaceVariables.deleted eq false) }
                .count()
            if (VariableLimits.isFull(held)) throw BadRequestException(ErrorCodes.VARIABLE_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()

            val (storedValue, iv) = when {
                secret -> VariableCrypto.encrypt(orgId, request.value, "workspace", key) to null
                encrypted -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            WorkspaceVariables.insert {
                it[WorkspaceVariables.id] = id
                it[WorkspaceVariables.workspaceId] = workspaceId
                it[createdBy] = userId
                it[WorkspaceVariables.key] = key
                it[value] = storedValue
                it[WorkspaceVariables.secret] = secret
                it[WorkspaceVariables.encrypted] = encrypted
                it[valueIv] = iv
                it[createdAt] = now
                it[updatedAt] = now
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.created", "variable", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("scope", "workspace"); put("parentId", workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:$workspaceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "workspaces"); put("resourceId", workspaceId.toString()) })
            variableSummary(id)
        }
    }

    /** Updates a variable's value. Re-encrypts if the variable is encrypted. */
    fun updateVariable(orgId: UUID, workspaceId: UUID, varId: UUID, request: UpdateVariableRequest, userId: UUID): VariableSummary {
        return transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceWriteAccess(workspaceId, orgId, cached)

            val row = WorkspaceVariables.selectAll()
                .where {
                    (WorkspaceVariables.id eq varId) and
                    (WorkspaceVariables.workspaceId eq workspaceId) and
                    (WorkspaceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[WorkspaceVariables.systemType] == "storage") {
                throw BadRequestException(ErrorCodes.READONLY_VARIABLE)
            }

            val (storedValue, iv) = when {
                row[WorkspaceVariables.secret] ->
                    VariableCrypto.encrypt(orgId, request.value, "workspace", row[WorkspaceVariables.key]) to null
                row[WorkspaceVariables.encrypted] -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            WorkspaceVariables.update({ WorkspaceVariables.id eq varId }) {
                it[value] = storedValue
                it[valueIv] = iv
                it[updatedAt] = Instant.now()
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.updated", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "workspace"); put("parentId", workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:$workspaceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "workspaces"); put("resourceId", workspaceId.toString()) })
            variableSummary(varId)
        }
    }

    /** Soft-deletes a variable. Requires workspace write access. System variables cannot be deleted. */
    fun deleteVariable(orgId: UUID, workspaceId: UUID, varId: UUID, userId: UUID) {
        transaction {
            val cached = requireCachedPermissions(orgId, userId)
            requireWorkspaceWriteAccess(workspaceId, orgId, cached)

            val row = WorkspaceVariables.selectAll()
                .where {
                    (WorkspaceVariables.id eq varId) and
                    (WorkspaceVariables.workspaceId eq workspaceId) and
                    (WorkspaceVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[WorkspaceVariables.systemType] != null) {
                throw BadRequestException(ErrorCodes.SYSTEM_VARIABLE)
            }

            WorkspaceVariables.update({ WorkspaceVariables.id eq varId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            OutboxEmit.emitResourceEvent(
                "resource.variable.deleted", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "workspace"); put("parentId", workspaceId.toString()) },
            )
            RealtimePublisher.publish("workspace:$workspaceId", orgId, "variable.changed", buildJsonObject { put("resourceType", "workspaces"); put("resourceId", workspaceId.toString()) })
        }
    }

    // ── Internals ──

    private fun requireWorkspaceAccess(workspaceId: UUID, orgId: UUID, cached: CachedPermissions) {
        requireWorkspaceExists(workspaceId, orgId)
        if (!canAccessResource(cached, "workspace", workspaceId)) {
            throw NotFoundException()
        }
    }

    private fun requireWorkspaceWriteAccess(workspaceId: UUID, orgId: UUID, cached: CachedPermissions) {
        requireWorkspaceExists(workspaceId, orgId)
        if (!canWriteResource(cached, "workspace", workspaceId)) {
            throw NotFoundException()
        }
    }

    private fun requireWorkspaceExists(workspaceId: UUID, orgId: UUID) {
        val exists = Workspaces.selectAll()
            .where {
                (Workspaces.id eq workspaceId) and
                (Workspaces.organizationId eq orgId) and
                (Workspaces.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun workspaceSummary(id: UUID, orgId: UUID? = null): WorkspaceSummary {
        val query = if (orgId != null) {
            Workspaces.selectAll().where {
                (Workspaces.id eq id) and (Workspaces.organizationId eq orgId) and (Workspaces.deleted eq false)
            }
        } else {
            Workspaces.selectAll().where { (Workspaces.id eq id) and (Workspaces.deleted eq false) }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return workspaceSummaryFromRow(row)
    }

    private fun workspaceSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = WorkspaceSummary(
        id = row[Workspaces.id].toString(),
        name = row[Workspaces.name],
        createdAt = row[Workspaces.createdAt].toString(),
    )

    private fun variableSummary(id: UUID): VariableSummary {
        val row = WorkspaceVariables.selectAll()
            .where { (WorkspaceVariables.id eq id) and (WorkspaceVariables.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()
        return variableSummaryFromRow(row)
    }

    private fun variableSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow, reveal: Boolean = false) = VariableSummary(
        id = row[WorkspaceVariables.id].toString(),
        key = row[WorkspaceVariables.key],
        value = VariableCrypto.displayValue(
            row[WorkspaceVariables.value],
            row[WorkspaceVariables.valueIv],
            row[WorkspaceVariables.secret],
            row[WorkspaceVariables.encrypted],
            reveal,
        ),
        type = variableTypeName(row[WorkspaceVariables.secret], row[WorkspaceVariables.encrypted]),
        systemType = row[WorkspaceVariables.systemType],
        createdAt = row[WorkspaceVariables.createdAt].toString(),
        updatedAt = row[WorkspaceVariables.updatedAt].toString(),
    )
}
