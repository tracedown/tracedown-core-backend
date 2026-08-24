package dev.tracedown.gateway.routes.v1.email

import dev.tracedown.common.email.SuppressionList
import dev.tracedown.common.email.WebhookSignatures
import dev.tracedown.common.models.SuppressionReasons
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.util.AppConfig
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.gateway.routes.v1.email.EmailWebhookRoutes")

/**
 * Delivery feedback from the mail provider.
 *
 * These are the only routes here a stranger can reach, and they are deliberately
 * unauthenticated — a provider holds no session. The signature is the entire
 * access control, so every path below fails closed: no configured secret, no
 * signature, or a signature that does not verify, and nothing is written.
 *
 * A verified permanent failure suppresses the address. Everything else is
 * acknowledged and ignored: providers retry on a non-2xx, so answering 400 to a
 * delivery event we simply do not act on would earn an endless redelivery loop.
 */
fun Route.emailWebhookRoutes(appConfig: AppConfig) = v1 {
    route("/email/webhooks") {

        /**
         * Mailgun posts `{signature: {timestamp, token, signature}, event-data: {…}}`.
         * `failed` carries a severity; only `permanent` suppresses — a temporary
         * failure is a full mailbox or a greylist, and clears on its own.
         */
        post("/mailgun") {
            val signingKey = appConfig.email.mailgun?.webhookSigningKey.orEmpty()
            if (signingKey.isBlank()) {
                log.warn("mailgun webhook received but no signing key configured — refusing")
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                return@post
            }

            val payload = parseBody(call.receiveText()) ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                return@post
            }
            val sig = payload["signature"]?.jsonObject
            val verified = sig != null && WebhookSignatures.verifyMailgun(
                signingKey = signingKey,
                timestamp = sig["timestamp"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                token = sig["token"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                signature = sig["signature"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            )
            if (!verified) {
                log.warn("rejected mailgun webhook: signature did not verify")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
                return@post
            }

            val data = payload["event-data"]?.jsonObject
            val event = data?.get("event")?.jsonPrimitive?.contentOrNull
            val recipient = data?.get("recipient")?.jsonPrimitive?.contentOrNull
            val severity = data?.get("severity")?.jsonPrimitive?.contentOrNull
            val detail = data?.get("delivery-status")?.jsonObject
                ?.get("message")?.jsonPrimitive?.contentOrNull

            val reason = when {
                event == "failed" && severity == "permanent" -> SuppressionReasons.BOUNCE
                event == "complained" -> SuppressionReasons.COMPLAINT
                else -> null
            }
            record(reason, recipient, "mailgun", detail, event)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }

        /**
         * Resend posts Svix-signed events. `email.bounced` carries a bounce type;
         * only a hard bounce suppresses, for the same reason as Mailgun's severity.
         */
        post("/resend") {
            val secret = appConfig.email.resend?.webhookSecret.orEmpty()
            if (secret.isBlank()) {
                log.warn("resend webhook received but no signing secret configured — refusing")
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "not_found"))
                return@post
            }

            // The raw text is what was signed — re-serialising the parsed JSON
            // would change byte-for-byte and never verify.
            val raw = call.receiveText()
            val verified = WebhookSignatures.verifyResend(
                secret = secret,
                id = call.request.headers["svix-id"].orEmpty(),
                timestamp = call.request.headers["svix-timestamp"].orEmpty(),
                body = raw,
                signatureHeader = call.request.headers["svix-signature"].orEmpty(),
            )
            if (!verified) {
                log.warn("rejected resend webhook: signature did not verify")
                call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "invalid_signature"))
                return@post
            }

            val payload = parseBody(raw) ?: run {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_body"))
                return@post
            }
            val type = payload["type"]?.jsonPrimitive?.contentOrNull
            val data = payload["data"]?.jsonObject
            // `to` is an array; a transactional send has exactly one recipient.
            val recipient = data?.get("to")?.let { to ->
                runCatching { to.jsonArrayOrNull()?.firstOrNull()?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?: to.jsonPrimitive.contentOrNull
            }
            val bounceType = data?.get("bounce")?.jsonObject?.get("type")?.jsonPrimitive?.contentOrNull
            val detail = data?.get("bounce")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull

            val reason = when {
                type == "email.bounced" && !bounceType.equals("soft", ignoreCase = true) ->
                    SuppressionReasons.BOUNCE
                type == "email.complained" -> SuppressionReasons.COMPLAINT
                else -> null
            }
            record(reason, recipient, "resend", detail, type)
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}

/** Suppresses when the event warrants it; otherwise records why it did not. */
private fun record(reason: String?, recipient: String?, provider: String, detail: String?, event: String?) {
    if (reason == null) {
        log.debug("{} webhook event '{}' needs no action", provider, event)
        return
    }
    if (recipient.isNullOrBlank()) {
        log.warn("{} webhook event '{}' warranted suppression but named no recipient", provider, event)
        return
    }
    SuppressionList.suppress(recipient, reason, provider, detail)
}

private fun parseBody(raw: String): JsonObject? =
    runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull() =
    this as? kotlinx.serialization.json.JsonArray
