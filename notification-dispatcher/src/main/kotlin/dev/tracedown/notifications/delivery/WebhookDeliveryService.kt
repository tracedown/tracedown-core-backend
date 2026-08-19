package dev.tracedown.notifications.delivery

import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.ResourceWebhookAccess
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.WebhookVariables
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.notifications.templates.TemplateRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Dispatches webhook notifications to configured endpoints.
 *
 * Finds webhooks bound to the resource hierarchy (service → project → workspace),
 * renders the body template with the variable context, and dispatches HTTP requests.
 * Logs each delivery to notification_log.
 *
 * Delivery is resilient: each webhook is attempted up to its own configured
 * `attempt_count` times with exponential backoff, retrying only on transient
 * failures (network error / timeout / HTTP 5xx). A 4xx response is a client
 * error and is never retried.
 *
 * The per-webhook `config` JSONB augments the request. Its shape is:
 * ```
 * { "headers": { "Authorization": "Bearer $o.botToken", ... },
 *   "query":   { "apiKey": "$o.key", ... } }
 * ```
 * `headers` are added to the outgoing request (typically auth), `query` entries are
 * appended to the URL's query string. Both keys are optional and both header/query
 * values may embed `$o.<key>` org-variable and `$h.<key>` webhook-variable
 * references, resolved the same way as the URL so secrets stay encrypted in
 * variables rather than plaintext in config. `$h.` variables belong to the one
 * webhook that carries them and are resolved only here — probe scripts can
 * never reference them.
 */
class WebhookDeliveryService(
    /** Base backoff in seconds; the wait before retry N is base × 4^(N-1). */
    private val retryBaseSeconds: Long = 2L,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        // Never auto-follow redirects: a public endpoint could 3xx to an
        // internal address, defeating the SSRF check that ran on the original
        // host. Each concrete URL is validated explicitly before it is called.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /**
     * Finds and dispatches webhooks for the given resource hierarchy.
     *
     * @param orgId the organization ID
     * @param workspaceId the workspace containing the service
     * @param projectId the project containing the service
     * @param serviceId the service that triggered the notification
     * @param probeResultId the probe result ID for logging
     * @param vars the variable context (includes ${text} with baked plaintext)
     */
    suspend fun deliver(
        orgId: UUID,
        workspaceId: UUID,
        projectId: UUID,
        serviceId: UUID,
        probeResultId: UUID,
        vars: Map<String, String>,
    ) {
        val webhooks = newSuspendedTransaction(Dispatchers.IO) {
            findBoundWebhooks(orgId, workspaceId, projectId, serviceId)
        }

        // Webhook URLs (and config header/query values) may embed org-variable
        // ($o.key) and webhook-variable ($h.key) references so secrets like a
        // bot token live encrypted in a variable, not plaintext. Resolve only
        // the referenced keys once per delivery; the decrypted values are used
        // only for the request, never logged. A variable that can't be
        // decrypted (e.g. key mismatch) is skipped — the ref stays unresolved
        // and only that webhook fails, rather than taking down every
        // notification for this result.
        val referencedKeys = webhooks
            .flatMap { webhook -> webhook.referencedKeys(ORG_VAR_RE) }
            .toSet()
        val orgVars = if (referencedKeys.isNotEmpty()) {
            newSuspendedTransaction(Dispatchers.IO) { loadOrgVars(orgId, referencedKeys) }
        } else {
            emptyMap()
        }

        for (webhook in webhooks) {
            // $h. variables are scoped to the single webhook, so their map is
            // loaded per webhook (AAD-bound to webhook:<id> — a ciphertext
            // copied to another webhook's row will not decrypt).
            val hookKeys = webhook.referencedKeys(WEBHOOK_VAR_RE)
            val hookVars = if (hookKeys.isNotEmpty()) {
                newSuspendedTransaction(Dispatchers.IO) { loadWebhookVars(orgId, webhook.webhookId, hookKeys) }
            } else {
                emptyMap()
            }

            // JSON-escaped substitution — raw text with quotes/newlines must
            // not corrupt the body document.
            val renderedBody = TemplateRenderer.renderJson(webhook.bodyTemplate, vars)
            val resolvedUrl = resolveUrl(webhook.url, orgVars, hookVars)

            val outcome = dispatchWithRetry(webhook, resolvedUrl, renderedBody, orgVars, hookVars, probeResultId)

            // `attempt_count` is the operator-configured retry ceiling, not a
            // counter — the per-delivery outcome (including attempts consumed) is
            // recorded in notification_log, so we never write back over the config.
            newSuspendedTransaction(Dispatchers.IO) {
                NotificationLog.insert {
                    it[id] = UUID.randomUUID()
                    it[NotificationLog.organizationId] = orgId
                    it[NotificationLog.serviceId] = serviceId
                    it[NotificationLog.probeResultId] = probeResultId
                    it[channel] = "webhook"
                    it[recipient] = webhook.url
                    it[NotificationLog.status] = outcome.status
                    it[NotificationLog.error] = outcome.error
                    it[createdAt] = Instant.now()
                }
            }
        }
    }

    private data class DeliveryOutcome(val status: String, val error: String?, val attempts: Int)

    /**
     * Attempts a single webhook, retrying transient failures (network / timeout /
     * 5xx) up to the webhook's own attempt ceiling with exponential backoff.
     * Never retries 4xx.
     */
    private suspend fun dispatchWithRetry(
        webhook: BoundWebhook,
        resolvedUrl: String,
        renderedBody: String,
        orgVars: Map<String, String>,
        hookVars: Map<String, String>,
        probeResultId: UUID,
    ): DeliveryOutcome {
        val maxAttempts = webhook.maxAttempts
        var lastError: String? = null
        var attempt = 0

        while (attempt < maxAttempts) {
            if (attempt > 0) {
                // Backoff before retry: base × 4^(retryIndex). base=2 → 2s, 8s, 32s…
                val waitSeconds = retryBaseSeconds * pow4(attempt - 1)
                delay(waitSeconds * 1000L)
            }
            attempt++

            val request = buildRequest(webhook, resolvedUrl, renderedBody, orgVars, hookVars)

            // Authoritative SSRF gate on the fully-resolved URL: https only, and
            // no host that resolves to a private/loopback/internal address. A
            // block is a permanent misconfiguration — never retried.
            try {
                dev.tracedown.common.net.SsrfGuard.assertAllowed(request.url.toString())
            } catch (e: dev.tracedown.common.net.SsrfGuard.BlockedException) {
                lastError = "blocked: ${e.reason}"
                log.warn("webhook blocked (SSRF) for webhook {} result {}: {}", webhook.webhookId, probeResultId, e.reason)
                return DeliveryOutcome("failed", lastError, attempt)
            }

            try {
                val (successful, code) = withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { response ->
                        // Response body is never read into logs — it can contain
                        // reflected secrets or PII.
                        response.isSuccessful to response.code
                    }
                }
                if (successful) {
                    log.debug("webhook dispatched to {} for result {} (attempt {})", webhook.url, probeResultId, attempt)
                    return DeliveryOutcome("sent", null, attempt)
                }
                lastError = "HTTP $code"
                if (code in 300..499) {
                    // Client error / un-followed redirect — retrying will not help.
                    log.warn("webhook failed to {}: HTTP {} (no retry)", webhook.url, code)
                    return DeliveryOutcome("failed", lastError, attempt)
                }
                log.warn("webhook failed to {}: HTTP {} (attempt {}/{})", webhook.url, code, attempt, maxAttempts)
            } catch (e: IOException) {
                // Network error / timeout — transient, eligible for retry.
                lastError = e.message ?: e.javaClass.simpleName
                log.warn("webhook dispatch failed to {} (attempt {}/{}): {}", webhook.url, attempt, maxAttempts, lastError)
            } catch (e: Exception) {
                // Non-transient (e.g. malformed URL) — do not retry.
                lastError = e.message ?: e.javaClass.simpleName
                log.warn("webhook dispatch failed to {} (no retry): {}", webhook.url, lastError)
                return DeliveryOutcome("failed", lastError, attempt)
            }
        }
        return DeliveryOutcome("failed", lastError, attempt)
    }

    /** Builds the OkHttp request, applying config headers/query and body rules. */
    private fun buildRequest(
        webhook: BoundWebhook,
        resolvedUrl: String,
        renderedBody: String,
        orgVars: Map<String, String>,
        hookVars: Map<String, String>,
    ): Request {
        // Append config query params (variable references resolved).
        val urlBuilder = resolvedUrl.toHttpUrlOrNull()?.newBuilder()
        val finalUrl = if (urlBuilder != null && webhook.config.query.isNotEmpty()) {
            webhook.config.query.forEach { (k, v) -> urlBuilder.addQueryParameter(k, resolveUrl(v, orgVars, hookVars)) }
            urlBuilder.build().toString()
        } else {
            resolvedUrl
        }

        // GET/HEAD must not carry a request body; all other verbs send JSON.
        val bodilessMethod = webhook.method.uppercase() in BODILESS_METHODS
        val requestBody: RequestBody? =
            if (bodilessMethod) null else renderedBody.toRequestBody("application/json".toMediaType())

        val builder = Request.Builder()
            .url(finalUrl)
            .method(webhook.method, requestBody)

        webhook.config.headers.forEach { (k, v) -> builder.header(k, resolveUrl(v, orgVars, hookVars)) }

        return builder.build()
    }

    /** Small shape read from the webhook `config` JSONB. */
    private data class WebhookConfig(
        val headers: Map<String, String>,
        val query: Map<String, String>,
    ) {
        companion object {
            val EMPTY = WebhookConfig(emptyMap(), emptyMap())

            fun from(element: JsonElement?): WebhookConfig {
                val obj = element as? JsonObject ?: return EMPTY
                return WebhookConfig(
                    headers = stringMap(obj["headers"]),
                    query = stringMap(obj["query"]),
                )
            }

            private fun stringMap(element: JsonElement?): Map<String, String> {
                val obj = element as? JsonObject ?: return emptyMap()
                return obj.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.let { k to it.content }
                }.toMap()
            }
        }
    }

    private data class BoundWebhook(
        val webhookId: UUID,
        val url: String,
        val method: String,
        val bodyTemplate: String,
        val config: WebhookConfig,
        /** Max total HTTP attempts for this webhook, from its `attempt_count`. */
        val maxAttempts: Int,
    ) {
        /** Variable keys the URL and config header/query values reference via [re]. */
        fun referencedKeys(re: Regex): Set<String> =
            (re.findAll(url) +
                config.headers.values.flatMap { re.findAll(it) } +
                config.query.values.flatMap { re.findAll(it) })
                .map { it.groupValues[1] }
                .toSet()
    }

    private fun findBoundWebhooks(
        orgId: UUID,
        workspaceId: UUID,
        projectId: UUID,
        serviceId: UUID,
    ): List<BoundWebhook> {
        return ResourceWebhookAccess
            .innerJoin(WebhookDeliveries)
            .selectAll()
            .where {
                (ResourceWebhookAccess.orgId eq orgId) and
                    (ResourceWebhookAccess.enabled eq true) and
                    (WebhookDeliveries.deleted eq false) and
                    (
                        ((ResourceWebhookAccess.resourceType eq "service") and (ResourceWebhookAccess.resourceId eq serviceId))
                            or ((ResourceWebhookAccess.resourceType eq "project") and (ResourceWebhookAccess.resourceId eq projectId))
                            or ((ResourceWebhookAccess.resourceType eq "workspace") and (ResourceWebhookAccess.resourceId eq workspaceId))
                        )
            }
            .mapNotNull { row ->
                val body = row[WebhookDeliveries.body]?.toString() ?: return@mapNotNull null
                BoundWebhook(
                    webhookId = row[WebhookDeliveries.id],
                    url = row[WebhookDeliveries.url],
                    method = row[WebhookDeliveries.method],
                    bodyTemplate = body,
                    config = WebhookConfig.from(row[WebhookDeliveries.config]),
                    maxAttempts = row[WebhookDeliveries.attemptCount].toInt().coerceAtLeast(1),
                )
            }
    }

    /**
     * Substitutes `$o.<key>` (org variable) and `$h.<key>` (webhook variable)
     * references in a string with their values. Unknown references are left
     * intact (the request then fails as a misconfiguration rather than
     * silently dropping the token).
     */
    internal fun resolveUrl(
        template: String,
        orgVars: Map<String, String>,
        hookVars: Map<String, String> = emptyMap(),
    ): String {
        var resolved = template
        if (resolved.contains("\$o.")) {
            resolved = ORG_VAR_RE.replace(resolved) { m -> orgVars[m.groupValues[1]] ?: m.value }
        }
        if (resolved.contains("\$h.")) {
            resolved = WEBHOOK_VAR_RE.replace(resolved) { m -> hookVars[m.groupValues[1]] ?: m.value }
        }
        return resolved
    }

    /**
     * Loads the requested org variables into a key → decrypted-value map. A
     * variable that fails to decrypt is skipped (logged) rather than throwing,
     * so a single bad/undecryptable var can't fail the whole delivery.
     */
    private fun loadOrgVars(orgId: UUID, keys: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        OrgVariables.selectAll()
            .where {
                (OrgVariables.organizationId eq orgId) and
                    (OrgVariables.deleted eq false) and
                    (OrgVariables.key inList keys)
            }
            .forEach { row ->
                val key = row[OrgVariables.key]
                val value = row[OrgVariables.value]
                val iv = row[OrgVariables.valueIv]
                val encrypted = row[OrgVariables.encrypted]
                try {
                    result[key] = if (encrypted) VariableCrypto.decrypt(orgId, value, iv, "org", key) else value
                } catch (e: Exception) {
                    log.warn(
                        "could not decrypt org variable '{}' for org {} — leaving its webhook refs unresolved (check PLATFORM_AES_KEY matches the gateway): {}",
                        key, orgId, e.message,
                    )
                }
            }
        return result
    }

    /**
     * Loads the requested webhook variables into a key → decrypted-value map.
     * Same failure posture as [loadOrgVars]: an undecryptable variable is
     * logged and skipped, leaving its refs unresolved.
     */
    private fun loadWebhookVars(orgId: UUID, webhookId: UUID, keys: Set<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        WebhookVariables.selectAll()
            .where {
                (WebhookVariables.webhookId eq webhookId) and
                    (WebhookVariables.organizationId eq orgId) and
                    (WebhookVariables.deleted eq false) and
                    (WebhookVariables.key inList keys)
            }
            .forEach { row ->
                val key = row[WebhookVariables.key]
                val value = row[WebhookVariables.value]
                val iv = row[WebhookVariables.valueIv]
                val encrypted = row[WebhookVariables.encrypted]
                try {
                    // AAD scope must match the gateway's write side exactly.
                    result[key] = if (encrypted) VariableCrypto.decrypt(orgId, value, iv, "webhook:$webhookId", key) else value
                } catch (e: Exception) {
                    log.warn(
                        "could not decrypt webhook variable '{}' for webhook {} — leaving its refs unresolved (check PLATFORM_AES_KEY matches the gateway): {}",
                        key, webhookId, e.message,
                    )
                }
            }
        return result
    }

    private companion object {
        /** Org-scoped variable reference: `$o.key`. */
        val ORG_VAR_RE = Regex("\\\$o\\.([a-zA-Z_][a-zA-Z0-9_]*)")

        /** Webhook-scoped variable reference: `$h.key`. */
        val WEBHOOK_VAR_RE = Regex("\\\$h\\.([a-zA-Z_][a-zA-Z0-9_]*)")

        /** HTTP methods that must never carry a request body. */
        val BODILESS_METHODS = setOf("GET", "HEAD")

        /** 4^exp for small non-negative exponents (backoff growth factor). */
        fun pow4(exp: Int): Long {
            var v = 1L
            repeat(exp) { v *= 4 }
            return v
        }
    }
}
