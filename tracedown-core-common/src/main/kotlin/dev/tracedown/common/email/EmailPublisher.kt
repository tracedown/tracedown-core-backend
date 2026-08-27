package dev.tracedown.common.email

import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Publishes email jobs to the Redis queue for the email-service to consume.
 *
 * Two modes:
 * - [publish]: named template + vars (used by gateway for system emails)
 * - [publishBody]: pre-baked HTML body (used by notification-dispatcher), and
 *   the only mode that carries attachments
 *
 * Redis arrives as a provider so a caller holding its connection behind
 * `by lazy` is not forced to connect just to construct this — the gateway
 * builds one during module init, and an eager connect there meant a Redis
 * restart mid-deploy stopped Ktor from ever binding.
 *
 * **Queueing never throws.** A failed enqueue is logged and reported through
 * the return value. An unreachable queue is a mail outage, and turning it into
 * an exception turned it into a 500 on every path that sends mail — password
 * reset requests included, where the caller answers identically either way on
 * purpose. Callers that can act on the failure should check the result.
 */
class EmailPublisher(private val redis: () -> RedisCommands<String, String>) {

    /** Convenience for callers that already hold a live connection. */
    constructor(redis: RedisCommands<String, String>) : this({ redis })

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val QUEUE_KEY = "email_queue"
    }

    /**
     * Publishes a named template email job.
     *
     * The email-service will load the template by type (e.g. "system.invite" → system/invite.html),
     * render it with the provided vars, and insert into the global layout.
     */
    fun publish(
        to: String,
        subject: String,
        type: String,
        vars: Map<String, String>,
        source: String,
        replyTo: String? = null,
    ): Boolean {
        val envelope = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("to", to)
            put("subject", subject)
            put("type", type)
            put("vars", buildJsonObject { vars.forEach { (k, v) -> put(k, v) } })
            put("source", source)
            if (replyTo != null) put("replyTo", replyTo)
            put("createdAt", Instant.now().toString())
        }
        return enqueue(envelope.toString(), type)
    }

    /**
     * Publishes a pre-baked body email job.
     *
     * The email-service will insert the body directly into the global layout
     * without any template rendering.
     *
     * [footer] is the small print explaining why the message was received. It
     * belongs on mail the recipient did not ask for (alerts); transactional mail
     * should leave it null, and the layout then omits the footer entirely.
     *
     * When [notificationLogId] is set, the email-service reports the send
     * outcome back via [EmailStatusEvent.QUEUE_KEY] so the corresponding
     * notification_log row can leave the `queued` status.
     */
    fun publishBody(
        to: String,
        subject: String,
        body: String,
        source: String,
        replyTo: String? = null,
        notificationLogId: UUID? = null,
        attachments: List<EmailAttachment> = emptyList(),
        footer: String? = null,
    ): Boolean {
        val envelope = buildJsonObject {
            put("id", UUID.randomUUID().toString())
            put("to", to)
            put("subject", subject)
            put("body", body)
            put("source", source)
            if (replyTo != null) put("replyTo", replyTo)
            if (notificationLogId != null) put("notificationLogId", notificationLogId.toString())
            if (attachments.isNotEmpty()) put("attachments", encodeAttachments(attachments))
            if (!footer.isNullOrBlank()) put("footer", footer)
            put("createdAt", Instant.now().toString())
        }
        return enqueue(envelope.toString(), "body")
    }

    /**
     * Pushes one envelope, converting a queue outage into a logged failure
     * rather than an exception thrown at whatever request happened to be
     * sending mail. Returns true when the job is on the queue.
     */
    private fun enqueue(envelope: String, kind: String): Boolean = try {
        redis().lpush(QUEUE_KEY, envelope)
        true
    } catch (e: Exception) {
        log.error(
            "email job '{}' was NOT queued — the mail queue is unreachable: {}",
            kind, e.message,
        )
        false
    }

    /**
     * Attachment bytes ride the queue base64-encoded, since the envelope is JSON.
     * That costs about a third in size, so this is for small documents only.
     */
    private fun encodeAttachments(attachments: List<EmailAttachment>): JsonArray =
        buildJsonArray {
            for (attachment in attachments) {
                add(
                    buildJsonObject {
                        put("filename", attachment.filename)
                        put("contentType", attachment.contentType)
                        put("content", Base64.getEncoder().encodeToString(attachment.content))
                    }
                )
            }
        }
}
