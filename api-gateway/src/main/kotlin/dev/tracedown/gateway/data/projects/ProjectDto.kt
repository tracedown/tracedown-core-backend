package dev.tracedown.gateway.data.projects

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import dev.tracedown.gateway.data.metrics.ServiceMetricsDto
import kotlinx.serialization.Serializable

@Serializable
data class CreateProjectRequest(val workspaceId: String, val name: String) : Validatable {
    override fun validate() = buildList {
        Validators.uuid("workspaceId", workspaceId)?.let(::add)
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
    }
}

@Serializable
data class UpdateProjectRequest(val name: String? = null) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
    }
}

@Serializable
data class ProjectSummary(
    val id: String,
    val workspaceId: String,
    val name: String,
    val createdAt: String,
    val metrics: ServiceMetricsDto? = null,
    val serviceCount: Int = 0,
)
