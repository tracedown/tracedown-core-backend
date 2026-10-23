package dev.tracedown.gateway.data.notifications

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

@Serializable
data class CreateNotificationTemplateRequest(
    @JsonSchema.MaxLength(64)
    val name: String,
    @JsonSchema.MaxLength(10000)
    val text: String,
    val projectIds: List<String>? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.notBlank("text", text)?.let(::add)
        Validators.maxLen("text", text, 10000)?.let(::add)
        Validators.each(projectIds) { Validators.uuid("projectId", it) }?.let(::add)
    }
}

@Serializable
data class UpdateNotificationTemplateRequest(
    @JsonSchema.MaxLength(64)
    val name: String? = null,
    @JsonSchema.MaxLength(10000)
    val text: String? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.maxLen("text", text, 10000)?.let(::add)
    }
}

@Serializable
data class NotificationTemplateSummary(
    val id: String,
    val name: String,
    val text: String,
    val projectIds: List<String>,
    val createdAt: String,
)

@Serializable
data class BindProjectRequest(
    val projectId: String,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("projectId", projectId)?.let(::add)
        Validators.uuid("projectId", projectId)?.let(::add)
    }
}
