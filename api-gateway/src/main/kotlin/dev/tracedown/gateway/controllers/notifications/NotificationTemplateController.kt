package dev.tracedown.gateway.controllers.notifications

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.models.NotificationTemplates
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ProjectNotificationTemplates
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.notifications.BindProjectRequest
import dev.tracedown.gateway.data.notifications.CreateNotificationTemplateRequest
import dev.tracedown.gateway.data.notifications.NotificationTemplateSummary
import dev.tracedown.gateway.data.notifications.UpdateNotificationTemplateRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Manages notification template CRUD operations.
 *
 * Templates are org-scoped and can be bound to projects via a junction table.
 * Gated by org settings permission.
 */
object NotificationTemplateController {

    /** Creates a notification template, optionally binding it to projects. */
    fun create(orgId: UUID, request: CreateNotificationTemplateRequest, userId: UUID): NotificationTemplateSummary {
        validateName(request.name)
        validateText(request.text)

        return transaction {
            requireOrgWrite(orgId, userId) { it.notifications }

            // Check name uniqueness within org
            val nameExists = NotificationTemplates.selectAll()
                .where {
                    (NotificationTemplates.organizationId eq orgId) and
                        (NotificationTemplates.name eq request.name) and
                        (NotificationTemplates.deleted eq false)
                }
                .any()
            if (nameExists) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()

            NotificationTemplates.insert {
                it[NotificationTemplates.id] = id
                it[organizationId] = orgId
                it[name] = request.name
                it[text] = request.text
                it[deleted] = false
                it[createdAt] = now
            }

            // Bind to projects if provided
            request.projectIds?.forEach { projectIdStr ->
                val projectId = UUID.fromString(projectIdStr)
                requireProjectInOrg(projectId, orgId)
                ProjectNotificationTemplates.insert {
                    it[ProjectNotificationTemplates.id] = UUID.randomUUID()
                    it[notificationTemplateId] = id
                    it[ProjectNotificationTemplates.projectId] = projectId
                }
            }

            AuditService.log(orgId, userId, "create.notification-template", "notification-template", id.toString(), entityDisplayName = request.name)

            templateSummary(id)
        }
    }

    /** Lists all notification templates in the organization. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<NotificationTemplateSummary> {
        return transaction {
            requireOrgRead(orgId, userId) { it.notifications }

            // Filters on the binding table need the join (left, so an isNull
            // filter can select unbound templates). A template binds a project
            // at most once, so a single project filter can't duplicate rows.
            val filtersBindings = pfs.filters.any { it.table == "project_notification_templates" }
            val source = if (filtersBindings) {
                NotificationTemplates.join(
                    ProjectNotificationTemplates,
                    org.jetbrains.exposed.sql.JoinType.LEFT,
                    onColumn = NotificationTemplates.id,
                    otherColumn = ProjectNotificationTemplates.notificationTemplateId,
                ).select(NotificationTemplates.columns)
            } else {
                NotificationTemplates.selectAll()
            }
            val query = source.where {
                (NotificationTemplates.organizationId eq orgId) and
                    (NotificationTemplates.deleted eq false)
            }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row ->
                val templateId = row[NotificationTemplates.id]
                val projectIds = loadProjectIds(templateId)
                summaryFromRow(row, projectIds)
            }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns a single notification template. */
    fun get(orgId: UUID, templateId: UUID, userId: UUID): NotificationTemplateSummary {
        return transaction {
            requireOrgRead(orgId, userId) { it.notifications }
            templateSummary(templateId, orgId)
        }
    }

    /** Updates a notification template. */
    fun update(orgId: UUID, templateId: UUID, request: UpdateNotificationTemplateRequest, userId: UUID): NotificationTemplateSummary {
        request.name?.let { validateName(it) }
        request.text?.let { validateText(it) }

        return transaction {
            requireOrgWrite(orgId, userId) { it.notifications }
            requireExists(templateId, orgId)

            // Check name uniqueness if changing name
            if (request.name != null) {
                val nameExists = NotificationTemplates.selectAll()
                    .where {
                        (NotificationTemplates.organizationId eq orgId) and
                            (NotificationTemplates.name eq request.name) and
                            (NotificationTemplates.deleted eq false) and
                            (NotificationTemplates.id neq templateId)
                    }
                    .any()
                if (nameExists) throw ConflictException()
            }

            val old = NotificationTemplates.selectAll()
                .where { NotificationTemplates.id eq templateId }
                .first()

            NotificationTemplates.update({
                (NotificationTemplates.id eq templateId) and (NotificationTemplates.organizationId eq orgId)
            }) {
                request.name?.let { v -> it[name] = v }
                request.text?.let { v -> it[text] = v }
            }

            AuditService.log(
                orgId, userId, "update.notification-template", "notification-template", templateId.toString(),
                entityDisplayName = old[NotificationTemplates.name],
                diff = auditDiff(
                    Triple("name", old[NotificationTemplates.name], request.name ?: old[NotificationTemplates.name]),
                    Triple("text", old[NotificationTemplates.text], request.text ?: old[NotificationTemplates.text]),
                ),
            )

            templateSummary(templateId)
        }
    }

    /** Soft-deletes a notification template. */
    fun delete(orgId: UUID, templateId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.notifications }
            requireExists(templateId, orgId)

            val templateName = NotificationTemplates.selectAll()
                .where { NotificationTemplates.id eq templateId }
                .firstOrNull()?.get(NotificationTemplates.name)

            NotificationTemplates.update({ NotificationTemplates.id eq templateId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.notification-template", "notification-template", templateId.toString(), entityDisplayName = templateName)
        }
    }

    // ── Project Bindings ──

    /** Binds a template to a project. */
    fun bindProject(orgId: UUID, templateId: UUID, request: BindProjectRequest, userId: UUID): NotificationTemplateSummary {
        val projectId = UUID.fromString(request.projectId)

        return transaction {
            requireOrgWrite(orgId, userId) { it.notifications }
            requireExists(templateId, orgId)
            requireProjectInOrg(projectId, orgId)

            val exists = ProjectNotificationTemplates.selectAll()
                .where {
                    (ProjectNotificationTemplates.notificationTemplateId eq templateId) and
                        (ProjectNotificationTemplates.projectId eq projectId)
                }
                .any()
            if (exists) throw ConflictException()

            ProjectNotificationTemplates.insert {
                it[id] = UUID.randomUUID()
                it[notificationTemplateId] = templateId
                it[ProjectNotificationTemplates.projectId] = projectId
            }

            val templateName = NotificationTemplates.selectAll()
                .where { NotificationTemplates.id eq templateId }
                .firstOrNull()?.get(NotificationTemplates.name)

            AuditService.log(orgId, userId, "bind-project.notification-template", "notification-template", templateId.toString(),
                entityDisplayName = templateName, comment = "Bound to project $projectId")

            templateSummary(templateId)
        }
    }

    /** Unbinds a template from a project. */
    fun unbindProject(orgId: UUID, templateId: UUID, projectId: UUID, userId: UUID): NotificationTemplateSummary {
        return transaction {
            requireOrgWrite(orgId, userId) { it.notifications }
            requireExists(templateId, orgId)
            // `projectId` is a bare route parameter and the binding table has no
            // path to an org of its own — same guard the two binding writers use.
            requireProjectInOrg(projectId, orgId)

            val deleted = ProjectNotificationTemplates.deleteWhere {
                (ProjectNotificationTemplates.notificationTemplateId eq templateId) and
                    (ProjectNotificationTemplates.projectId eq projectId)
            }
            if (deleted == 0) throw NotFoundException()

            val templateName = NotificationTemplates.selectAll()
                .where { NotificationTemplates.id eq templateId }
                .firstOrNull()?.get(NotificationTemplates.name)

            AuditService.log(orgId, userId, "unbind-project.notification-template", "notification-template", templateId.toString(),
                entityDisplayName = templateName, comment = "Unbound from project $projectId")

            templateSummary(templateId)
        }
    }

    // ── Internals ──

    private fun validateName(name: String) {
        if (name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
    }

    private fun validateText(text: String) {
        if (text.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
    }

    private fun requireExists(templateId: UUID, orgId: UUID) {
        val exists = NotificationTemplates.selectAll()
            .where {
                (NotificationTemplates.id eq templateId) and
                    (NotificationTemplates.organizationId eq orgId) and
                    (NotificationTemplates.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun requireProjectInOrg(projectId: UUID, orgId: UUID) {
        val exists = (Projects innerJoin Workspaces)
            .selectAll()
            .where {
                (Projects.id eq projectId) and
                    (Workspaces.organizationId eq orgId) and
                    (Projects.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun loadProjectIds(templateId: UUID): List<String> {
        return ProjectNotificationTemplates.selectAll()
            .where { ProjectNotificationTemplates.notificationTemplateId eq templateId }
            .map { it[ProjectNotificationTemplates.projectId].toString() }
    }

    private fun templateSummary(id: UUID, orgId: UUID? = null): NotificationTemplateSummary {
        val query = NotificationTemplates.selectAll().where {
            if (orgId != null) {
                (NotificationTemplates.id eq id) and (NotificationTemplates.organizationId eq orgId) and (NotificationTemplates.deleted eq false)
            } else {
                (NotificationTemplates.id eq id) and (NotificationTemplates.deleted eq false)
            }
        }
        val row = query.firstOrNull()
            ?: throw NotFoundException()
        val projectIds = loadProjectIds(row[NotificationTemplates.id])
        return summaryFromRow(row, projectIds)
    }

    private fun summaryFromRow(row: org.jetbrains.exposed.sql.ResultRow, projectIds: List<String>) = NotificationTemplateSummary(
        id = row[NotificationTemplates.id].toString(),
        name = row[NotificationTemplates.name],
        text = row[NotificationTemplates.text],
        projectIds = projectIds,
        createdAt = row[NotificationTemplates.createdAt].toString(),
    )
}
