package dev.tracedown.gateway.data.silences

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

private val SILENCE_CHANNELS = setOf("email", "webhook", "all", "quiet-hours")

@Serializable
data class CreateSilenceRequest(
    @JsonSchema.Enum("email", "all", "quiet-hours")
    val channel: String,
    val workspaceId: String? = null,
    val projectId: String? = null,
    val serviceId: String? = null,
    @JsonSchema.MaxLength(1024)
    @JsonSchema.Description("A JSON object, as text.")
    val config: String? = null,
    @JsonSchema.MaxLength(256)
    @JsonSchema.Description("`RRULE/minutes/Zone`, as a service's maintenance window.")
    val quietHours: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("channel", channel)?.let(::add)
        Validators.oneOf("channel", channel, SILENCE_CHANNELS)?.let(::add)
        Validators.uuid("workspaceId", workspaceId)?.let(::add)
        Validators.uuid("projectId", projectId)?.let(::add)
        Validators.uuid("serviceId", serviceId)?.let(::add)
        Validators.maxLen("config", config, 1024)?.let(::add)
        Validators.maxLen("quietHours", quietHours, 256)?.let(::add)
    }
}

@Serializable
data class UpdateSilenceRequest(
    @JsonSchema.Description("`email`, `all` or `quiet-hours`.")
    val channel: String? = null,
    val config: String? = null,
    val quietHours: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.oneOf("channel", channel, SILENCE_CHANNELS)?.let(::add)
        Validators.maxLen("config", config, 1024)?.let(::add)
        Validators.maxLen("quietHours", quietHours, 256)?.let(::add)
    }
}

@Serializable
data class SilenceSummary(
    val id: String,
    val orgUserId: String,
    val workspaceId: String?,
    val projectId: String?,
    val serviceId: String?,
    @JsonSchema.Enum("email", "webhook", "all", "quiet-hours")
    val channel: String,
    val config: String?,
    val quietHours: String?,
    /** Display name of the most specific silenced scope (null when scopeless). */
    val resourceName: String? = null,
)
