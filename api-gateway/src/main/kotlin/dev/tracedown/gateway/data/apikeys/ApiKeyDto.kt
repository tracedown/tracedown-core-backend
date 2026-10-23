package dev.tracedown.gateway.data.apikeys

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import dev.tracedown.gateway.util.BadRequestException
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

/**
 * The levels a key can be issued at, as they appear on the wire. `admin`
 * carries everything `write` does and is reserved for organization
 * administration through keys, which no endpoint serves yet.
 */
object ApiKeyAccess {
    const val READ = "read"
    const val WRITE = "write"
    const val ADMIN = "admin"
    val ALL = setOf(READ, WRITE, ADMIN)

    /** The stored level for [name], one of [ALL]. */
    fun level(name: String): Short = when (name) {
        READ -> AccessLevel.READ
        WRITE -> AccessLevel.WRITE
        ADMIN -> ApiKeys.ADMIN_ACCESS
        else -> throw BadRequestException(ErrorCodes.FIELD_INVALID)
    }

    /** The wire name of a stored [level]. */
    fun name(level: Short): String = when {
        level >= ApiKeys.ADMIN_ACCESS -> ADMIN
        level.canWrite() -> WRITE
        else -> READ
    }
}

@Serializable
data class CreateApiKeyRequest(
    val name: String,
    val expiresInDays: Int? = null,
    /** `read`, `write` or `admin`. A key is read-only unless it is asked to be more. */
    val access: String = ApiKeyAccess.READ,
    /** The caller's password: minting a credential asks for it again. */
    val password: String,
    /** A TOTP or recovery code, required when the caller has a second factor. */
    val code: String? = null,
) : Validatable {
    /** Never the password or the code, wherever this ends up printed. */
    override fun toString() = "CreateApiKeyRequest(name=$name, expiresInDays=$expiresInDays, access=$access)"

    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
        Validators.inRange("expiresInDays", expiresInDays, 1..3650)?.let(::add)
        Validators.oneOf("access", access, ApiKeyAccess.ALL)?.let(::add)
        Validators.notBlank("password", password)?.let(::add)
    }
}

@Serializable
data class ApiKeySummary(
    val id: String,
    val name: String,
    /** The key itself. Present only in the response that creates it. */
    val key: String? = null,
    /** Leading characters of the key, for telling keys apart. */
    val prefix: String?,
    /** `read`, `write` or `admin`. */
    val access: String,
    /** `active`, `revoked`, `expired`, or `inactive` — the user it acts as cannot currently act in the organization. */
    val state: String,
    val organizationId: String,
    val organizationName: String,
    /** The user the key acts as. Null once that account has been erased. */
    val createdBy: String?,
    val createdByName: String? = null,
    val createdByEmail: String? = null,
    val lastUsedAt: String?,
    val expiresAt: String?,
    val revoked: Boolean,
    val createdAt: String,
)

/** What a key learns about itself from the key-authenticated API. */
@Serializable
data class ApiKeyInfo(
    val id: String,
    val name: String,
    val prefix: String?,
    @JsonSchema.Enum("read", "write", "admin")
    val access: String,
    val expiresAt: String?,
    val organization: Organization,
    val user: User,
) {
    @Serializable
    data class Organization(val id: String, val name: String)

    @Serializable
    data class User(val id: String, val email: String)
}
