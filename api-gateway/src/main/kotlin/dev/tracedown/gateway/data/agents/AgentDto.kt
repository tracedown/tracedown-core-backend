package dev.tracedown.gateway.data.agents

import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable
import java.net.InetAddress
import java.net.URI

/** Request body for POST /internal/agents/register. */
@Serializable
data class AgentRegisterRequest(
    val bootstrapToken: String,
    val csrPem: String,
    val agentUri: String,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("bootstrapToken", bootstrapToken)?.let(::add)
        Validators.maxLen("bootstrapToken", bootstrapToken, 255)?.let(::add)
        Validators.notBlank("csrPem", csrPem)?.let(::add)
        Validators.maxLen("csrPem", csrPem, 8192)?.let(::add)
        Validators.notBlank("agentUri", agentUri)?.let(::add)
        Validators.maxLen("agentUri", agentUri, 255)?.let(::add)
        validateAgentUri("agentUri", agentUri)?.let(::add)
    }
}

/**
 * Validates the address the scheduler will dial for this agent.
 *
 * The scheduler ships probe scripts and resolved secret variables to this URI,
 * so it must be `https://` (an `http://` URI would send them in the clear with
 * no mTLS) with a syntactically valid host. Loopback, link-local (incl. the
 * cloud metadata range), the unspecified/wildcard address and multicast are
 * rejected — an agent never legitimately lives there, and they are the classic
 * scheduler-side SSRF pivots.
 *
 * Private (RFC1918 / IPv6 ULA) addresses are intentionally NOT rejected: agents
 * run on the platform's private internal network, so that is their normal home.
 * SSRF to a non-agent internal host is separately foreclosed at dispatch time —
 * the scheduler only completes the mTLS handshake with a peer presenting a
 * CA-signed serverAuth certificate whose SAN matches the target agent slug.
 *
 * Hostnames are accepted without DNS resolution (resolution here would be
 * fragile and block the request thread); only IP-literal hosts are range-checked.
 */
private fun validateAgentUri(field: String, value: String): String? {
    val code = "invalid_$field"
    val uri = try {
        URI(value.trim())
    } catch (e: Exception) {
        return code
    }
    if (!uri.scheme.equals("https", ignoreCase = true)) return code
    val rawHost = uri.host ?: return code
    // java.net.URI keeps IPv6 literals in brackets (e.g. "[::1]").
    val host = rawHost.trim('[', ']')
    if (host.isBlank()) return code
    if (host.equals("localhost", ignoreCase = true)) return code

    // Range-check only IP literals; never resolve hostnames here.
    if (isIpLiteral(host)) {
        val addr = try {
            InetAddress.getByName(host)
        } catch (e: Exception) {
            return code
        }
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress ||
            addr.isAnyLocalAddress || addr.isMulticastAddress
        ) {
            return code
        }
    }
    return null
}

/** True when [host] is a numeric IPv4 or IPv6 literal (so it is safe to parse without DNS). */
private fun isIpLiteral(host: String): Boolean {
    if (host.contains(':')) return true // IPv6 literal
    // IPv4 dotted-quad: all label characters are digits or dots.
    return host.isNotEmpty() && host.all { it.isDigit() || it == '.' } && host.contains('.')
}

/** Response body for POST /internal/agents/register and /renew. */
@Serializable
data class AgentRegisterResponse(
    val certificatePem: String,
    val caRootPem: String,
    val slug: String,
)

/**
 * Request body for POST /internal/agents/renew.
 *
 * An already-registered agent rotates its certificate before expiry. The
 * [signature] is the agent's signature over the raw [csrPem] bytes made with
 * its CURRENT private key (base64-encoded, SHA256withRSA) — this proves the
 * caller possesses the key bound to the agent's stored public key.
 */
@Serializable
data class AgentRenewRequest(
    val slug: String,
    val csrPem: String,
    val signature: String,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("slug", slug)?.let(::add)
        Validators.maxLen("slug", slug, 64)?.let(::add)
        Validators.notBlank("csrPem", csrPem)?.let(::add)
        Validators.maxLen("csrPem", csrPem, 8192)?.let(::add)
        Validators.notBlank("signature", signature)?.let(::add)
        Validators.maxLen("signature", signature, 1024)?.let(::add)
    }
}

/**
 * An agent as the key-authenticated API lists it: the slug a service's agent
 * list names it by, its label, and how it is doing — the same verdict the
 * dashboard's health roster shows. No address, no credentials.
 */
@Serializable
data class PublicAgentSummary(
    val slug: String,
    val label: String,
    @JsonSchema.Enum("healthy", "degraded", "down", "unknown")
    @JsonSchema.Description("`healthy`, `degraded` (answering, but slower than its own usual), `down` (failing its health checks), or `unknown`.")
    val status: String = "unknown",
    @JsonSchema.Description("When the agent's health was last checked; null until it has been (`status` is then `unknown`).")
    val lastCheckAt: String? = null,
)
