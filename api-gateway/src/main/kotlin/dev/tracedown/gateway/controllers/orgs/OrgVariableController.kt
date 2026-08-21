package dev.tracedown.gateway.controllers.orgs

import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.pfs.Page
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.VariableSummary
import dev.tracedown.gateway.data.parseVariableType
import dev.tracedown.gateway.data.variableTypeName
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.variables.VariableLimits
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.VariableCrypto
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Manages org-level variable CRUD.
 * Gated by org settings permission.
 */
object OrgVariableController {

    /** Lists org variables. Encrypted values are masked. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<VariableSummary> {
        return transaction {
            requireOrgRead(orgId, userId) { it.settings }

            val query = OrgVariables.selectAll()
                .where { (OrgVariables.organizationId eq orgId) and (OrgVariables.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row ->
                val masked = if (row[OrgVariables.secret]) "••••••••" else {
                    if (row[OrgVariables.encrypted]) "••••••••" else row[OrgVariables.value]
                }
                VariableSummary(
                    id = row[OrgVariables.id].toString(),
                    key = row[OrgVariables.key],
                    value = masked,
                    type = variableTypeName(row[OrgVariables.secret], row[OrgVariables.encrypted]),
                    createdAt = row[OrgVariables.createdAt].toString(),
                    updatedAt = row[OrgVariables.updatedAt].toString(),
                )
            }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Creates an org variable. */
    fun create(orgId: UUID, request: CreateVariableRequest, userId: UUID): VariableSummary {
        if (request.key.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.key.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        val (secret, encrypted) = parseVariableType(request.type)

        return transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val exists = OrgVariables.selectAll()
                .where {
                    (OrgVariables.organizationId eq orgId) and
                        (OrgVariables.key eq request.key) and
                        (OrgVariables.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            // One resource, one cap. Counted live so deleting a variable frees
            // the slot; system-managed rows are created elsewhere and are not
            // subject to it.
            val held = OrgVariables.selectAll()
                .where { (OrgVariables.organizationId eq orgId) and (OrgVariables.deleted eq false) }
                .count()
            if (VariableLimits.isFull(held)) throw BadRequestException(ErrorCodes.VARIABLE_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()
            val (storedValue, iv) = when {
                secret -> VariableCrypto.encrypt(orgId, request.value, "org", request.key) to null
                encrypted -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            OrgVariables.insert {
                it[OrgVariables.id] = id
                it[organizationId] = orgId
                it[createdBy] = userId
                it[key] = request.key
                it[value] = storedValue
                it[OrgVariables.secret] = secret
                it[OrgVariables.encrypted] = encrypted
                it[valueIv] = iv
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.created", "variable", id,
                buildJsonObject { put("id", id.toString()); put("orgId", orgId.toString()); put("scope", "org"); put("parentId", orgId.toString()) },
            )
            variableSummary(id)
        }
    }

    /** Reveals a variable's decrypted value. Secrets cannot be revealed. */
    fun reveal(orgId: UUID, varId: UUID, userId: UUID): VariableSummary {
        return transaction {
            requireOrgRead(orgId, userId) { it.settings }

            val row = OrgVariables.selectAll()
                .where {
                    (OrgVariables.id eq varId) and
                        (OrgVariables.organizationId eq orgId) and
                        (OrgVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            if (row[OrgVariables.secret]) throw BadRequestException(ErrorCodes.FORBIDDEN)

            val value = if (row[OrgVariables.encrypted]) {
                VariableCrypto.decrypt(orgId, row[OrgVariables.value], row[OrgVariables.valueIv], "org", row[OrgVariables.key])
            } else {
                row[OrgVariables.value]
            }

            VariableSummary(
                id = row[OrgVariables.id].toString(),
                key = row[OrgVariables.key],
                value = value,
                type = variableTypeName(row[OrgVariables.secret], row[OrgVariables.encrypted]),
                createdAt = row[OrgVariables.createdAt].toString(),
                updatedAt = row[OrgVariables.updatedAt].toString(),
            )
        }
    }

    /** Updates a variable's value. */
    fun update(orgId: UUID, varId: UUID, request: UpdateVariableRequest, userId: UUID): VariableSummary {
        return transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val row = OrgVariables.selectAll()
                .where {
                    (OrgVariables.id eq varId) and
                        (OrgVariables.organizationId eq orgId) and
                        (OrgVariables.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            val (storedValue, iv) = when {
                row[OrgVariables.secret] -> VariableCrypto.encrypt(orgId, request.value, "org", row[OrgVariables.key]) to null
                row[OrgVariables.encrypted] -> VariableCrypto.encrypt(request.value)
                else -> request.value to null
            }

            OrgVariables.update({ OrgVariables.id eq varId }) {
                it[value] = storedValue
                it[valueIv] = iv
                it[updatedAt] = Instant.now()
            }

            OutboxEmit.emitResourceEvent(
                "resource.variable.updated", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "org"); put("parentId", orgId.toString()) },
            )
            variableSummary(varId)
        }
    }

    /** Soft-deletes a variable. */
    fun delete(orgId: UUID, varId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val updated = OrgVariables.update({
                (OrgVariables.id eq varId) and
                    (OrgVariables.organizationId eq orgId) and
                    (OrgVariables.deleted eq false)
            }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            if (updated == 0) throw NotFoundException()
            OutboxEmit.emitResourceEvent(
                "resource.variable.deleted", "variable", varId,
                buildJsonObject { put("id", varId.toString()); put("orgId", orgId.toString()); put("scope", "org"); put("parentId", orgId.toString()) },
            )
        }
    }

    private fun variableSummary(id: UUID): VariableSummary {
        val row = OrgVariables.selectAll()
            .where { OrgVariables.id eq id }
            .first()
        val masked = if (row[OrgVariables.secret] || row[OrgVariables.encrypted]) "••••••••" else row[OrgVariables.value]
        return VariableSummary(
            id = row[OrgVariables.id].toString(),
            key = row[OrgVariables.key],
            value = masked,
            type = variableTypeName(row[OrgVariables.secret], row[OrgVariables.encrypted]),
            createdAt = row[OrgVariables.createdAt].toString(),
            updatedAt = row[OrgVariables.updatedAt].toString(),
        )
    }
}
