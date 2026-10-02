package dev.tracedown.gateway.data.audit

import kotlinx.serialization.Serializable

@Serializable
data class AuditLogEntry(
    val id: String,
    val userId: String?,
    /** Actor display name/email, resolved server-side (null for system actions). */
    val actorName: String? = null,
    val actorEmail: String? = null,
    val action: String,
    val entityType: String?,
    val entityId: String?,
    /** What the entity was called at the time of the change (null for system-wide actions). */
    val entityDisplayName: String? = null,
    val diff: String?,
    val comment: String?,
    /** The API key the action came through, when it was not a signed-in session. */
    val apiKeyId: String? = null,
    /** That key's name, while the key still exists. */
    val apiKeyName: String? = null,
    val createdAt: String,
)

