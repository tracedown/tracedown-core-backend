package dev.tracedown.gateway.controllers.silences

import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.silences.SilenceSummary
import dev.tracedown.gateway.data.silences.UpdateSilenceRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.requireCachedPermissions
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.util.UUID

object SilenceController {


    /** Creates a notification silence for the current user. */
    fun create(orgId: UUID, userId: UUID, request: CreateSilenceRequest): SilenceSummary {
        validateRequest(request)

        val wsId = request.workspaceId?.let { parseUuid(it, "workspace ID") }
        val projId = request.projectId?.let { parseUuid(it, "project ID") }
        val svcId = request.serviceId?.let { parseUuid(it, "service ID") }

        validateQuietHours(request.quietHours)

        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)

            // The target is a caller-supplied id. Resolving it by id alone
            // confirmed only that the row existed anywhere in the installation,
            // and the summary then echoed its name back — so any member could
            // enumerate another org's projects and services by silencing them.
            // Resolve inside the org, and require the same read access the
            // resource itself takes: silencing something you cannot see has no
            // meaning, since notification eligibility keys off the same grants.
            requireVisibleTarget(orgId, userId, wsId, projId, svcId)

            val id = UUID.randomUUID()

            NotificationSilences.insert {
                it[NotificationSilences.id] = id
                it[NotificationSilences.orgUserId] = orgUserId
                it[workspaceId] = wsId
                it[projectId] = projId
                it[serviceId] = svcId
                it[channel] = request.channel
                it[config] = request.config?.let(::parseJsonField)
                it[quietHours] = request.quietHours
            }

            silenceSummary(orgId, id)
        }
    }

    /** Lists silences for the current user in this org. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<SilenceSummary> {
        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)

            val query = NotificationSilences.selectAll()
                .where { NotificationSilences.orgUserId eq orgUserId }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { silenceSummaryFromRow(orgId, it) }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns a single silence. Must belong to the current user. */
    fun get(orgId: UUID, userId: UUID, silenceId: UUID): SilenceSummary {
        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            silenceSummary(orgId, silenceId, orgUserId)
        }
    }

    /** Updates a silence's channel, config, or quiet hours. */
    fun update(orgId: UUID, userId: UUID, silenceId: UUID, request: UpdateSilenceRequest): SilenceSummary {
        if (request.channel != null && !SilenceChannels.isAccepted(request.channel)) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        validateQuietHours(request.quietHours)

        return transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            requireSilenceOwnership(silenceId, orgUserId)

            NotificationSilences.update({ NotificationSilences.id eq silenceId }) {
                request.channel?.let { v -> it[channel] = v }
                request.config?.let { v -> it[config] = parseJsonField(v) }
                request.quietHours?.let { v -> it[quietHours] = v }
            }

            silenceSummary(orgId, silenceId)
        }
    }

    /** Deletes a silence. Must belong to the current user. */
    fun delete(orgId: UUID, userId: UUID, silenceId: UUID) {
        transaction {
            val orgUserId = resolveOrgUserId(orgId, userId)
            requireSilenceOwnership(silenceId, orgUserId)

            NotificationSilences.deleteWhere { NotificationSilences.id eq silenceId }
        }
    }

    // ── Validation ──

    private fun validateRequest(request: CreateSilenceRequest) {
        // A channel nothing enforces is a control that silently does nothing —
        // "webhook" in particular is refused here rather than stored and
        // ignored. See SilenceChannels for the reasoning.
        if (!SilenceChannels.isAccepted(request.channel)) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        val scopeCount = listOfNotNull(request.workspaceId, request.projectId, request.serviceId).size
        if (scopeCount > 1) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    /**
     * Quiet hours are a recurrence spec `RRULE[/durationMinutes[/timezone]]`,
     * the same format as the service maintenance window. Reject malformed specs
     * up front — the dispatcher silently ignores them, which would read as
     * "quiet hours set" while never actually suppressing. Timezone names contain
     * slashes, so split as rrule / duration / rest.
     */
    private fun validateQuietHours(quietHours: String?) {
        if (quietHours.isNullOrBlank()) return
        val parts = quietHours.trim().split('/')
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

    private fun parseUuid(value: String, label: String): UUID {
        return try { UUID.fromString(value) } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.INVALID_UUID)
        }
    }

    // ── Internals ──

    private fun resolveOrgUserId(orgId: UUID, userId: UUID): UUID {
        val row = OrgUsers.selectAll()
            .where {
                (OrgUsers.organizationId eq orgId) and
                (OrgUsers.userId eq userId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull() ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
        return row[OrgUsers.id]
    }

    private fun requireSilenceOwnership(silenceId: UUID, orgUserId: UUID) {
        val exists = NotificationSilences.selectAll()
            .where {
                (NotificationSilences.id eq silenceId) and
                (NotificationSilences.orgUserId eq orgUserId)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    /**
     * Resolves the silenced scope inside [orgId] and requires read access to it.
     * At most one of the three is set (see [validateRequest]). Anything the
     * caller may not see is indistinguishable from a nonexistent id.
     */
    private fun requireVisibleTarget(orgId: UUID, userId: UUID, wsId: UUID?, projId: UUID?, svcId: UUID?) {
        if (wsId == null && projId == null && svcId == null) return
        val cached = requireCachedPermissions(orgId, userId)
        when {
            svcId != null -> {
                val ctx = ResourceResolver.resolveService(svcId, orgId)
                val chain = listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
                if (!canAccessResource(cached, "service", svcId, chain)) throw NotFoundException()
            }
            projId != null -> {
                val ctx = ResourceResolver.resolveProject(projId, orgId)
                val chain = listOf("workspace::${ctx.workspaceId}")
                if (!canAccessResource(cached, "project", projId, chain)) throw NotFoundException()
            }
            else -> {
                ResourceResolver.resolveWorkspace(wsId!!, orgId)
                if (!canAccessResource(cached, "workspace", wsId)) throw NotFoundException()
            }
        }
    }

    private fun silenceSummary(orgId: UUID, id: UUID, orgUserId: UUID? = null): SilenceSummary {
        val query = if (orgUserId != null) {
            NotificationSilences.selectAll().where {
                (NotificationSilences.id eq id) and
                (NotificationSilences.orgUserId eq orgUserId)
            }
        } else {
            NotificationSilences.selectAll().where {
                NotificationSilences.id eq id
            }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return silenceSummaryFromRow(orgId, row)
    }

    private fun silenceSummaryFromRow(orgId: UUID, row: org.jetbrains.exposed.sql.ResultRow) = SilenceSummary(
        id = row[NotificationSilences.id].toString(),
        orgUserId = row[NotificationSilences.orgUserId].toString(),
        workspaceId = row[NotificationSilences.workspaceId]?.toString(),
        projectId = row[NotificationSilences.projectId]?.toString(),
        serviceId = row[NotificationSilences.serviceId]?.toString(),
        channel = row[NotificationSilences.channel],
        config = row[NotificationSilences.config]?.toString(),
        quietHours = row[NotificationSilences.quietHours],
        resourceName = resolveResourceName(orgId, row),
    )

    /**
     * Display name of the most specific silenced scope, for list UIs.
     *
     * Only `workspaces` carries organization_id; a project reaches the org
     * through its workspace and a service through its project, so each name
     * lookup joins up to the workspace and filters there. Rows written before
     * [requireVisibleTarget] existed may still point outside the org — those
     * resolve to no name rather than echoing a foreign one.
     */
    private fun resolveResourceName(orgId: UUID, row: org.jetbrains.exposed.sql.ResultRow): String? {
        row[NotificationSilences.serviceId]?.let { id ->
            return Services
                .join(Projects, JoinType.INNER, Services.projectId, Projects.id)
                .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
                .select(Services.name)
                .where { (Services.id eq id) and (Workspaces.organizationId eq orgId) }
                .firstOrNull()?.get(Services.name)
        }
        row[NotificationSilences.projectId]?.let { id ->
            return Projects
                .join(Workspaces, JoinType.INNER, Projects.workspaceId, Workspaces.id)
                .select(Projects.name)
                .where { (Projects.id eq id) and (Workspaces.organizationId eq orgId) }
                .firstOrNull()?.get(Projects.name)
        }
        row[NotificationSilences.workspaceId]?.let { id ->
            return Workspaces.selectAll()
                .where { (Workspaces.id eq id) and (Workspaces.organizationId eq orgId) }
                .firstOrNull()?.get(Workspaces.name)
        }
        return null
    }
    /** JSONB columns only accept valid JSON — reject anything else up front. */
    private fun parseJsonField(value: String): kotlinx.serialization.json.JsonElement {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(value)
        } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

}
