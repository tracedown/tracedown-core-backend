package dev.tracedown.gateway.data.me

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// --- Email change ---

@Serializable
data class ChangeEmailRequest(
    val newEmail: String,
    val currentPassword: String,
    /** TOTP (or recovery) code — required when the user is enrolled. */
    val code: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("newEmail", newEmail)?.let(::add)
        Validators.maxLen("newEmail", newEmail, 256)?.let(::add)
        Validators.email("newEmail", newEmail)?.let(::add)
        Validators.notBlank("currentPassword", currentPassword)?.let(::add)
        Validators.maxLen("currentPassword", currentPassword, 256)?.let(::add)
        Validators.maxLen("code", code, 64)?.let(::add)
    }
}

// --- Personal data export ---

/** The user's profile row, secrets excluded (no password hash, no TOTP secret). */
@Serializable
data class ExportProfile(
    val id: String,
    val email: String,
    val displayName: String,
    val totpEnabled: Boolean,
    val totpEnrolledAt: String? = null,
    val selectedOrgId: String? = null,
    val isActive: Boolean,
    val createdAt: String,
)

@Serializable
data class ExportSession(
    val ipAddress: String? = null,
    val userAgent: String? = null,
    val createdAt: String,
    val lastActiveAt: String,
    val expiresAt: String,
    val revoked: Boolean,
    val current: Boolean,
)

@Serializable
data class ExportOrgMembership(
    val organizationId: String,
    val organizationName: String,
    val status: String,
    val joinedAt: String? = null,
    val isOwner: Boolean,
    val groups: List<String>,
)

@Serializable
data class ExportResourceGrant(
    val organizationId: String,
    val resourceType: String,
    val resourceId: String,
    val permissions: Short,
)

/**
 * One audit entry the caller appears in. [role] says on which side: `"actor"`
 * (they performed it), `"subject"` (it was performed on them — an invite, a
 * removal, a group assignment, all recorded under someone else's actor id), or
 * `"both"`. Subject-side entries are part of the disclosure precisely because
 * they are the ones that tend to carry the caller's own email or display name.
 */
@Serializable
data class ExportAuditEntry(
    val organizationId: String,
    val action: String,
    val role: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val entityDisplayName: String? = null,
    val diff: JsonElement? = null,
    val comment: String? = null,
    val createdAt: String,
)

/** API key metadata only — key material is never exported. */
@Serializable
data class ExportApiKey(
    val organizationId: String,
    val name: String,
    val lastUsedAt: String? = null,
    val expiresAt: String? = null,
    val revoked: Boolean,
    val createdAt: String,
)

@Serializable
data class ExportNotificationSilence(
    val organizationId: String,
    val channel: String,
    val workspaceId: String? = null,
    val projectId: String? = null,
    val serviceId: String? = null,
    val quietHours: String? = null,
    val config: JsonElement? = null,
)

/** Variable metadata only — values are write-only and never exported. */
@Serializable
data class ExportVariable(
    val scope: String,
    val scopeId: String,
    val key: String,
    val secret: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class ExportSentInvite(
    val organizationId: String,
    val email: String,
    val invitedAt: String? = null,
    val inviteExpiresAt: String? = null,
)

/**
 * One notification-delivery record addressed to the subject's email address.
 *
 * The purge erases these rows on erasure (matched by email), so Art. 15 access
 * must disclose exactly what Art. 17 would later delete. Message bodies are not
 * stored on the row, so there is none to include.
 */
@Serializable
data class ExportNotificationLogEntry(
    val organizationId: String,
    val channel: String,
    val recipient: String,
    val status: String,
    val error: String? = null,
    val createdAt: String,
)

/**
 * Versioned envelope for the personal data export. Section names and shapes
 * are a stable contract — additions bump [exportVersion].
 */
@Serializable
data class UserDataExport(
    val exportVersion: Int = 2,
    val generatedAt: String,
    val profile: ExportProfile,
    val sessions: List<ExportSession>,
    val orgMemberships: List<ExportOrgMembership>,
    val resourceGrants: List<ExportResourceGrant>,
    val auditLog: List<ExportAuditEntry>,
    val apiKeys: List<ExportApiKey>,
    val notificationSilences: List<ExportNotificationSilence>,
    val variables: List<ExportVariable>,
    val sentInvites: List<ExportSentInvite>,
    val notificationLog: List<ExportNotificationLogEntry>,
)
