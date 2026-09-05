package dev.tracedown.gateway.controllers.webhooks

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ResourceWebhookAccess
import dev.tracedown.common.models.Services
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.data.webhooks.CreateWebhookRequest
import dev.tracedown.gateway.data.webhooks.UpdateWebhookRequest
import dev.tracedown.gateway.data.webhooks.WebhookBindingRequest
import dev.tracedown.gateway.data.webhooks.WebhookBindingSummary
import dev.tracedown.gateway.data.webhooks.WebhookSummary
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

object WebhookController {

    private val validMethods = setOf("GET", "POST", "PUT", "PATCH")
    private val validResourceTypes = setOf("workspace", "project", "service")

    // ── Webhook CRUD ──

    /** Creates a webhook delivery channel. Requires org-level webhooks.write. */
    @Injectable("webhook.create")
    fun create(orgId: UUID, request: CreateWebhookRequest, userId: UUID): WebhookSummary {
        validateCreateRequest(request)

        return Interceptors.injectable("webhook.create", InterceptorContext(orgId = orgId, userId = userId)) {
            transaction {
                requireOrgWrite(orgId, userId) { it.webhooks }

                val id = UUID.randomUUID()
                val now = Instant.now()

                WebhookDeliveries.insert {
                    it[WebhookDeliveries.id] = id
                    it[organizationId] = orgId
                    it[name] = request.name
                    it[label] = request.label
                    it[url] = request.url
                    it[method] = request.method
                    it[body] = request.body?.let(::parseJsonField)
                    it[config] = request.config?.let(::parseJsonField)
                    it[attemptCount] = request.attemptCount ?: 1
                    it[deleted] = false
                    it[createdAt] = now
                }

                AuditService.log(orgId, userId, "create.webhook", "webhook", id.toString(), entityDisplayName = request.name)

                webhookSummary(id)
            }
        }
    }

    /** Lists all webhooks in the organization. Requires webhooks.read. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<WebhookSummary> {
        return transaction {
            val perms = requireOrgRead(orgId, userId) { it.webhooks }
            // URLs, body templates and delivery config may embed tokens —
            // read-only users get them redacted.
            val redact = !perms.webhooks.canWrite()

            val query = WebhookDeliveries.selectAll()
                .where { (WebhookDeliveries.organizationId eq orgId) and (WebhookDeliveries.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { webhookSummaryFromRow(it, redact) }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns a single webhook. Requires webhooks.read. */
    fun get(orgId: UUID, webhookId: UUID, userId: UUID): WebhookSummary {
        return transaction {
            val perms = requireOrgRead(orgId, userId) { it.webhooks }
            webhookSummary(webhookId, orgId, redact = !perms.webhooks.canWrite())
        }
    }

    /** Updates a webhook's configuration. Requires webhooks.write. */
    fun update(orgId: UUID, webhookId: UUID, request: UpdateWebhookRequest, userId: UUID): WebhookSummary {
        validateUpdateRequest(request)

        return transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }
            requireWebhookExists(webhookId, orgId)

            val old = WebhookDeliveries.selectAll()
                .where { WebhookDeliveries.id eq webhookId }
                .first()

            WebhookDeliveries.update({ (WebhookDeliveries.id eq webhookId) and (WebhookDeliveries.organizationId eq orgId) }) {
                request.name?.let { v -> it[name] = v }
                request.url?.let { v -> it[url] = v }
                request.method?.let { v -> it[method] = v }
                request.label?.let { v -> it[label] = v }
                request.body?.let { v -> it[body] = parseJsonField(v) }
                request.config?.let { v -> it[config] = parseJsonField(v) }
                request.attemptCount?.let { v -> it[attemptCount] = v }
            }

            // URL/config may embed tokens — record THAT they changed, not values.
            AuditService.log(
                orgId, userId, "update.webhook", "webhook", webhookId.toString(),
                entityDisplayName = old[WebhookDeliveries.name],
                diff = auditDiff(
                    Triple("name", old[WebhookDeliveries.name], request.name ?: old[WebhookDeliveries.name]),
                    Triple("label", old[WebhookDeliveries.label], request.label ?: old[WebhookDeliveries.label]),
                    Triple("method", old[WebhookDeliveries.method], request.method ?: old[WebhookDeliveries.method]),
                    Triple("attemptCount", old[WebhookDeliveries.attemptCount], request.attemptCount ?: old[WebhookDeliveries.attemptCount]),
                    Triple("urlChanged", false, request.url != null && request.url != old[WebhookDeliveries.url]),
                    Triple("configChanged", false, request.config != null && request.config != old[WebhookDeliveries.config]?.toString()),
                    Triple("bodyChanged", false, request.body != null && request.body != old[WebhookDeliveries.body]?.toString()),
                ),
            )

            webhookSummary(webhookId)
        }
    }

    /** Soft-deletes a webhook. Requires webhooks.write. */
    fun delete(orgId: UUID, webhookId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }
            requireWebhookExists(webhookId, orgId)

            val webhookName = WebhookDeliveries.selectAll()
                .where { WebhookDeliveries.id eq webhookId }
                .firstOrNull()?.get(WebhookDeliveries.name)

            WebhookDeliveries.update({ WebhookDeliveries.id eq webhookId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.webhook", "webhook", webhookId.toString(), entityDisplayName = webhookName)
        }
    }

    // ── Resource Bindings ──

    /** Binds a webhook to a resource (workspace, project, or service). Requires webhooks.write. */
    fun createBinding(orgId: UUID, resourceType: String, resourceId: UUID, request: WebhookBindingRequest, userId: UUID): WebhookBindingSummary {
        if (resourceType !in validResourceTypes) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        val webhookId = try { UUID.fromString(request.webhookId) } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.INVALID_UUID)
        }

        return transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }
            requireWebhookExists(webhookId, orgId)
            requireResourceExists(resourceType, resourceId, orgId)

            val exists = ResourceWebhookAccess.selectAll()
                .where {
                    (ResourceWebhookAccess.webhookDeliveryId eq webhookId) and
                    (ResourceWebhookAccess.resourceType eq resourceType) and
                    (ResourceWebhookAccess.resourceId eq resourceId)
                }
                .any()
            if (exists) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()

            ResourceWebhookAccess.insert {
                it[ResourceWebhookAccess.id] = id
                it[ResourceWebhookAccess.orgId] = orgId
                it[ResourceWebhookAccess.resourceType] = resourceType
                it[ResourceWebhookAccess.resourceId] = resourceId
                it[webhookDeliveryId] = webhookId
                it[enabled] = request.enabled
                it[createdAt] = now
            }

            val webhookName = WebhookDeliveries.selectAll()
                .where { WebhookDeliveries.id eq webhookId }
                .firstOrNull()?.get(WebhookDeliveries.name)

            AuditService.log(orgId, userId, "create.webhook-binding", "webhook", webhookId.toString(), entityDisplayName = webhookName, comment = "Bound to $resourceType $resourceId")

            bindingSummary(id)
        }
    }

    /** Lists webhook bindings for a resource. Requires webhooks.read. */
    fun listBindings(orgId: UUID, resourceType: String, resourceId: UUID, userId: UUID, pfs: PfsParams): Page<WebhookBindingSummary> {
        if (resourceType !in validResourceTypes) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        return transaction {
            requireOrgRead(orgId, userId) { it.webhooks }

            val query = (ResourceWebhookAccess innerJoin WebhookDeliveries)
                .selectAll()
                .where {
                    (ResourceWebhookAccess.resourceType eq resourceType) and
                    (ResourceWebhookAccess.resourceId eq resourceId) and
                    (ResourceWebhookAccess.orgId eq orgId) and
                    (WebhookDeliveries.deleted eq false)
                }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { bindingSummaryFromRow(it) }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Updates a binding's enabled state. Requires webhooks.write. */
    fun updateBinding(orgId: UUID, bindingId: UUID, enabled: Boolean, userId: UUID): WebhookBindingSummary {
        return transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }

            val updated = ResourceWebhookAccess.update({
                (ResourceWebhookAccess.id eq bindingId) and (ResourceWebhookAccess.orgId eq orgId)
            }) {
                it[ResourceWebhookAccess.enabled] = enabled
            }
            if (updated == 0) throw NotFoundException()

            bindingSummary(bindingId)
        }
    }

    /** Removes a webhook binding from a resource. Requires webhooks.write. */
    fun deleteBinding(orgId: UUID, bindingId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }

            val deleted = ResourceWebhookAccess.deleteWhere {
                (ResourceWebhookAccess.id eq bindingId) and (ResourceWebhookAccess.orgId eq orgId)
            }
            if (deleted == 0) throw NotFoundException()

            AuditService.log(orgId, userId, "delete.webhook-binding", "webhook-binding", bindingId.toString(), entityDisplayName = null)
        }
    }

    // ── Validation ──

    private fun validateCreateRequest(request: CreateWebhookRequest) {
        if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (request.url.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.url.length > 512) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (request.method !in validMethods) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        if (request.attemptCount != null && (request.attemptCount < 1 || request.attemptCount > 10)) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    private fun validateUpdateRequest(request: UpdateWebhookRequest) {
        if (request.name != null) {
            if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
            if (request.name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        }
        if (request.url != null) {
            if (request.url.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
            if (request.url.length > 512) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        }
        if (request.method != null && request.method !in validMethods) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        if (request.attemptCount != null && (request.attemptCount < 1 || request.attemptCount > 10)) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    // ── Internals ──

    private fun requireWebhookExists(webhookId: UUID, orgId: UUID) {
        val exists = WebhookDeliveries.selectAll()
            .where {
                (WebhookDeliveries.id eq webhookId) and
                (WebhookDeliveries.organizationId eq orgId) and
                (WebhookDeliveries.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    /** Verifies that a resource exists in the org. Polymorphic — no FK, validated at application layer. */
    private fun requireResourceExists(resourceType: String, resourceId: UUID, orgId: UUID) {
        val exists = when (resourceType) {
            "workspace" -> Workspaces.selectAll()
                .where { (Workspaces.id eq resourceId) and (Workspaces.organizationId eq orgId) and (Workspaces.deleted eq false) }
                .any()
            "project" -> {
                // Project belongs to a workspace in the org
                (Projects innerJoin Workspaces)
                    .selectAll()
                    .where {
                        (Projects.id eq resourceId) and
                        (Workspaces.organizationId eq orgId) and
                        (Projects.deleted eq false)
                    }
                    .any()
            }
            "service" -> {
                // Service belongs to a project in a workspace in the org
                (Services innerJoin Projects innerJoin Workspaces)
                    .selectAll()
                    .where {
                        (Services.id eq resourceId) and
                        (Workspaces.organizationId eq orgId) and
                        (Services.deleted eq false)
                    }
                    .any()
            }
            else -> false
        }
        if (!exists) throw NotFoundException()
    }

    private fun webhookSummary(id: UUID, orgId: UUID? = null, redact: Boolean = false): WebhookSummary {
        val query = if (orgId != null) {
            WebhookDeliveries.selectAll().where {
                (WebhookDeliveries.id eq id) and (WebhookDeliveries.organizationId eq orgId) and (WebhookDeliveries.deleted eq false)
            }
        } else {
            WebhookDeliveries.selectAll().where { (WebhookDeliveries.id eq id) and (WebhookDeliveries.deleted eq false) }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return webhookSummaryFromRow(row, redact)
    }

    private fun webhookSummaryFromRow(
        row: org.jetbrains.exposed.v1.core.ResultRow,
        redact: Boolean = false,
    ) = WebhookSummary(
        id = row[WebhookDeliveries.id].toString(),
        name = row[WebhookDeliveries.name],
        label = row[WebhookDeliveries.label],
        url = if (redact) redactUrl(row[WebhookDeliveries.url]) else row[WebhookDeliveries.url],
        method = row[WebhookDeliveries.method],
        body = if (redact) null else row[WebhookDeliveries.body]?.toString(),
        config = if (redact) null else row[WebhookDeliveries.config]?.toString(),
        attemptCount = row[WebhookDeliveries.attemptCount],
        createdAt = row[WebhookDeliveries.createdAt].toString(),
    )

    /** Keeps scheme+host for recognizability; the path/query may embed tokens. */
    private fun redactUrl(url: String): String {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd == -1) return "•••"
        val hostEnd = url.indexOf('/', schemeEnd + 3)
        return if (hostEnd == -1) url else url.substring(0, hostEnd) + "/•••"
    }

    private fun bindingSummary(id: UUID): WebhookBindingSummary {
        val row = (ResourceWebhookAccess innerJoin WebhookDeliveries)
            .selectAll()
            .where { ResourceWebhookAccess.id eq id }
            .firstOrNull() ?: throw NotFoundException()
        return bindingSummaryFromRow(row)
    }

    private fun bindingSummaryFromRow(row: org.jetbrains.exposed.v1.core.ResultRow) = WebhookBindingSummary(
        id = row[ResourceWebhookAccess.id].toString(),
        webhookId = row[ResourceWebhookAccess.webhookDeliveryId].toString(),
        webhookName = row[WebhookDeliveries.name],
        enabled = row[ResourceWebhookAccess.enabled],
        createdAt = row[ResourceWebhookAccess.createdAt].toString(),
    )
    /** JSONB columns only accept valid JSON — reject anything else up front. */
    private fun parseJsonField(value: String): kotlinx.serialization.json.JsonElement {
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(value)
        } catch (_: Exception) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

}
