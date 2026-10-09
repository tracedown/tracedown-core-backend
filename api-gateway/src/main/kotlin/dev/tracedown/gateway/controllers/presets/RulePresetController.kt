package dev.tracedown.gateway.controllers.presets

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgRulePresets
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.data.presets.CreateRulePresetRequest
import dev.tracedown.gateway.data.presets.RulePresetSummary
import dev.tracedown.gateway.data.presets.UpdateRulePresetRequest
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.jdbc.Query
import dev.tracedown.gateway.util.fieldError
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.requireCachedPermissions
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
            visibleQuery(orgId, requestingUserId, workspaceId)
                .orderBy(OrgRulePresets.displayName)
                .map { toSummary(it) }
        }
    }

    /**
     * [list], a page at a time: the same presets, by name then id so a page
     * boundary never falls between two of the same name differently.
     */
    fun listPaged(orgId: UUID, requestingUserId: UUID, workspaceId: UUID?, pfs: PfsParams): Page<RulePresetSummary> {
        return transaction {
            val (paged, total) = visibleQuery(orgId, requestingUserId, workspaceId).applyPfs(
                pfs, listOf(OrgRulePresets.displayName to SortOrder.ASC, OrgRulePresets.id to SortOrder.ASC),
            )
            Page(items = paged.map { toSummary(it) }, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /**
     * The org-wide presets, and the named workspace's when the caller may see
     * it. Call inside a transaction.
     */
    private fun visibleQuery(orgId: UUID, requestingUserId: UUID, workspaceId: UUID?): Query {
        // Org-wide presets are readable by every member. A workspace-scoped
        // preset is not: its script is workspace content, and membership
        // alone was letting any member name a workspace they hold no grant
        // on and read what is stored there. Reading the scope takes the
        // same grant as writing into it takes in [requireScopeWrite].
        val cached = requireCachedPermissions(orgId, requestingUserId)
        val scope = visibleWorkspaceScope(cached, workspaceId) { inOrg(orgId, it) }

        return OrgRulePresets.selectAll()
            .where {
                (OrgRulePresets.organizationId eq orgId) and
                (OrgRulePresets.deleted eq false) and
                (
                    (OrgRulePresets.workspaceId eq null) or
                    (if (scope != null) OrgRulePresets.workspaceId eq scope else OrgRulePresets.workspaceId eq null)
                )
            }
    }

    /**
     * One preset, when the caller may read it: an org-wide one for every
     * member, a workspace's for those who may see that workspace — the rule
     * [list] applies. One they may not read is not found, as one that does not
     * exist is.
     */
    fun get(orgId: UUID, requestingUserId: UUID, presetId: UUID): RulePresetSummary {
        return transaction { toSummary(readable(orgId, requestingUserId, presetId)) }
    }

    /**
     * The script of a preset the caller may read, for a service being created
     * in [workspaceId] — a workspace's preset only there, as the editor only
     * offers it there. Call inside a transaction.
     */
    fun scriptFor(orgId: UUID, requestingUserId: UUID, presetId: UUID, workspaceId: UUID): String {
        val row = try {
            readable(orgId, requestingUserId, presetId)
        } catch (_: NotFoundException) {
            throw presetNotFound()
        }
        val scope = row[OrgRulePresets.workspaceId]
        if (scope != null && scope != workspaceId) throw presetNotFound()
        return row[OrgRulePresets.script]
    }

    private fun readable(orgId: UUID, userId: UUID, presetId: UUID): ResultRow {
        val cached = requireCachedPermissions(orgId, userId)
        val row = OrgRulePresets.selectAll()
            .where {
                (OrgRulePresets.id eq presetId) and
                (OrgRulePresets.organizationId eq orgId) and
                (OrgRulePresets.deleted eq false)
            }
            .firstOrNull() ?: throw NotFoundException()
        val scope = row[OrgRulePresets.workspaceId]
        if (scope != null && visibleWorkspaceScope(cached, scope) { inOrg(orgId, it) } == null) throw NotFoundException()
        return row
    }

    /** 404 naming `presetId`: what a service create that names a preset it cannot use answers. */
    private fun presetNotFound() = ApiException(
        HttpStatusCode.NotFound, ErrorCodes.NOT_FOUND,
        details = buildJsonObject { put("field", "presetId") },
    )

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
        val name = validName(request.name)
        validScript(request.script)

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
                workspaceId = workspaceId?.toString(),
            )
        }
    }

    /**
     * Renames a preset or replaces its script — the checks a create makes, and
     * write access to the scope it is in. Its scope does not move.
     */
    fun update(orgId: UUID, requestingUserId: UUID, presetId: UUID, request: UpdateRulePresetRequest): RulePresetSummary {
        val name = request.name?.let(::validName)
        request.script?.let(::validScript)

        return transaction {
            // One the caller cannot read is not there for them — not a 403
            // that says it exists.
            val row = readable(orgId, requestingUserId, presetId)
            requireScopeWrite(orgId, requestingUserId, row[OrgRulePresets.workspaceId])

            OrgRulePresets.update({ OrgRulePresets.id eq presetId }) {
                if (name != null) it[displayName] = name
                if (request.script != null) it[script] = request.script
            }
            AuditService.log(
                orgId, requestingUserId, "update.rule_preset", "rule-preset", presetId.toString(),
                entityDisplayName = name ?: row[OrgRulePresets.displayName],
                diff = auditDiff(
                    Triple("name", row[OrgRulePresets.displayName], name ?: row[OrgRulePresets.displayName]),
                    Triple("script", row[OrgRulePresets.script], request.script ?: row[OrgRulePresets.script]),
                ),
            )
            toSummary(
                OrgRulePresets.selectAll().where { OrgRulePresets.id eq presetId }.first(),
            )
        }
    }

    /** A preset's name, trimmed. Names need not be unique. */
    private fun validName(raw: String): String {
        val name = raw.trim()
        if (name.isEmpty()) throw fieldError("name", ErrorCodes.FIELD_REQUIRED)
        if (name.length > 128) throw fieldError("name", ErrorCodes.FIELD_TOO_LONG)
        return name
    }

    /** Never store a script that can't run — same validator the service script save path uses. */
    private fun validScript(script: String) {
        if (script.isBlank()) throw fieldError("script", ErrorCodes.FIELD_REQUIRED)
        val valid = try {
            dev.lacelang.validator.validate(dev.lacelang.validator.parse(script)).errors.isEmpty()
        } catch (_: Exception) {
            false
        }
        if (!valid) throw fieldError("script")
    }

    /** Three-tier deletes a preset. Requires write access to its scope. */
    fun delete(orgId: UUID, requestingUserId: UUID, presetId: UUID) {
        transaction {
            // As [update]: one the caller cannot read is not found.
            val row = readable(orgId, requestingUserId, presetId)

            requireScopeWrite(orgId, requestingUserId, row[OrgRulePresets.workspaceId])

            val now = Instant.now()
            OrgRulePresets.update({ OrgRulePresets.id eq presetId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = DeletionRetention.purgeAfter(now)
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

    private fun toSummary(row: ResultRow) = RulePresetSummary(
        id = row[OrgRulePresets.id].toString(),
        name = row[OrgRulePresets.displayName],
        script = row[OrgRulePresets.script],
        scope = if (row[OrgRulePresets.workspaceId] != null) "workspace" else "org",
        workspaceId = row[OrgRulePresets.workspaceId]?.toString(),
    )
}
