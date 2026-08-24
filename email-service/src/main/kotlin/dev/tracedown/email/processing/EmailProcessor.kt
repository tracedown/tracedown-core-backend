package dev.tracedown.email.processing

import dev.tracedown.common.email.EmailAttachment
import dev.tracedown.common.email.EmailMessage
import dev.tracedown.common.email.EmailStatusEvent
import dev.tracedown.common.email.EmailTransport
import dev.tracedown.common.email.SuppressionList
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Processes email jobs from the queue.
 *
 * Resolution logic:
 * 1. If `type` is present → load named template, render with vars, use as full email HTML
 * 2. If `type` is absent → insert `body` into layout.html wrapper
 */
open class EmailProcessor(
    private val emailTransport: EmailTransport,
    private val redis: RedisCommands<String, String>?,
    /**
     * Whether the suppression list is consultable — false when the service runs
     * without a database, as the tests and the file/console transports do.
     */
    private val suppressionEnabled: Boolean = true,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val deliveryLog = LoggerFactory.getLogger("email.delivery")
    private val templateCache = ConcurrentHashMap<String, String>()
    private val layoutHtml: String by lazy { loadResource("/email-templates/layout.html") }

    /**
     * Processes a single email job envelope.
     *
     * @param envelope the deserialized JSON job from the queue
     */
    fun process(envelope: JsonObject) {
        val id = envelope["id"]?.jsonPrimitive?.content ?: return
        val to = envelope["to"]?.jsonPrimitive?.content ?: return
        val subject = envelope["subject"]?.jsonPrimitive?.content ?: return
        val replyTo = envelope["replyTo"]?.jsonPrimitive?.contentOrNull
        val source = envelope["source"]?.jsonPrimitive?.content ?: "unknown"

        // Idempotency check
        if (!checkIdempotency(id)) {
            log.debug("duplicate email job {}, skipping", id)
            return
        }

        val type = envelope["type"]?.jsonPrimitive?.contentOrNull
        val htmlBody: String

        if (type != null) {
            // Named template mode: load template and render with vars
            val templateHtml = loadTemplate(type)
            if (templateHtml == null) {
                log.error("template not found for type={}, skipping email {}", type, id)
                logDelivery(id, to, subject, source, "failed", "template not found: $type")
                publishStatus(envelope, "failed", "template not found: $type")
                return
            }
            val vars = envelope["vars"]?.jsonObject ?: JsonObject(emptyMap())
            htmlBody = renderTemplate(templateHtml, vars)
        } else {
            // Body mode: wrap pre-baked content in layout
            val body = envelope["body"]?.jsonPrimitive?.content
            if (body == null) {
                log.error("no type or body in email job {}, skipping", id)
                logDelivery(id, to, subject, source, "failed", "no type or body")
                publishStatus(envelope, "failed", "no type or body")
                return
            }
            htmlBody = applyLayout(body, envelope["footer"]?.jsonPrimitive?.contentOrNull)
        }

        // Withheld before the transport is touched: every producer's mail funnels
        // through here, so this is the one place that can guarantee a bounced
        // address is never written to again. Sending anyway is what providers
        // score as sender abuse, and the damage lands on every other recipient.
        if (isSuppressed(to)) {
            log.info("withholding email {} — {} is suppressed", id, to)
            logDelivery(id, to, subject, source, "suppressed", null)
            publishStatus(envelope, "suppressed", "recipient suppressed")
            return
        }

        // Send
        try {
            emailTransport.send(
                EmailMessage(
                    to = to,
                    subject = subject,
                    htmlBody = htmlBody,
                    replyTo = replyTo,
                    attachments = decodeAttachments(envelope, id),
                )
            )
            logDelivery(id, to, subject, source, "sent", null)
            publishStatus(envelope, "sent", null)
        } catch (e: Exception) {
            log.error("failed to send email {}: {}", id, e.message)
            logDelivery(id, to, subject, source, "failed", e.message)
            publishStatus(envelope, "failed", e.message)
        }
    }

    /**
     * Wraps a pre-baked body in the shared layout.
     *
     * The footer explaining *why* the message was received belongs only to mail
     * the recipient did not ask for — alerts. Mail sent in direct response to
     * something the recipient just did needs no such explanation, so a job that
     * supplies no footer has the whole row removed rather than being left with
     * an empty bordered strip.
     */
    private fun applyLayout(body: String, footer: String?): String {
        val withContent = layoutHtml.replace("{{content}}", body)
        return if (footer.isNullOrBlank()) {
            withContent.replace(FOOTER_BLOCK, "")
        } else {
            withContent.replace("{{footer}}", footer)
        }
    }

    /**
     * Decodes the base64 attachments carried on the envelope.
     *
     * A malformed entry is dropped rather than failing the job: the message body
     * is the part that matters, and losing an attachment beats not delivering
     * the email at all.
     */
    private fun decodeAttachments(envelope: JsonObject, id: String): List<EmailAttachment> {
        val array = envelope["attachments"]?.jsonArray ?: return emptyList()
        return array.mapNotNull { element ->
            try {
                val obj = element.jsonObject
                EmailAttachment(
                    filename = obj["filename"]!!.jsonPrimitive.content,
                    contentType = obj["contentType"]?.jsonPrimitive?.contentOrNull
                        ?: "application/octet-stream",
                    content = Base64.getDecoder().decode(obj["content"]!!.jsonPrimitive.content),
                )
            } catch (e: Exception) {
                log.error("dropping malformed attachment on email job {}: {}", id, e.message)
                null
            }
        }
    }

    /**
     * Reports the send outcome back to the producer via the status queue,
     * if the job envelope carries a `notificationLogId`.
     */
    private fun publishStatus(envelope: JsonObject, status: String, error: String?) {
        if (redis == null) return
        val rawId = envelope["notificationLogId"]?.jsonPrimitive?.contentOrNull ?: return
        val logId = try {
            UUID.fromString(rawId)
        } catch (e: IllegalArgumentException) {
            log.error("invalid notificationLogId '{}' in email job, dropping status event", rawId)
            return
        }
        val event = EmailStatusEvent(logId, status, error)
        redis.lpush(EmailStatusEvent.QUEUE_KEY, event.toEnvelope().toString())
    }

    /**
     * Whether mail to [to] must be withheld. A lookup failure is NOT treated as
     * suppression: a database blip must not silently stop every notification.
     */
    open fun isSuppressed(to: String): Boolean {
        if (!suppressionEnabled) return false
        return try {
            transaction { SuppressionList.isSuppressed(to) }
        } catch (e: Exception) {
            log.error("suppression lookup failed for {}, sending anyway: {}", to, e.message)
            false
        }
    }

    /**
     * Checks idempotency for the given message id.
     * Returns true if this message should be processed, false if it's a duplicate.
     */
    open fun checkIdempotency(id: String): Boolean {
        if (redis == null) return true
        val result = redis.set("email:sent:$id", "", SetArgs().nx().ex(86400))
        return result != null
    }

    private fun loadTemplate(type: String): String? {
        return templateCache.getOrPut(type) {
            // type = "system.invite" → path = "/email-templates/system/invite.html"
            val path = "/email-templates/${type.replace('.', '/')}.html"
            val stream = javaClass.getResourceAsStream(path) ?: return null
            stream.bufferedReader().readText()
        }
    }

    private fun renderTemplate(template: String, vars: JsonObject): String {
        var result = template
        for ((key, value) in vars) {
            val strValue = value.jsonPrimitive.content
            result = result.replace("{{$key}}", strValue)
        }
        return result
    }

    private companion object {
        /** The optional footer region of the layout, markers included. */
        val FOOTER_BLOCK = Regex(
            "<!-- FOOTER_START.*?FOOTER_END -->",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
    }

    private fun loadResource(path: String): String {
        val stream = javaClass.getResourceAsStream(path)
            ?: throw IllegalStateException("Resource not found: $path")
        return stream.bufferedReader().readText()
    }

    private fun logDelivery(id: String, to: String, subject: String, source: String, status: String, error: String?) {
        if (error != null) {
            deliveryLog.info("id={} to={} subject=\"{}\" source={} status={} error=\"{}\"", id, to, subject, source, status, error)
        } else {
            deliveryLog.info("id={} to={} subject=\"{}\" source={} status={}", id, to, subject, source, status)
        }
    }
}
