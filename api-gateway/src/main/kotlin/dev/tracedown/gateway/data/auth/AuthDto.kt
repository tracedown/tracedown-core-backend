package dev.tracedown.gateway.data.auth

import dev.tracedown.common.auth.PermissionSections
import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// --- Auth ---

@Serializable
data class LoginRequest(val email: String, val password: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("email", email)?.let(::add)
        Validators.maxLen("email", email, 256)?.let(::add)
        Validators.email("email", email)?.let(::add)
        Validators.notBlank("password", password)?.let(::add)
        Validators.maxLen("password", password, 256)?.let(::add)
    }
}

@Serializable
data class LoginResponse(
    val token: String? = null,
    val expiresAt: String? = null,
    val user: UserSummary? = null,
    val totpRequired: Boolean = false,
    val challenge: String? = null,
    val totpSetupRequired: Boolean = false,
    val setupToken: String? = null,
    val recoveryCodes: List<String>? = null,
)

@Serializable
data class TotpVerifyRequest(val challenge: String, val code: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("challenge", challenge)?.let(::add)
        Validators.maxLen("challenge", challenge, 255)?.let(::add)
        Validators.notBlank("code", code)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

// --- TOTP Setup ---

@Serializable
data class TotpSetupRequest(val setupToken: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("setupToken", setupToken)?.let(::add)
        Validators.maxLen("setupToken", setupToken, 255)?.let(::add)
    }
}

@Serializable
data class TotpSetupResponse(
    val secret: String,
    val otpauthUri: String,
    val confirmToken: String,
)

@Serializable
data class TotpConfirmRequest(val confirmToken: String, val code: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("confirmToken", confirmToken)?.let(::add)
        Validators.maxLen("confirmToken", confirmToken, 255)?.let(::add)
        Validators.notBlank("code", code)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

@Serializable
data class UserSummary(
    val id: String,
    val email: String,
    val displayName: String,
    val totpEnabled: Boolean,
    val selectedOrgId: String? = null,
)

/**
 * Org-level section access levels for the session.
 *
 * Built-in sections are fixed fields; sections registered by additional modules
 * (see `PermissionSections`) carry their levels in [extra] and are written to
 * the wire as siblings of the built-in keys, matching the permission cache and
 * the shape used by the org permission API. With no registered sections the
 * output is byte-identical to the built-in-only form.
 */
@Serializable(with = OrgPermissionsDtoSerializer::class)
data class OrgPermissionsDto(
    val users: Short,
    val settings: Short,
    val domains: Short,
    val webhooks: Short,
    val notifications: Short,
    val admin: Short,
    val workspaces: Short,
    val isOwner: Boolean,
    val extra: Map<String, Short> = emptyMap(),
)

/** Serializes [OrgPermissionsDto] flat, with extension section keys as siblings. */
object OrgPermissionsDtoSerializer : KSerializer<OrgPermissionsDto> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("OrgPermissionsDto")

    override fun serialize(encoder: Encoder, value: OrgPermissionsDto) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error("OrgPermissionsDto only supports JSON serialization")
        jsonEncoder.encodeJsonElement(
            buildJsonObject {
                put("users", value.users.toInt())
                put("settings", value.settings.toInt())
                put("domains", value.domains.toInt())
                put("webhooks", value.webhooks.toInt())
                put("notifications", value.notifications.toInt())
                put("admin", value.admin.toInt())
                put("workspaces", value.workspaces.toInt())
                for ((key, level) in value.extra) {
                    put(key, level.toInt())
                }
                put("isOwner", value.isOwner)
            }
        )
    }

    override fun deserialize(decoder: Decoder): OrgPermissionsDto {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error("OrgPermissionsDto only supports JSON deserialization")
        val obj = jsonDecoder.decodeJsonElement().jsonObject
        fun level(key: String): Short = obj[key]?.jsonPrimitive?.int?.toShort() ?: 0
        return OrgPermissionsDto(
            users = level("users"),
            settings = level("settings"),
            domains = level("domains"),
            webhooks = level("webhooks"),
            notifications = level("notifications"),
            admin = level("admin"),
            workspaces = level("workspaces"),
            isOwner = obj["isOwner"]?.jsonPrimitive?.boolean ?: false,
            extra = obj.entries
                .filter { it.key !in PermissionSections.BUILTIN && it.key != "isOwner" }
                .associate { it.key to it.value.jsonPrimitive.int.toShort() },
        )
    }
}

@Serializable
data class MeResponse(
    val user: UserSummary,
    /**
     * The org this session is scoped to (the token's org, not the user's stored
     * last_org). Null when the session has no org. The client compares it to the
     * membership list to detect a session pointing at an org the user has lost
     * access to, and re-scopes rather than showing a mismatched header/body.
     */
    val organizationId: String? = null,
    val permissions: OrgPermissionsDto? = null,
    /** Effective resource grants (own + groups), keyed "type::id" → level. */
    val resources: Map<String, Short> = emptyMap(),
    /** The selected org's default IANA timezone (null without org context). */
    val orgDefaultTimezone: String? = null,
    /** Platform flag: when true, domain verification is a no-op (no Domains UI). */
    val trustedDomainMode: Boolean = true,
)

// --- Org Switch ---

@Serializable
data class SwitchOrgRequest(val orgId: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("orgId", orgId)?.let(::add)
        Validators.uuid("orgId", orgId)?.let(::add)
    }
}

// --- Password Reset ---

@Serializable
data class PasswordResetRequest(val email: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("email", email)?.let(::add)
        Validators.maxLen("email", email, 256)?.let(::add)
        Validators.email("email", email)?.let(::add)
    }
}

@Serializable
data class PasswordResetConfirm(val token: String, val newPassword: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("token", token)?.let(::add)
        Validators.maxLen("token", token, 255)?.let(::add)
        Validators.notBlank("newPassword", newPassword)?.let(::add)
        Validators.maxLen("newPassword", newPassword, 256)?.let(::add)
    }
}

// --- Profile ---

@Serializable
data class UpdateProfileRequest(val displayName: String? = null) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("displayName", displayName, 128)?.let(::add)
    }
}

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("currentPassword", currentPassword)?.let(::add)
        Validators.maxLen("currentPassword", currentPassword, 256)?.let(::add)
        Validators.notBlank("newPassword", newPassword)?.let(::add)
        Validators.maxLen("newPassword", newPassword, 256)?.let(::add)
    }
}

// --- TOTP ---

@Serializable
data class TotpDisableRequest(val code: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("code", code)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

// --- Sessions ---

@Serializable
data class SessionSummary(
    val id: String,
    val ipAddress: String?,
    val userAgent: String?,
    val createdAt: String,
    val lastActiveAt: String,
    val expiresAt: String,
    val current: Boolean,
)

@Serializable
data class RevokedCount(val revoked: Int)

@Serializable
data class DeleteAccountRequest(
    val password: String,
    /** TOTP or recovery code; required when the account has 2FA enrolled. */
    val code: String? = null,
    /**
     * Delete along with the account every organization it owns and is the only
     * member of. Owned organizations that still have other members are never
     * covered by this — they have to be handed to another owner first.
     */
    val deleteOwnedOrgs: Boolean = false,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("password", password)?.let(::add)
        Validators.maxLen("password", password, 256)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

/**
 * An organization this account owns, and therefore has to deal with before it
 * can be closed. [soleMember] means nobody else holds a membership, so the
 * organization can simply go with the account.
 */
@Serializable
data class OwnedOrgSummary(
    val id: String,
    val name: String,
    val soleMember: Boolean,
)

/**
 * What the current user is allowed to do to their own account, and what stands
 * in the way. Fetched by the profile page to decide which sections to render.
 */
@Serializable
data class ProfileCapabilitiesResponse(
    val allowProfileEdit: Boolean,
    val allowAccountClosure: Boolean,
    /** Empty unless [allowAccountClosure] — nothing else consumes it. */
    val ownedOrgs: List<OwnedOrgSummary> = emptyList(),
)

// --- Org Membership ---

@Serializable
data class OrgMembership(
    val id: String,
    val name: String,
)
