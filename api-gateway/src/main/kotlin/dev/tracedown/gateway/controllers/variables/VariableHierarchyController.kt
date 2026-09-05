package dev.tracedown.gateway.controllers.variables

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.variables.SystemVariables
import dev.tracedown.gateway.data.LockedVariable
import dev.tracedown.gateway.data.VariableHierarchyResponse
import dev.tracedown.gateway.data.VariableScope
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Builds the full inherited variable hierarchy for a resource — the resource's
 * own variables plus every ancestor's, up to the org — for the collapsible
 * variables editor. Only the requested resource's scope is editable; ancestors
 * are read-only context so users can see the values their probes actually
 * resolve. Locked (computed) variables like `$s.name` are surfaced at each
 * scope, kept in sync with the scheduler resolver via [SystemVariables].
 *
 * Access is gated on the requested resource; ancestor values are shown because
 * they materially affect a resource the caller can already see (secrets stay
 * masked — there is no reveal path for inherited variables).
 */
object VariableHierarchyController {

    fun forService(orgId: UUID, serviceId: UUID, userId: UUID): VariableHierarchyResponse = transaction {
        val ctx = ResourceResolver.resolveService(serviceId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canAccessResource(cached, "service", serviceId, listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}"))) {
            throw NotFoundException()
        }
        val service = Services.selectAll().where { Services.id eq serviceId }.firstOrNull() ?: throw NotFoundException()
        val project = Projects.selectAll().where { Projects.id eq ctx.projectId }.firstOrNull() ?: throw NotFoundException()
        val workspace = Workspaces.selectAll().where { Workspaces.id eq ctx.workspaceId }.firstOrNull() ?: throw NotFoundException()
        VariableHierarchyResponse(
            listOf(
                serviceScope(service, editable = true),
                projectScope(project, editable = false),
                workspaceScope(workspace, editable = false),
                orgScope(orgId, editable = false),
            ),
        )
    }

    fun forProject(orgId: UUID, projectId: UUID, userId: UUID): VariableHierarchyResponse = transaction {
        val ctx = ResourceResolver.resolveProject(projectId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canAccessResource(cached, "project", projectId, listOf("workspace::${ctx.workspaceId}"))) {
            throw NotFoundException()
        }
        val project = Projects.selectAll().where { Projects.id eq projectId }.firstOrNull() ?: throw NotFoundException()
        val workspace = Workspaces.selectAll().where { Workspaces.id eq ctx.workspaceId }.firstOrNull() ?: throw NotFoundException()
        VariableHierarchyResponse(
            listOf(
                projectScope(project, editable = true),
                workspaceScope(workspace, editable = false),
                orgScope(orgId, editable = false),
            ),
        )
    }

    fun forWorkspace(orgId: UUID, workspaceId: UUID, userId: UUID): VariableHierarchyResponse = transaction {
        ResourceResolver.resolveWorkspace(workspaceId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canAccessResource(cached, "workspace", workspaceId, emptyList())) {
            throw NotFoundException()
        }
        val workspace = Workspaces.selectAll().where { Workspaces.id eq workspaceId }.firstOrNull() ?: throw NotFoundException()
        VariableHierarchyResponse(
            listOf(
                workspaceScope(workspace, editable = true),
                orgScope(orgId, editable = false),
            ),
        )
    }

    // ── Scope builders ──

    private fun serviceScope(service: ResultRow, editable: Boolean) = VariableScope(
        scope = "service",
        prefix = "\$s.",
        resourceId = service[Services.id].toString(),
        resourceName = service[Services.name],
        editable = editable,
        variables = ServiceVariables.selectAll()
            .where { (ServiceVariables.serviceId eq service[Services.id]) and (ServiceVariables.deleted eq false) }
            .map { summary(it[ServiceVariables.id], it[ServiceVariables.key], it[ServiceVariables.value], it[ServiceVariables.valueIv], it[ServiceVariables.secret], it[ServiceVariables.encrypted], it[ServiceVariables.systemType], it[ServiceVariables.createdAt].toString(), it[ServiceVariables.updatedAt].toString()) },
        locked = lockedFrom(
            "service",
            mapOf(
                "name" to service[Services.name],
                "lastStatus" to (service[Services.lastStatus] ?: ""),
                "lastStatusSince" to (service[Services.lastStatusSince]?.toString() ?: ""),
                "lastStatusConsecutive" to service[Services.lastStatusConsecutive].toString(),
            ),
        ),
    )

    private fun projectScope(project: ResultRow, editable: Boolean) = VariableScope(
        scope = "project",
        prefix = "\$p.",
        resourceId = project[Projects.id].toString(),
        resourceName = project[Projects.name],
        editable = editable,
        variables = ProjectVariables.selectAll()
            .where { (ProjectVariables.projectId eq project[Projects.id]) and (ProjectVariables.deleted eq false) }
            .map { summary(it[ProjectVariables.id], it[ProjectVariables.key], it[ProjectVariables.value], it[ProjectVariables.valueIv], it[ProjectVariables.secret], it[ProjectVariables.encrypted], it[ProjectVariables.systemType], it[ProjectVariables.createdAt].toString(), it[ProjectVariables.updatedAt].toString()) },
        locked = lockedFrom("project", mapOf("name" to project[Projects.name])),
    )

    private fun workspaceScope(workspace: ResultRow, editable: Boolean) = VariableScope(
        scope = "workspace",
        prefix = "\$w.",
        resourceId = workspace[Workspaces.id].toString(),
        resourceName = workspace[Workspaces.name],
        editable = editable,
        variables = WorkspaceVariables.selectAll()
            .where { (WorkspaceVariables.workspaceId eq workspace[Workspaces.id]) and (WorkspaceVariables.deleted eq false) }
            .map { summary(it[WorkspaceVariables.id], it[WorkspaceVariables.key], it[WorkspaceVariables.value], it[WorkspaceVariables.valueIv], it[WorkspaceVariables.secret], it[WorkspaceVariables.encrypted], it[WorkspaceVariables.systemType], it[WorkspaceVariables.createdAt].toString(), it[WorkspaceVariables.updatedAt].toString()) },
        locked = lockedFrom("workspace", mapOf("name" to workspace[Workspaces.name])),
    )

    private fun orgScope(orgId: UUID, editable: Boolean): VariableScope {
        val org = Organizations.selectAll().where { Organizations.id eq orgId }.firstOrNull() ?: throw NotFoundException()
        return VariableScope(
            scope = "org",
            prefix = "\$o.",
            resourceId = orgId.toString(),
            resourceName = org[Organizations.name],
            editable = editable,
            variables = OrgVariables.selectAll()
                .where { (OrgVariables.organizationId eq orgId) and (OrgVariables.deleted eq false) }
                .map { summary(it[OrgVariables.id], it[OrgVariables.key], it[OrgVariables.value], it[OrgVariables.valueIv], it[OrgVariables.secret], it[OrgVariables.encrypted], it[OrgVariables.systemType], it[OrgVariables.createdAt].toString(), it[OrgVariables.updatedAt].toString()) },
            locked = lockedFrom("org", emptyMap()),
        )
    }

    /** Builds the locked-variable list for a scope from its computed definitions + resolved values. */
    private fun lockedFrom(scope: String, values: Map<String, String>): List<LockedVariable> =
        SystemVariables.computed(scope).map { LockedVariable(it.key, values[it.key] ?: "", it.description) }

    /** Assembles a masked [VariableSummary] from a variable row (secrets stay masked — no reveal here). */
    private fun summary(
        id: UUID,
        key: String,
        value: String,
        iv: String?,
        secret: Boolean,
        encrypted: Boolean,
        systemType: String?,
        createdAt: String,
        updatedAt: String,
    ) = VariableSummary(
        id = id.toString(),
        key = key,
        value = VariableCrypto.displayValue(value, iv, secret, encrypted, false),
        type = variableTypeName(secret, encrypted),
        systemType = systemType,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
