package dev.tracedown.gateway.data.domains

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

@Serializable
data class CreateDomainRequest(
    val domain: String,
    val verificationType: String = "dns-01",
    val wildcardEnabled: Boolean = true,
    val exceptions: List<String>? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("domain", domain)?.let(::add)
        Validators.maxLen("domain", domain, 256)?.let(::add)
        Validators.oneOf("verificationType", verificationType, setOf("http-01", "dns-01"))?.let(::add)
        Validators.each(exceptions) { Validators.maxLen("exceptions", it, 256) }?.let(::add)
    }
}

@Serializable
data class UpdateDomainRequest(
    val wildcardEnabled: Boolean? = null,
    val exceptions: List<String>? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.each(exceptions) { Validators.maxLen("exceptions", it, 256) }?.let(::add)
    }
}

@Serializable
data class DomainSummary(
    val id: String,
    val domain: String,
    val challenge: String,
    val verificationType: String,
    val status: String,
    val verifiedAt: String?,
    val wildcardEnabled: Boolean,
    val exceptions: List<String>,
    val lastCheckedAt: String?,
    val lapsed: Boolean,
    /** How the DNS record was placed: a provider id, a host's own method, or null for by hand. */
    val dnsSetupMethod: String?,
    val dnsSetupAt: String?,
)

@Serializable
data class VerifyDomainResponse(
    val verified: Boolean,
    val status: String,
    val error: String? = null,
)
