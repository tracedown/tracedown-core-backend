package dev.tracedown.notifications.delivery

import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.ResourceWebhookAccess
import dev.tracedown.common.models.WebhookDeliveries
import dev.tracedown.common.models.WebhookVariables
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.notifications.templates.TemplateRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Dns
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
import java.net.InetAddress
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
 * ## Delivery is asynchronous, and deliberately so
 *
 * [deliver] does the database work — resolve the bound webhooks, decrypt the
 * variables they reference, render the body — and then **hands each webhook to a
 * worker pool and returns**. It never sleeps.
 *
 * That split is the whole point. Retry backoff used to run inline: a serial loop
 * over a run's webhooks, inside the outbox consumer's serial loop over events,
 * inside the poll mutex. One unreachable endpoint on the default ladder is ~42
 * seconds of pure sleep per event (2s + 8s + 32s), so a batch of fifty events
 * bound to it held the single poll for well over half an hour — and during that
 * time no other organization's failure mail moved either, because there is only
 * one pipeline. The sleeps now happen on [workerCount] dedicated workers, and a
 * dead endpoint occupies at most one of them per event instead of the pipeline.
 *
 * Two further bounds keep a dead endpoint from eating the pool as well:
 *
 * - A [WebhookCircuitBreaker] fast-fails a webhook that has failed repeatedly,
 *   so from the fourth consecutive failure onward its events cost no sleep at
 *   all until the circuit's window expires and one probe is let through.
 * - The queue is bounded ([queueCapacity]). If it ever fills, the delivery is
 *   recorded as failed rather than blocking the caller — shedding load is the
 *   correct answer for an alerting pipeline whose value is timeliness.
 *
 * The module runs single-instance (see `OutboxConsumer`), so the pool is
 * in-process by design: there is no second replica for a shared queue to
 * balance across, and adding one would be a horizontal-scaling change the rest
 * of the module does not support.
 *
 * The cost of queueing is that a shutdown mid-flight abandons whatever is still
 * queued, and the outbox event that produced it has already been marked
 * published. Those deliveries are not lost silently: their `notification_log`
 * rows stay at `queued`, which is exactly the thing to look for after an
 * unclean restart.
 *
 * ## Idempotency
 *
 * A `notification_log` row is written as `queued` **before** the job is enqueued
 * and updated to `sent`/`failed` by the worker, mirroring the email path. That
 * row is also the idempotency record: if the same outbox event is processed
 * again — its other channel threw, so the event was never marked published —
 * the webhooks already logged for that probe result are skipped instead of being
 * re-delivered.
 *
 * ## Retry and SSRF
 *
 * Each webhook is attempted up to its own configured `attempt_count` times with
 * exponential backoff, retrying only on transient failures (network error /
 * timeout / HTTP 5xx). A 4xx response is a client error and is never retried.
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
    /** Workers draining the delivery queue. Caps concurrent outbound webhooks. */
    private val workerCount: Int = 4,
    /** Bound on queued deliveries. Overflow is shed, never blocked on. */
    private val queueCapacity: Int = 1000,
    /** Fast-fail for endpoints that have failed repeatedly. */
    private val breaker: WebhookCircuitBreaker = WebhookCircuitBreaker(),
) {

    private val log = LoggerFactory.getLogger(javaClass)

    private val queue = Channel<WebhookJob>(capacity = queueCapacity)
    private var workers: List<Job> = emptyList()

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

    /** Starts the delivery workers. Must be called before the outbox consumer. */
    fun start(scope: CoroutineScope) {
        if (workers.isNotEmpty()) return
        workers = (1..workerCount).map { n ->
            scope.launch {
                log.debug("webhook delivery worker {} started", n)
                for (job in queue) {
                    try {
                        runJob(job)
                    } catch (e: Exception) {
                        // A worker must never die: it is the only thing draining
                        // the queue, and a dead worker would silently strand
                        // every delivery routed to it.
                        log.error("webhook delivery worker error for webhook {}: {}", job.webhookId, e.message, e)
                    }
                }
            }
        }
        log.info("webhook delivery pool started ({} workers, queue {})", workerCount, queueCapacity)
    }

    /** Stops the delivery workers. */
    fun stop() {
        queue.close()
        workers.forEach { it.cancel() }
        workers = emptyList()
    }

    /**
     * Finds webhooks for the given resource hierarchy and queues one delivery
     * for each. Returns as soon as the jobs are queued — see the class comment.
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
        val (webhooks, alreadyLogged) = newSuspendedTransaction(Dispatchers.IO) {
            findBoundWebhooks(orgId, workspaceId, projectId, serviceId) to
                loggedRecipients(probeResultId, CHANNEL)
        }

        // Re-processing guard: this event may have been through here already and
        // failed on the other channel. Anything with a notification_log row for
        // this probe result was queued once and is not queued again. Counted
        // rather than set-tested, so two bindings that happen to share a URL
        // template are each matched exactly once.
        val pending = webhooks.filter { webhook ->
            val seen = alreadyLogged[logRecipient(webhook.url)] ?: 0
            if (seen > 0) {
                alreadyLogged[logRecipient(webhook.url)] = seen - 1
                log.debug("webhook {} already logged for result {} — not re-delivering", webhook.webhookId, probeResultId)
                false
            } else {
                true
            }
        }
        if (pending.isEmpty()) return

        // Webhook URLs (and config header/query values) may embed org-variable
        // ($o.key) and webhook-variable ($h.key) references so secrets like a
        // bot token live encrypted in a variable, not plaintext. Resolve only
        // the referenced keys once per delivery; the decrypted values are used
        // only for the request, never logged. A variable that can't be
        // decrypted (e.g. key mismatch) is skipped — the ref stays unresolved
        // and only that webhook fails, rather than taking down every
        // notification for this result.
        val referencedKeys = pending
            .flatMap { webhook -> webhook.referencedKeys(ORG_VAR_RE) }
            .toSet()
        val orgVars = if (referencedKeys.isNotEmpty()) {
            newSuspendedTransaction(Dispatchers.IO) { loadOrgVars(orgId, referencedKeys) }
        } else {
            emptyMap()
        }

        for (webhook in pending) {
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

            // Log first, then queue: the row is both the audit record and the
            // re-processing guard above, so it must exist before the job can be
            // picked up (and before this method can be re-entered for the same
            // probe result).
            val logId = UUID.randomUUID()
            newSuspendedTransaction(Dispatchers.IO) {
                NotificationLog.insert {
                    it[id] = logId
                    it[NotificationLog.organizationId] = orgId
                    it[NotificationLog.serviceId] = serviceId
                    it[NotificationLog.probeResultId] = probeResultId
                    it[channel] = CHANNEL
                    it[recipient] = logRecipient(webhook.url)
                    it[NotificationLog.status] = "queued"
                    it[createdAt] = Instant.now()
                }
            }

            val job = WebhookJob(
                logId = logId,
                webhook = webhook,
                webhookId = webhook.webhookId,
                resolvedUrl = resolvedUrl,
                renderedBody = renderedBody,
                orgVars = orgVars,
                hookVars = hookVars,
                probeResultId = probeResultId,
            )

            if (queue.trySend(job).isFailure) {
                // Bounded queue full (or the pool is stopped). Shed rather than
                // block the outbox pipeline behind an endpoint's backlog.
                log.error(
                    "webhook delivery queue full — dropping delivery for webhook {} result {}",
                    webhook.webhookId, probeResultId,
                )
                recordOutcome(logId, DeliveryOutcome("failed", "delivery queue full", 0, retriable = false))
            }
        }
    }

    /** One queued delivery. Everything it needs is already resolved. */
    private data class WebhookJob(
        val logId: UUID,
        val webhook: BoundWebhook,
        val webhookId: UUID,
        val resolvedUrl: String,
        val renderedBody: String,
        val orgVars: Map<String, String>,
        val hookVars: Map<String, String>,
        val probeResultId: UUID,
    )

    /** Runs one queued delivery on a worker: breaker, HTTP with retries, log. */
    private suspend fun runJob(job: WebhookJob) {
        if (breaker.isOpen(job.webhookId)) {
            log.warn(
                "webhook {} circuit open ({} consecutive failures) — not dialled for result {}",
                job.webhookId, breaker.failureCount(job.webhookId), job.probeResultId,
            )
            recordOutcome(
                job.logId,
                DeliveryOutcome("failed", "circuit open: endpoint failing repeatedly, no attempt made", 0, retriable = false),
            )
            return
        }

        val outcome = dispatchWithRetry(
            job.webhook, job.resolvedUrl, job.renderedBody, job.orgVars, job.hookVars, job.probeResultId,
        )

        when {
            outcome.status == "sent" -> breaker.recordSuccess(job.webhookId)
            // Only failures that actually consumed backoff arm the breaker. A
            // 4xx or an SSRF block is instant and costs the pool nothing, so
            // fast-failing it would only make the log less informative.
            outcome.retriable -> breaker.recordFailure(job.webhookId)
        }

        recordOutcome(job.logId, outcome)
    }

    /** Transitions the `queued` log row to its final state. */
    private suspend fun recordOutcome(logId: UUID, outcome: DeliveryOutcome) {
        newSuspendedTransaction(Dispatchers.IO) {
            NotificationLog.update({ NotificationLog.id eq logId }) {
                it[NotificationLog.status] = outcome.status
                it[NotificationLog.error] = outcome.error
                // Unlike the webhook's own `attempt_count` (an operator-set
                // ceiling that is never written back), this column records what
                // this one delivery actually consumed.
                it[NotificationLog.attemptCount] =
                    outcome.attempts.coerceIn(0, Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }

    /**
     * An OkHttp [Dns] that answers the one vetted host with the exact addresses
     * [SsrfGuard.assertAllowed] resolved and approved, and defers everything else
     * to the system resolver. Scoped to a single call so it pins that call's
     * connection to a checked address without touching any other lookup.
     */
    private class PinnedDns(
        host: String,
        private val vetted: List<InetAddress>,
    ) : Dns {
        private val pinnedHost = host.lowercase()

        override fun lookup(hostname: String): List<InetAddress> =
            if (hostname.lowercase() == pinnedHost && vetted.isNotEmpty()) vetted
            else Dns.SYSTEM.lookup(hostname)
    }

    private data class DeliveryOutcome(
        val status: String,
        val error: String?,
        val attempts: Int,
        /** True when the failure was of the kind that consumes retry backoff. */
        val retriable: Boolean = false,
    )

    /**
     * Attempts a single webhook, retrying transient failures (network / timeout /
     * 5xx) up to the webhook's own attempt ceiling with exponential backoff.
     * Never retries 4xx. Runs on a delivery worker — this is the only place that
     * sleeps, and it is off the outbox pipeline.
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
            val vetted = try {
                dev.tracedown.common.net.SsrfGuard.assertAllowed(request.url.toString())
            } catch (e: dev.tracedown.common.net.SsrfGuard.BlockedException) {
                lastError = "blocked: ${e.reason}"
                log.warn("webhook blocked (SSRF) for webhook {} result {}: {}", webhook.webhookId, probeResultId, e.reason)
                return DeliveryOutcome("failed", lastError, attempt)
            }

            // Pin the addresses the guard just vetted for this one call, so the
            // connection goes to what was checked. Without it OkHttp re-resolves
            // the host itself, and a name that answered with a public address for
            // the guard's lookup can answer with 127.0.0.1 (or a metadata IP) for
            // the connect a moment later — the classic DNS-rebind window. TLS
            // still validates the certificate against the hostname, so pinning
            // the address changes only which IP is dialled, not who is trusted.
            val pinnedClient = httpClient.newBuilder()
                .dns(PinnedDns(request.url.host, vetted))
                .build()

            try {
                val (successful, code) = withContext(Dispatchers.IO) {
                    pinnedClient.newCall(request).execute().use { response ->
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
        // Exhausted the ladder on transient failures — this is the outcome the
        // circuit breaker exists for.
        return DeliveryOutcome("failed", lastError, attempt, retriable = true)
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
     * Recipients already logged on [channel] for this probe result, with how
     * many rows each has. The multiset is the re-processing guard in [deliver].
     */
    private fun loggedRecipients(probeResultId: UUID, channel: String): MutableMap<String, Int> {
        val counts = mutableMapOf<String, Int>()
        NotificationLog
            .select(NotificationLog.recipient)
            .where {
                (NotificationLog.probeResultId eq probeResultId) and
                    (NotificationLog.channel eq channel)
            }
            .forEach { row -> counts.merge(row[NotificationLog.recipient], 1, Int::plus) }
        return counts
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

    internal companion object {
        /** notification_log.channel value for this service's rows. */
        const val CHANNEL = "webhook"

        /** Org-scoped variable reference: `$o.key`. */
        val ORG_VAR_RE = Regex("\\\$o\\.([a-zA-Z_][a-zA-Z0-9_]*)")

        /** Webhook-scoped variable reference: `$h.key`. */
        val WEBHOOK_VAR_RE = Regex("\\\$h\\.([a-zA-Z_][a-zA-Z0-9_]*)")

        /** HTTP methods that must never carry a request body. */
        val BODILESS_METHODS = setOf("GET", "HEAD")

        /**
         * `notification_log.recipient` is `VARCHAR(255)`. A longer webhook URL
         * would fail the insert and, with it, the whole event. Truncate on both
         * the write and the idempotency read so the two always agree.
         */
        fun logRecipient(url: String): String = if (url.length <= 255) url else url.take(255)

        /** 4^exp for small non-negative exponents (backoff growth factor). */
        fun pow4(exp: Int): Long {
            var v = 1L
            repeat(exp) { v *= 4 }
            return v
        }
    }
}
