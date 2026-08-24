package dev.tracedown.gateway.data.workspaces

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateWorkspaceRequest(val name: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 128)?.let(::add)
    }
}

@Serializable
data class UpdateWorkspaceRequest(val name: String? = null) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 128)?.let(::add)
    }
}

@Serializable
data class WorkspaceSummary(
    val id: String,
    val name: String,
    val createdAt: String,
)
