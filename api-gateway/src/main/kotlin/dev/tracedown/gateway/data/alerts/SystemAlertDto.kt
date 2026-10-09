package dev.tracedown.gateway.data.alerts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class SystemAlertSummary(
    val id: String,
    val alertType: String,
    val subject: String,
    val severity: String,
    val data: JsonObject? = null,
    val createdAt: String,
    val lastSeenAt: String,
)

/**
 * One system alert episode as the key-authenticated API gives it: the
 * dashboard's [SystemAlertSummary] under public names, and whether the caller
 * dismissed it.
 */
@Serializable
data class PublicSystemAlert(
    val id: String,
    /** What kind of condition: `agent_down`, `dispatch_capacity`, … */
    val type: String,
    /** What it is about — an agent's slug, a service, an endpoint; empty when the type says it all. */
    val subject: String,
    /** `warning` or `error`. */
    val severity: String,
    val data: JsonObject? = null,
    /** When this episode began. */
    val firstSeenAt: String,
    /** When the condition was last observed. */
    val lastSeenAt: String,
    /** When the caller dismissed it; null while they have not. */
    val dismissedAt: String? = null,
)
