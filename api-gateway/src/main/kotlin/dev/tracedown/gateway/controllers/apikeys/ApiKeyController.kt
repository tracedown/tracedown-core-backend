package dev.tracedown.gateway.controllers.apikeys

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.ApiKeyFormat
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.auth.actingPairs
import dev.tracedown.common.auth.resolveCachedPermissions
import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Users
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.apikeys.ApiKeyAccess
import dev.tracedown.gateway.data.apikeys.ApiKeyInfo
import dev.tracedown.gateway.data.apikeys.ApiKeySummary
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.SystemLimitsConfig
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * API keys: minting, listing, revoking, deleting.
 *
 * **A key belongs to a user and acts as that user, in one organization.** It is
 * the user's own credential — any member may mint one for themselves, and
 * nobody can mint one that acts as somebody else. It carries no permissions of
 * its own: each request it authenticates is checked against what its user may
 * do at that moment (see `ApiKeyAuthenticator`), narrowed by the key's
 * `access` ceiling. So a key can never do more than the person who holds it,
 * and stops working the moment that person could no longer act themselves.
 *
 * Two surfaces manage them, both session-only — a key cannot mint, list or
 * revoke keys:
 *  - the user's own keys, across every organization they belong to
 *    ([create], [listOwn], [revokeOwn], [deleteOwn]);
 *  - an organization's oversight of the keys that act in it ([listForOrg],
 *    [revoke], [delete]), gated by the `settings` section. An administrator can
 *    cut a member's key off; there is deliberately no way to create one here.
 *
 * Only a SHA-256 digest of a key is stored. The key is returned once, by
 * [create], and cannot be recovered afterwards.
 *
 * Minting asks for the password again, and a second factor when the user has
 * one. A key outlives the session it was made in — deliberately: it is not
 * revoked by a password reset — so a session alone must not be enough to leave
 * one behind.
 */
object ApiKeyController {

    private val secureRandom = SecureRandom()

    @Volatile
    private var maxPerUser: Int = SystemLimitsConfig.DEFAULT_MAX_API_KEYS_PER_USER

    /** Wires the configured per-user cap. Values below 1 are ignored as misconfiguration. */
    fun init(maxPerUser: Int) {
        if (maxPerUser >= 1) this.maxPerUser = maxPerUser
    }

    /**
     * Mints a key that acts as [userId] in [orgId]. Returns the key itself,
     * this once.
     *
     * Needs no permission beyond an active membership: the key can do no more
     * than its user, so there is nothing here for a permission to guard. It
     * does need the user's identity re-proved — see the class note. Injectable
     * so a host can refuse the creation.
     */
    @Injectable("api-key.create")
    fun create(orgId: UUID, request: CreateApiKeyRequest, userId: UUID): ApiKeySummary {
        val access = accessLevel(request.access)

        // Everything this function would refuse for is checked before the
        // identity is: verifying consumes a TOTP step or a recovery code, and
        // a user at the cap who typed a recovery code must not lose it to a
        // refusal that was going to come anyway. The checks run again, under
        // the lock, before the row is written.
        transaction {
            resolveCachedPermissions(orgId, userId) ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
            if (heldBy(userId) >= maxPerUser) throw BadRequestException(ErrorCodes.API_KEY_LIMIT_REACHED)
        }
        AuthController.verifyIdentity(userId, request.password, request.code)

        // The key is held back from the hooks: what an after-hook is handed, and
        // may log or pass on, is the key's description.
        val rawKey = generateKey()

        val created = Interceptors.injectableInTx(
            "api-key.create",
            InterceptorContext(orgId = orgId, userId = userId),
            // READ COMMITTED, for the cap below — see the comment there.
            isolation = Connection.TRANSACTION_READ_COMMITTED,
        ) {
            resolveCachedPermissions(orgId, userId) ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)

            // The cap is a count followed by an insert, and two creations by
            // the same user must not both read "one slot left". Locking the
            // user's row makes them take turns; READ COMMITTED is what makes
            // taking turns mean anything, because the one that waited then
            // counts what the other committed. At the pool's REPEATABLE READ it
            // would count from the snapshot it took before it started waiting.
            // NO KEY UPDATE: enough to make mints take turns, without holding up
            // every insert elsewhere that merely references this user.
            // The membership row first, shared: a removal UPDATEs it, so a
            // removal under way makes this wait for its outcome, and one that
            // starts after this waits for the key to be committed — and then
            // revokes it with the rest (MembershipAccess.revokeAll). Taken in
            // the order a removal takes them (membership, then account), so the
            // two cannot deadlock.
            OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.userId eq userId) }
                .forUpdate(ForUpdateOption.PostgreSQL.ForShare).toList()
            val user = Users.selectAll().where { Users.id eq userId }
                .forUpdate(ForUpdateOption.PostgreSQL.ForNoKeyUpdate).firstOrNull()
                ?: throw NotFoundException()
            // Asked again now that the row is held. The check above ran before
            // the lock; a mint that waited on it behind an erasure, a
            // deactivation or a removal would otherwise write a key for an
            // account that is no longer there — dead, but never purged with it.
            // READ COMMITTED: these reads see what the other side committed.
            if (user[Users.deleted] || !user[Users.isActive]) throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
            resolveCachedPermissions(orgId, userId) ?: throw ForbiddenException(ErrorCodes.NOT_ORG_MEMBER)
            if (heldBy(userId) >= maxPerUser) throw BadRequestException(ErrorCodes.API_KEY_LIMIT_REACHED)

            val id = UUID.randomUUID()
            val now = Instant.now()

            ApiKeys.insert {
                it[ApiKeys.id] = id
                it[organizationId] = orgId
                it[createdBy] = userId
                it[name] = request.name
                it[keyHash] = TokenHasher.sha256Hex(rawKey)
                it[keyPrefix] = ApiKeyFormat.displayPrefix(rawKey)
                it[ApiKeys.access] = access
                it[expiresAt] = request.expiresInDays?.let { days -> now.plusSeconds(days.toLong() * 24 * 3600) }
                it[revoked] = false
                it[deleted] = false
                it[createdAt] = now
            }

            AuditService.log(orgId, userId, "create.api-key", "api-key", id.toString(), entityDisplayName = request.name)

            summaries(listOf(detailed().where { ApiKeys.id eq id }.first())).single()
        }
        return created.copy(key = rawKey)
    }

    /** The caller's own keys, in every organization, newest first unless asked otherwise. */
    fun listOwn(userId: UUID, pfs: PfsParams): Page<ApiKeySummary> = transaction {
        val query = detailed()
            .where { (ApiKeys.createdBy eq userId) and (ApiKeys.deleted eq false) }
        if (pfs.sorters.isEmpty()) query.orderBy(ApiKeys.createdAt to SortOrder.DESC, ApiKeys.id to SortOrder.ASC)
        val (pagedQuery, total) = query.applyPfs(pfs)
        Page(items = summaries(pagedQuery.toList()), total = total, page = pfs.page, pageSize = pfs.pageSize)
    }

    /**
     * Every key that acts in the organization, whoever holds it. Filterable and
     * sortable on the key's and its user's listed columns; grouped by user
     * unless the caller asks for another order, since "whose keys are these" is
     * the question the list is opened with.
     */
    fun listForOrg(orgId: UUID, userId: UUID, pfs: PfsParams): Page<ApiKeySummary> = transaction {
        requireOrgRead(orgId, userId) { it.settings }

        val query = detailed()
            .where { (ApiKeys.organizationId eq orgId) and (ApiKeys.deleted eq false) }
        if (pfs.sorters.isEmpty()) {
            query.orderBy(
                Users.displayName to SortOrder.ASC,
                Users.email to SortOrder.ASC,
                ApiKeys.createdAt to SortOrder.DESC,
                ApiKeys.id to SortOrder.ASC,
            )
        }
        val (pagedQuery, total) = query.applyPfs(pfs)
        Page(items = summaries(pagedQuery.toList()), total = total, page = pfs.page, pageSize = pfs.pageSize)
    }

    /** Revokes one of the caller's own keys (cannot be undone). */
    fun revokeOwn(userId: UUID, keyId: UUID) {
        transaction {
            val key = ownKey(userId, keyId)
            // Already revoked is already done; saying so twice in the audit log
            // would record an event that did not happen.
            if (key[ApiKeys.revoked]) return@transaction
            markRevoked(key[ApiKeys.id])
            AuditService.log(
                key[ApiKeys.organizationId], userId, "revoke.api-key", "api-key", keyId.toString(),
                entityDisplayName = key[ApiKeys.name],
            )
        }
    }

    /** Soft-deletes one of the caller's own keys. */
    fun deleteOwn(userId: UUID, keyId: UUID) {
        transaction {
            val key = ownKey(userId, keyId)
            markDeleted(key[ApiKeys.id])
            AuditService.log(
                key[ApiKeys.organizationId], userId, "delete.api-key", "api-key", keyId.toString(),
                entityDisplayName = key[ApiKeys.name],
            )
        }
    }

    /** Revokes a key acting in the organization (cannot be undone). */
    fun revoke(orgId: UUID, keyId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }
            // `settings` write admits the caller to the surface; it does not say
            // which org's key this id names — the lookup does.
            val key = orgKey(orgId, keyId)
            if (key[ApiKeys.revoked]) return@transaction
            markRevoked(key[ApiKeys.id])
            AuditService.log(orgId, userId, "revoke.api-key", "api-key", keyId.toString(), entityDisplayName = key[ApiKeys.name])
        }
    }

    /** Soft-deletes a key acting in the organization. */
    fun delete(orgId: UUID, keyId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.settings }
            val key = orgKey(orgId, keyId)
            markDeleted(key[ApiKeys.id])
            AuditService.log(orgId, userId, "delete.api-key", "api-key", keyId.toString(), entityDisplayName = key[ApiKeys.name])
        }
    }

    /**
     * Revokes every live key [userId] holds in [orgId]. Called when the
     * membership ends: the keys are already unusable without it, and revoking
     * them keeps a later re-invite from bringing old credentials back to life.
     * Each is audited with no actor — nobody chose to revoke this key; it went
     * with the membership — so the list's "revoked" has a reason beside it.
     * Must be called within a transaction.
     */
    internal fun revokeAllFor(orgId: UUID, userId: UUID, reason: String = "Membership ended") {
        val live = ApiKeys.selectAll()
            .where {
                (ApiKeys.organizationId eq orgId) and (ApiKeys.createdBy eq userId) and (ApiKeys.revoked eq false)
            }
            .toList()
        for (key in live) {
            markRevoked(key[ApiKeys.id])
            AuditService.log(
                orgId, null, "revoke.api-key", "api-key", key[ApiKeys.id].toString(),
                entityDisplayName = key[ApiKeys.name],
                comment = reason,
            )
        }
    }

    /** What the key-authenticated API tells a key about itself. */
    fun describe(keyId: UUID): ApiKeyInfo = transaction {
        val row = detailed().where { ApiKeys.id eq keyId }.firstOrNull() ?: throw NotFoundException()
        ApiKeyInfo(
            id = row[ApiKeys.id].toString(),
            name = row[ApiKeys.name],
            prefix = row[ApiKeys.keyPrefix],
            access = accessName(row[ApiKeys.access]),
            expiresAt = row[ApiKeys.expiresAt]?.toString(),
            organization = ApiKeyInfo.Organization(
                id = row[ApiKeys.organizationId].toString(),
                name = row[Organizations.name],
            ),
            user = ApiKeyInfo.User(
                id = row[ApiKeys.createdBy].toString(),
                email = row[Users.email],
            ),
        )
    }

    // ── Internals ──

    /** Keys the user holds that count against the cap: everything not yet deleted. */
    private fun heldBy(userId: UUID): Long =
        ApiKeys.selectAll()
            .where { (ApiKeys.createdBy eq userId) and (ApiKeys.deleted eq false) }
            .count()

    /** Keys joined to the organization they act in and the user they act as (absent once erased). */
    private fun detailed() = ApiKeys
        .join(Organizations, JoinType.INNER, ApiKeys.organizationId, Organizations.id)
        .join(Users, JoinType.LEFT, ApiKeys.createdBy, Users.id)
        .selectAll()

    private fun ownKey(userId: UUID, keyId: UUID): ResultRow =
        ApiKeys.selectAll()
            .where { (ApiKeys.id eq keyId) and (ApiKeys.createdBy eq userId) and (ApiKeys.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()

    private fun orgKey(orgId: UUID, keyId: UUID): ResultRow =
        ApiKeys.selectAll()
            .where { (ApiKeys.id eq keyId) and (ApiKeys.organizationId eq orgId) and (ApiKeys.deleted eq false) }
            .firstOrNull() ?: throw NotFoundException()

    private fun markRevoked(keyId: UUID) {
        ApiKeys.update({ ApiKeys.id eq keyId }) { it[revoked] = true }
    }

    private fun markDeleted(keyId: UUID) {
        val now = Instant.now()
        ApiKeys.update({ ApiKeys.id eq keyId }) {
            // Deleted implies revoked: the row is kept for the retention period,
            // and nothing kept should still be a credential.
            it[revoked] = true
            it[deleted] = true
            it[deletedAt] = now
            // Never later than a purge date the row already has: a delete must
            // not extend how long something already due to go is kept.
            it[purgeAfter] = DeletionRetention.earliestPurge(ApiKeys.purgeAfter, DeletionRetention.purgeAfter(now))
        }
    }

    private fun generateKey(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return ApiKeyFormat.PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun accessLevel(name: String): Short = when (name) {
        ApiKeyAccess.READ -> AccessLevel.READ
        ApiKeyAccess.WRITE -> AccessLevel.WRITE
        else -> throw BadRequestException(ErrorCodes.FIELD_INVALID)
    }

    private fun accessName(level: Short): String =
        if (level.canWrite()) ApiKeyAccess.WRITE else ApiKeyAccess.READ

    /**
     * Describes [rows], including whether each key could act right now.
     *
     * "Could act" is the authenticator's own test — the user exists, is active,
     * and may act in the key's organization — asked for the whole page at once
     * ([actingPairs]) rather than once per row. It is not everything a request
     * meets: a key whose user has yet to enrol a required second factor is
     * `active` here and refused with `totp_enrollment_required` when used.
     */
    private fun summaries(rows: List<ResultRow>): List<ApiKeySummary> {
        val now = Instant.now()
        val acting = actingPairs(
            rows.mapNotNull { row ->
                val actsAs = row[ApiKeys.createdBy] ?: return@mapNotNull null
                if (row[Users.deleted] || !row[Users.isActive]) null else row[ApiKeys.organizationId] to actsAs
            },
        )
        return rows.map { row ->
            val actsAs = row[ApiKeys.createdBy]
            val expiresAt = row[ApiKeys.expiresAt]
            val state = when {
                row[ApiKeys.revoked] -> "revoked"
                expiresAt != null && expiresAt < now -> "expired"
                actsAs == null || (row[ApiKeys.organizationId] to actsAs) !in acting -> "inactive"
                else -> "active"
            }
            ApiKeySummary(
                id = row[ApiKeys.id].toString(),
                name = row[ApiKeys.name],
                prefix = row[ApiKeys.keyPrefix],
                access = accessName(row[ApiKeys.access]),
                state = state,
                organizationId = row[ApiKeys.organizationId].toString(),
                organizationName = row[Organizations.name],
                createdBy = actsAs?.toString(),
                createdByName = if (actsAs == null) null else row[Users.displayName],
                createdByEmail = if (actsAs == null) null else row[Users.email],
                lastUsedAt = row[ApiKeys.lastUsedAt]?.toString(),
                expiresAt = expiresAt?.toString(),
                revoked = row[ApiKeys.revoked],
                createdAt = row[ApiKeys.createdAt].toString(),
            )
        }
    }
}
