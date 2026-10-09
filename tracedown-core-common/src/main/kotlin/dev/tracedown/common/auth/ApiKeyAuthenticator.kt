package dev.tracedown.common.auth

import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/** What a usable API key yields: the key, and the user and organization it acts for. */
data class ApiKeyContext(
    val keyId: UUID,
    val userId: UUID,
    val organizationId: UUID,
    val email: String,
    val totpEnabled: Boolean,
    /** The key's own ceiling — an [AccessLevel]: read or write. */
    val access: Short,
    /** The user's permissions in [organizationId], as they were resolved to let the key in. */
    val permissions: CachedPermissions? = null,
)

/** Outcome of validating a presented API key, with the reason when it is refused. */
sealed interface ApiKeyResult {
    data class Valid(val context: ApiKeyContext) : ApiKeyResult
    data class Invalid(val reason: Reason) : ApiKeyResult

    enum class Reason { NOT_FOUND, REVOKED, EXPIRED, OWNER_GONE, OWNER_INACTIVE, NOT_MEMBER }
}

/** The shape of a key, shared by whoever mints one and whoever is shown one. */
object ApiKeyFormat {
    const val PREFIX = "td_"

    /** Characters of a key kept in the clear to identify it: the marker plus eight of the secret's 43. */
    const val DISPLAY_PREFIX_LENGTH = 11

    /** `td_` followed by 32 random bytes in unpadded base64url. */
    const val LENGTH = 46

    fun displayPrefix(key: String): String = key.take(DISPLAY_PREFIX_LENGTH)

    /** Whether [token] has the outward shape of a key. Says nothing about whether one exists. */
    fun looksLikeKey(token: String): Boolean = token.length == LENGTH && token.startsWith(PREFIX)
}

/**
 * The single answer to "is this API key usable, and as whom?" — the counterpart
 * of [SessionAuthenticator].
 *
 * A key carries no authority of its own. It names a user and an organization,
 * and is usable only while that user could act there themselves: the account
 * exists and is active, and it is an active member of the organization (or its
 * owner). Membership is read through [resolveCachedPermissions] — the same
 * function every permission check goes through — so "is a member" can never
 * mean one thing here and another where the permissions are applied.
 *
 * Nothing about the user's permissions is copied onto the key. What the key may
 * do is decided per request, from the user's current grants, by the same checks
 * a signed-in session meets; the key's own [ApiKeyContext.access] only narrows
 * that further.
 */
object ApiKeyAuthenticator {

    /** Only the digest is stored at rest: the caller hashes the presented key (`TokenHasher`) and this matches. */
    fun authenticateDigest(keyHash: String): ApiKeyResult = authenticate { ApiKeys.keyHash eq keyHash }

    /**
     * The same verdict, again, for a key already authenticated — by its id.
     * For a call that outlasts the moment it was let in: whatever happened to
     * the key or its user since applies from the next ask.
     */
    fun recheck(keyId: UUID): ApiKeyResult = authenticate { ApiKeys.id eq keyId }

    private fun authenticate(match: () -> Op<Boolean>): ApiKeyResult = transaction {
        val row = ApiKeys
            .join(Users, JoinType.LEFT, ApiKeys.createdBy, Users.id)
            .selectAll()
            .where { match() and (ApiKeys.deleted eq false) }
            .firstOrNull()
            ?: return@transaction ApiKeyResult.Invalid(ApiKeyResult.Reason.NOT_FOUND)

        val userId = row[ApiKeys.createdBy]
        val expiresAt = row[ApiKeys.expiresAt]
        when {
            row[ApiKeys.revoked] ->
                ApiKeyResult.Invalid(ApiKeyResult.Reason.REVOKED)
            expiresAt != null && expiresAt < Instant.now() ->
                ApiKeyResult.Invalid(ApiKeyResult.Reason.EXPIRED)
            // The account was erased: the key has nobody left to act as.
            userId == null || row[Users.deleted] ->
                ApiKeyResult.Invalid(ApiKeyResult.Reason.OWNER_GONE)
            !row[Users.isActive] ->
                ApiKeyResult.Invalid(ApiKeyResult.Reason.OWNER_INACTIVE)
            else -> {
                val permissions = resolveCachedPermissions(row[ApiKeys.organizationId], userId)
                    ?: return@transaction ApiKeyResult.Invalid(ApiKeyResult.Reason.NOT_MEMBER)
                ApiKeyResult.Valid(
                    ApiKeyContext(
                        keyId = row[ApiKeys.id],
                        userId = userId,
                        organizationId = row[ApiKeys.organizationId],
                        email = row[Users.email],
                        totpEnabled = row[Users.totpEnabled],
                        access = row[ApiKeys.access],
                        permissions = permissions,
                    ),
                )
            }
        }
    }
}
