package dev.tracedown.gateway.controllers.presets

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgRulePresets
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.data.presets.CreateRulePresetRequest
import dev.tracedown.gateway.data.presets.RulePresetSummary
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.requireCachedPermissions
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Preset Library (spec §3.10): org-defined Lace script starters for the
 * service editor, org-wide or workspace-scoped. Defaults are seeded per org
 * at creation ([DefaultRulePresets]); creating/deleting requires write
 * access to the preset's scope.
 */
object RulePresetController {

    /** Org presets visible in the given workspace context. */
    fun list(orgId: UUID, requestingUserId: UUID, workspaceId: UUID?): List<RulePresetSummary> {
        return transaction {
            // Org-wide presets are readable by every member. A workspace-scoped
            // preset is not: its script is workspace content, and membership
            // alone was letting any member name a workspace they hold no grant
            // on and read what is stored there. Reading the scope takes the
            // same grant as writing into it takes in [requireScopeWrite].
            val cached = requireCachedPermissions(orgId, requestingUserId)
            val scope = visibleWorkspaceScope(cached, workspaceId) { inOrg(orgId, it) }

            OrgRulePresets.selectAll()
                .where {
                    (OrgRulePresets.organizationId eq orgId) and
                    (OrgRulePresets.deleted eq false) and
                    (
                        (OrgRulePresets.workspaceId eq null) or
                        (if (scope != null) OrgRulePresets.workspaceId eq scope else OrgRulePresets.workspaceId eq null)
                    )
                }
                .orderBy(OrgRulePresets.displayName)
                .map { toSummary(it) }
        }
    }

    /**
     * The workspace whose scoped presets [cached] may see, or null for the
     * org-wide list only.
     *
     * A workspace outside the org, and one the caller holds no grant on, both
     * degrade to null rather than to an error — the listing never reports
     * whether the named workspace exists.
     *
     * Pure on purpose: [inOrg] is the only part that touches the database, so
     * the decision itself is unit-testable.
     */
    internal fun visibleWorkspaceScope(
        cached: CachedPermissions,
        workspaceId: UUID?,
        inOrg: (UUID) -> Boolean,
    ): UUID? {
        if (workspaceId == null) return null
        if (!inOrg(workspaceId)) return null
        return workspaceId.takeIf { canAccessResource(cached, "workspace", it) }
    }

    /** Whether the workspace is a live workspace of this org. */
    private fun inOrg(orgId: UUID, workspaceId: UUID): Boolean =
        Workspaces.selectAll()
            .where {
                (Workspaces.id eq workspaceId) and
                (Workspaces.organizationId eq orgId) and
                (Workspaces.deleted eq false)
            }
            .any()

    /** Saves a preset into its scope. The script must be valid Lace. */
    fun create(orgId: UUID, requestingUserId: UUID, request: CreateRulePresetRequest): RulePresetSummary {
        val name = request.name.trim()
        if (name.isEmpty()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (request.script.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        // Never store a script that can't run — same validator the service
        // script save path uses.
        try {
            val ast = dev.lacelang.validator.parse(request.script)
            val sink = dev.lacelang.validator.validate(ast)
            if (sink.errors.isNotEmpty()) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        } catch (e: BadRequestException) {
            throw e
        } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        return transaction {
            val workspaceId = request.workspaceId?.let { parseUuid(it, "workspace ID") }
            requireScopeWrite(orgId, requestingUserId, workspaceId)

            val id = UUID.randomUUID()
            OrgRulePresets.insert {
                it[OrgRulePresets.id] = id
                it[organizationId] = orgId
                it[OrgRulePresets.workspaceId] = workspaceId
                it[createdBy] = requestingUserId
                it[displayName] = name
                it[script] = request.script
                it[createdAt] = Instant.now()
            }
            AuditService.log(orgId, requestingUserId, "create.rule_preset", "rule-preset", id.toString(),
                entityDisplayName = name)

            RulePresetSummary(
                id = id.toString(),
                name = name,
                script = request.script,
                scope = if (workspaceId != null) "workspace" else "org",
            )
        }
    }

    /** Three-tier deletes a preset. Requires write access to its scope. */
    fun delete(orgId: UUID, requestingUserId: UUID, presetId: UUID) {
        transaction {
            val row = OrgRulePresets.selectAll()
                .where {
                    (OrgRulePresets.id eq presetId) and
                    (OrgRulePresets.organizationId eq orgId) and
                    (OrgRulePresets.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            requireScopeWrite(orgId, requestingUserId, row[OrgRulePresets.workspaceId])

            val now = Instant.now()
            OrgRulePresets.update({ OrgRulePresets.id eq presetId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = now
            }
            AuditService.log(orgId, requestingUserId, "delete.rule_preset", "rule-preset", presetId.toString(),
                entityDisplayName = row[OrgRulePresets.displayName])
        }
    }

    /** Org-wide presets need org workspaces write; scoped ones write on the workspace. */
    private fun requireScopeWrite(orgId: UUID, userId: UUID, workspaceId: UUID?) {
        if (workspaceId == null) {
            requireOrgWrite(orgId, userId) { it.workspaces }
            return
        }
        ResourceResolver.resolveWorkspace(workspaceId, orgId)
        val cached = requireCachedPermissions(orgId, userId)
        if (!canWriteResource(cached, "workspace", workspaceId)) {
            throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
        }
    }

    private fun toSummary(row: org.jetbrains.exposed.sql.ResultRow) = RulePresetSummary(
        id = row[OrgRulePresets.id].toString(),
        name = row[OrgRulePresets.displayName],
        script = row[OrgRulePresets.script],
        scope = if (row[OrgRulePresets.workspaceId] != null) "workspace" else "org",
    )
}
