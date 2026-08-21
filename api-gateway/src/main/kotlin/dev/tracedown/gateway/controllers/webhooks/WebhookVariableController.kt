package dev.tracedown.gateway.controllers.webhooks

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.variables.VariableLimits
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.WebhookVariables
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.parseVariableType
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Manages per-webhook variable CRUD (`$h.<key>` references in a webhook's URL
 * and config header/query values). Gated by the webhooks permission, like the
 * webhook itself. Unlike org variables these are invisible to probe scripts —
 * only the notification dispatcher resolves them, so a delivery credential
 * doesn't have to be an org-wide variable every script author can use.
 *
 * Secrets are envelope-encrypted with AAD scope `webhook:<webhookId>`, so a
 * ciphertext moved to another webhook's row will not decrypt.
 */
object WebhookVariableController {

    /** Lists a webhook's variables. Encrypted values are masked. */
    fun list(orgId: UUID, webhookId: UUID, userId: UUID): List<VariableSummary> {
        return transaction {
            requireOrgRead(orgId, userId) { it.webhooks }
            requireWebhook(webhookId, orgId)

            WebhookVariables.selectAll()
                .where { (WebhookVariables.webhookId eq webhookId) and (WebhookVariables.deleted eq false) }
                .orderBy(WebhookVariables.key)
                .map { row ->
                    val masked = if (row[WebhookVariables.secret] || row[WebhookVariables.encrypted]) {
                        "••••••••"
                    } else {
                        row[WebhookVariables.value]
                    }
                    VariableSummary(
                        id = row[WebhookVariables.id].toString(),
                        key = row[WebhookVariables.key],
                        value = masked,
                        type = variableTypeName(row[WebhookVariables.secret], row[WebhookVariables.encrypted]),
                        createdAt = row[WebhookVariables.createdAt].toString(),
                        updatedAt = row[WebhookVariables.updatedAt].toString(),
                    )
                }
        }
    }

    /** Creates a webhook variable. The plaintext `metric` type has no meaning here. */
    fun create(orgId: UUID, webhookId: UUID, request: CreateVariableRequest, userId: UUID): VariableSummary {
        if (request.key.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.key.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (request.type == "metric") throw BadRequestException(ErrorCodes.FIELD_INVALID)
        val (secret, encrypted) = parseVariableType(request.type)

        return transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }
            requireWebhook(webhookId, orgId)

            val exists = WebhookVariables.selectAll()
                .where {
                    (WebhookVariables.webhookId eq webhookId) and
                        (WebhookVariables.key eq request.key) and
                        (WebhookVariables.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            // One resource, one cap. Counted live so deleting a variable frees
            // the slot; system-managed rows are created elsewhere and are not
            // subject to it.
            val held = WebhookVariables.selectAll()
                .where { (WebhookVariables.webhookId eq webhookId) and (WebhookVariables.deleted eq false) }
                .count()
            if (VariableLimits.isFull(held)) throw BadRequestException(ErrorCodes.VARIABLE_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()
            val (storedValue, iv) = when {
                secret -> VariableCrypto.encrypt(orgId, request.value, cryptoScope(webhookId), request.key) to null
                else -> VariableCrypto.encrypt(request.value)
            }

            WebhookVariables.insert {
                it[WebhookVariables.id] = id
                it[organizationId] = orgId
                it[WebhookVariables.webhookId] = webhookId
                it[createdBy] = userId
                it[key] = request.key
                it[value] = storedValue
                it[WebhookVariables.secret] = secret
                it[WebhookVariables.encrypted] = encrypted
                it[valueIv] = iv
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.created", "variable", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("scope", "webhook"); put("parentId", webhookId.toString()) },
            )
            variableSummary(id)
        }
    }

    /** Reveals a variable's decrypted value. Secrets cannot be revealed. */
    fun reveal(orgId: UUID, webhookId: UUID, varId: UUID, userId: UUID): VariableSummary {
        return transaction {
            requireOrgRead(orgId, userId) { it.webhooks }

            val row = variableRow(orgId, webhookId, varId)
            if (row[WebhookVariables.secret]) throw BadRequestException(ErrorCodes.FORBIDDEN)

            val value = if (row[WebhookVariables.encrypted]) {
                VariableCrypto.decrypt(
                    orgId, row[WebhookVariables.value], row[WebhookVariables.valueIv],
                    cryptoScope(webhookId), row[WebhookVariables.key],
                )
            } else {
                row[WebhookVariables.value]
            }

            VariableSummary(
                id = row[WebhookVariables.id].toString(),
                key = row[WebhookVariables.key],
                value = value,
                type = variableTypeName(row[WebhookVariables.secret], row[WebhookVariables.encrypted]),
                createdAt = row[WebhookVariables.createdAt].toString(),
                updatedAt = row[WebhookVariables.updatedAt].toString(),
            )
        }
    }

    /** Updates a variable's value. */
    fun update(orgId: UUID, webhookId: UUID, varId: UUID, request: UpdateVariableRequest, userId: UUID): VariableSummary {
        return transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }

            val row = variableRow(orgId, webhookId, varId)
            val (storedValue, iv) = when {
                row[WebhookVariables.secret] ->
                    VariableCrypto.encrypt(orgId, request.value, cryptoScope(webhookId), row[WebhookVariables.key]) to null
                else -> VariableCrypto.encrypt(request.value)
            }

            WebhookVariables.update({ WebhookVariables.id eq varId }) {
                it[value] = storedValue
                it[valueIv] = iv
                it[updatedAt] = Instant.now()
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.updated", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "webhook"); put("parentId", webhookId.toString()) },
            )
            variableSummary(varId)
        }
    }

    /** Soft-deletes a variable. */
    fun delete(orgId: UUID, webhookId: UUID, varId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.webhooks }

            val updated = WebhookVariables.update({
                (WebhookVariables.id eq varId) and
                    (WebhookVariables.webhookId eq webhookId) and
                    (WebhookVariables.organizationId eq orgId) and
                    (WebhookVariables.deleted eq false)
            }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            if (updated == 0) throw NotFoundException()
            OutboxEmit.emitResourceEvent(
                "resource.variable.deleted", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "webhook"); put("parentId", webhookId.toString()) },
            )
        }
    }

    /** AAD scope binding a ciphertext to its webhook. Must match the dispatcher's decrypt. */
    private fun cryptoScope(webhookId: UUID) = "webhook:$webhookId"

    private fun requireWebhook(webhookId: UUID, orgId: UUID) {
        val exists = WebhookDeliveries.selectAll()
            .where {
                (WebhookDeliveries.id eq webhookId) and
                    (WebhookDeliveries.organizationId eq orgId) and
                    (WebhookDeliveries.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun variableRow(orgId: UUID, webhookId: UUID, varId: UUID) =
        WebhookVariables.selectAll()
            .where {
                (WebhookVariables.id eq varId) and
                    (WebhookVariables.webhookId eq webhookId) and
                    (WebhookVariables.organizationId eq orgId) and
                    (WebhookVariables.deleted eq false)
            }
            .firstOrNull() ?: throw NotFoundException()

    private fun variableSummary(id: UUID): VariableSummary {
        val row = WebhookVariables.selectAll()
            .where { WebhookVariables.id eq id }
            .first()
        val masked = if (row[WebhookVariables.secret] || row[WebhookVariables.encrypted]) "••••••••" else row[WebhookVariables.value]
        return VariableSummary(
            id = row[WebhookVariables.id].toString(),
            key = row[WebhookVariables.key],
            value = masked,
            type = variableTypeName(row[WebhookVariables.secret], row[WebhookVariables.encrypted]),
            createdAt = row[WebhookVariables.createdAt].toString(),
            updatedAt = row[WebhookVariables.updatedAt].toString(),
        )
    }
}
