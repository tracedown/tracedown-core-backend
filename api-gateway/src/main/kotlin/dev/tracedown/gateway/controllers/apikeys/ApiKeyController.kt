package dev.tracedown.gateway.controllers.apikeys

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.apikeys.ApiKeySummary
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgWrite
import dev.tracedown.gateway.util.requireOrgRead
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Manages API key CRUD operations.
 *
 * API keys are org-scoped, created with a plain key shown once on creation,
 * then only the hash is stored. Gated by org settings permission.
 *
 * **No verifier exists yet, so a key authenticates nothing.** Nothing in the
 * request path ever reads [ApiKeys.keyHash]: `SessionAuthenticator` matches the
 * session-token hash and only that, so a `td_…` key presented on any endpoint
 * is rejected exactly like an unknown token. [ApiKeys.lastUsedAt] is written by
 * nobody for the same reason and stays null for the life of every key.
 *
 * That is deliberate, not an oversight: this CRUD surface is deliberately ahead
 * of the verification path, and the verifier is deferred. Nothing mints these
 * keys today either — no UI reaches these routes — so no key is in circulation
 * expecting to work. Do not read the presence of full CRUD, a bcrypt hash and a
 * `last_used_at` column as evidence that key auth is wired up; it is not, and
 * building on it will silently do nothing.
 */
object ApiKeyController {

    private val secureRandom = SecureRandom()

    /** Creates a new API key. Returns the plaintext key only once. */
    fun create(orgId: UUID, request: CreateApiKeyRequest, userId: UUID): ApiKeySummary {
        if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (request.expiresInDays != null && request.expiresInDays < 1) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        return transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            val id = UUID.randomUUID()
            val rawKey = generateKey()
            val keyHash = BCrypt.withDefaults().hashToString(10, rawKey.toCharArray())
            val now = Instant.now()
            val expiresAt = request.expiresInDays?.let { now.plusSeconds(it.toLong() * 24 * 3600) }

            ApiKeys.insert {
                it[ApiKeys.id] = id
                it[organizationId] = orgId
                it[createdBy] = userId
                it[name] = request.name
                it[ApiKeys.keyHash] = keyHash
                it[ApiKeys.expiresAt] = expiresAt
                it[revoked] = false
                it[deleted] = false
                it[createdAt] = now
            }

            AuditService.log(orgId, userId, "create.api-key", "api-key", id.toString(), entityDisplayName = request.name)

            // Return with plaintext key (shown only once)
            keySummary(id, rawKey)
        }
    }

    /** Lists all API keys for the organization. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<ApiKeySummary> {
        return transaction {
            requireOrgRead(orgId, userId) { it.settings }

            val query = ApiKeys.selectAll()
                .where { (ApiKeys.organizationId eq orgId) and (ApiKeys.deleted eq false) }
            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { summaryFromRow(it) }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Revokes an API key (cannot be undone). */
    fun revoke(orgId: UUID, keyId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            // Same three terms the update below carries. `settings` write
            // admits the caller to the surface; it does not say which org's key
            // this id names, and the name goes into the audit entry.
            val keyName = ApiKeys.selectAll()
                .where { (ApiKeys.id eq keyId) and (ApiKeys.organizationId eq orgId) }
                .firstOrNull()?.get(ApiKeys.name)

            val updated = ApiKeys.update({
                (ApiKeys.id eq keyId) and (ApiKeys.organizationId eq orgId) and (ApiKeys.deleted eq false)
            }) {
                it[revoked] = true
            }
            if (updated == 0) throw NotFoundException()

            AuditService.log(orgId, userId, "revoke.api-key", "api-key", keyId.toString(), entityDisplayName = keyName)
        }
    }

    /** Soft-deletes an API key. */
    fun delete(orgId: UUID, keyId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }

            // Same three terms the update below carries. `settings` write
            // admits the caller to the surface; it does not say which org's key
            // this id names, and the name goes into the audit entry.
            val keyName = ApiKeys.selectAll()
                .where { (ApiKeys.id eq keyId) and (ApiKeys.organizationId eq orgId) }
                .firstOrNull()?.get(ApiKeys.name)

            val updated = ApiKeys.update({
                (ApiKeys.id eq keyId) and (ApiKeys.organizationId eq orgId) and (ApiKeys.deleted eq false)
            }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }
            if (updated == 0) throw NotFoundException()

            AuditService.log(orgId, userId, "delete.api-key", "api-key", keyId.toString(), entityDisplayName = keyName)
        }
    }

    // ── Internals ──

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return "td_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun keySummary(id: UUID, rawKey: String): ApiKeySummary {
        val row = ApiKeys.selectAll()
            .where { ApiKeys.id eq id }
            .first()
        return ApiKeySummary(
            id = row[ApiKeys.id].toString(),
            name = row[ApiKeys.name],
            key = rawKey,
            lastUsedAt = row[ApiKeys.lastUsedAt]?.toString(),
            expiresAt = row[ApiKeys.expiresAt]?.toString(),
            revoked = row[ApiKeys.revoked],
            createdBy = row[ApiKeys.createdBy].toString(),
            createdAt = row[ApiKeys.createdAt].toString(),
        )
    }

    private fun summaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = ApiKeySummary(
        id = row[ApiKeys.id].toString(),
        name = row[ApiKeys.name],
        key = null,
        lastUsedAt = row[ApiKeys.lastUsedAt]?.toString(),
        expiresAt = row[ApiKeys.expiresAt]?.toString(),
        revoked = row[ApiKeys.revoked],
        createdBy = row[ApiKeys.createdBy].toString(),
        createdAt = row[ApiKeys.createdAt].toString(),
    )
}
