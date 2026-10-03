package dev.tracedown.gateway.data.webhooks

import dev.tracedown.common.net.SsrfGuard
import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.Serializable

private val webhookMethods = setOf("GET", "POST", "PUT", "PATCH")

@Serializable
data class CreateWebhookRequest(
    val name: String,
    val url: String,
    val method: String = "POST",
    val label: String? = null,
    val body: String? = null,
    val config: String? = null,
    val attemptCount: Short? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("name", name)?.let(::add)
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.notBlank("url", url)?.let(::add)
        Validators.maxLen("url", url, 512)?.let(::add)
        // Reject non-https and obvious private/internal targets at write time
        // (full DNS-based SSRF check runs again at delivery).
        SsrfGuard.validateUrlSyntax("url", url)?.let(::add)
        Validators.oneOf("method", method, webhookMethods)?.let(::add)
        Validators.maxLen("label", label, 64)?.let(::add)
        Validators.maxLen("body", body, 8192)?.let(::add)
        Validators.maxLen("config", config, 8192)?.let(::add)
        Validators.inRange("attemptCount", attemptCount?.toInt(), 1..10)?.let(::add)
    }
}

@Serializable
data class UpdateWebhookRequest(
    val name: String? = null,
    val url: String? = null,
    val method: String? = null,
    val label: String? = null,
    val body: String? = null,
    val config: String? = null,
    val attemptCount: Short? = null,
) : Validatable {
    override fun validate() = buildList {
        Validators.maxLen("name", name, 64)?.let(::add)
        Validators.maxLen("url", url, 512)?.let(::add)
        SsrfGuard.validateUrlSyntax("url", url)?.let(::add)
        Validators.oneOf("method", method, webhookMethods)?.let(::add)
        Validators.maxLen("label", label, 64)?.let(::add)
        Validators.maxLen("body", body, 8192)?.let(::add)
        Validators.maxLen("config", config, 8192)?.let(::add)
        Validators.inRange("attemptCount", attemptCount?.toInt(), 1..10)?.let(::add)
    }
}

@Serializable
data class WebhookSummary(
    val id: String,
    val name: String,
    val label: String?,
    val url: String,
    val method: String,
    val body: String?,
    val config: String?,
    val attemptCount: Short,
    val createdAt: String,
)

@Serializable
data class WebhookBindingRequest(
    val webhookId: String,
    val enabled: Boolean = true,
) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("webhookId", webhookId)?.let(::add)
        Validators.uuid("webhookId", webhookId)?.let(::add)
    }
}

@Serializable
data class WebhookBindingSummary(
    val id: String,
    val webhookId: String,
    val webhookName: String,
    val enabled: Boolean,
    val createdAt: String,
)

/**
 * A webhook as the key-authenticated API lists it: enough to recognise it and
 * bind it to a resource, and nothing of where it sends or what — the URL, body
 * template and configuration may carry tokens, and are never in it.
 */
@Serializable
data class PublicWebhookSummary(
    val id: String,
    val name: String,
    val label: String?,
    val method: String,
    val createdAt: String,
)
