package dev.tracedown.gateway.data

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable

/**
 * Variable types (determined by secret + encrypted flags):
 * - "secret"   (secret=true,  encrypted=true)  → encrypted, never shown after saving
 * - "variable" (secret=false, encrypted=true)   → encrypted at rest, shown on explicit request
 * - "metric"   (secret=false, encrypted=false)  → plaintext, always visible, writable by Lace scripts
 */
@Serializable
data class CreateVariableRequest(
    @JsonSchema.MaxLength(64)
    val key: String,
    @JsonSchema.MaxLength(4096)
    val value: String,
    @JsonSchema.Enum("variable", "secret", "metric")
    @JsonSchema.Default("variable")
    val type: String = "variable",
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("key", key)?.let(::add)
        Validators.maxLen("key", key, 64)?.let(::add)
        Validators.notBlank("value", value)?.let(::add)
        Validators.maxLen("value", value, 4096)?.let(::add)
        Validators.oneOf("type", type, setOf("secret", "variable", "metric"))?.let(::add)
    }
}

@Serializable
data class UpdateVariableRequest(@JsonSchema.MaxLength(4096) val value: String) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("value", value)?.let(::add)
        Validators.maxLen("value", value, 4096)?.let(::add)
    }
}

@Serializable
data class VariableSummary(
    val id: String,
    val key: String,
    val value: String,
    @JsonSchema.Enum("variable", "secret", "metric")
    val type: String,
    val systemType: String? = null,
    val createdAt: String,
    val updatedAt: String,
    /** True when [value] is the mask, not the value: a secret, or an encrypted value not revealed. */
    val masked: Boolean = false,
)

/** Maps the type string to (secret, encrypted) flags. */
fun parseVariableType(type: String): Pair<Boolean, Boolean> = when (type) {
    "secret" -> true to true
    "variable" -> false to true
    "metric" -> false to false
    else -> throw dev.tracedown.gateway.util.BadRequestException(
        "Invalid variable type '$type'. Must be 'secret', 'variable', or 'metric'"
    )
}

/** Maps (secret, encrypted) flags back to the type string. */
fun variableTypeName(secret: Boolean, encrypted: Boolean): String = when {
    secret -> "secret"
    encrypted -> "variable"
    else -> "metric"
}

/** Resource-level variable prefixes. */
private val RESOURCE_PREFIXES = listOf("\$w.", "\$p.", "\$s.")

/**
 * Strips the resource prefix (`$w.`, `$p.`, `$s.`) from a variable key if present.
 * Returns the clean key for storage — prefixes are display-only on the frontend.
 */
fun sanitizeVariableKey(key: String): String {
    val trimmed = key.trim()
    for (prefix in RESOURCE_PREFIXES) {
        if (trimmed.startsWith(prefix)) {
            return trimmed.removePrefix(prefix)
        }
    }
    return trimmed
}
