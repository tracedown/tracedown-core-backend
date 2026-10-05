package dev.tracedown.gateway.data.orgs

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

/** One principal holding a grant on a resource. */
@Serializable
data class ResourceAccessEntry(
    /** "user" or "group". */
    @JsonSchema.Enum("user", "group")
    val principalType: String,
    /** userId for users, groupId for groups. */
    val principalId: String,
    val name: String,
    val email: String? = null,
    @JsonSchema.Minimum(1.0)
    @JsonSchema.Maximum(2.0)
    @JsonSchema.Description("1 read, 2 write.")
    val permissions: Short,
)

/** Grants or updates one principal's level (1 read / 2 write) on a resource. */
@Serializable
data class UpsertAccessRequest(
    @JsonSchema.Enum("user", "group")
    val principalType: String,
    val principalId: String,
    @JsonSchema.Minimum(1.0)
    @JsonSchema.Maximum(2.0)
    @JsonSchema.Description("1 read, 2 write.")
    val permissions: Short,
) : Validatable {
    override fun validate() = buildList {
        Validators.oneOf("principalType", principalType, setOf("user", "group"))?.let(::add)
        Validators.notBlank("principalId", principalId)?.let(::add)
        Validators.uuid("principalId", principalId)?.let(::add)
        Validators.inRange("permissions", permissions.toInt(), 1..2)?.let(::add)
    }
}
