package dev.tracedown.email.processing

import dev.tracedown.common.email.EmailAttachment
import dev.tracedown.common.email.EmailMessage
import dev.tracedown.common.email.EmailStatusEvent
import dev.tracedown.common.email.EmailTransport
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Processes email jobs from the queue.
 *
 * Resolution logic:
 * 1. If `type` is present → load named template, render with vars, use as full email HTML
 * 2. If `type` is absent → insert `body` into layout.html wrapper
 *
 * ## Where templates come from
 *
 * Templates ship on the classpath under `/email-templates/`. A host that needs
 * mail this service does not ship — or needs different wording in mail it does
 * — can point [templateDir] at a directory laid out the same way. That
 * directory is consulted **first** and the classpath is the fallback, so the
 * same mechanism both adds new template types and overrides shipped ones: drop
 * `system/invite.html` in there and the invite mail is yours; drop
 * `reports/weekly.html` in there and `reports.weekly` becomes a type this
 * service can render, with nothing in this module knowing what it is.
 *
 * Nothing about the directory is trusted. A `type` is turned into a relative
 * path and the result must still resolve inside the root, an unreadable or
 * missing file falls back to the classpath, and any failure at all falls back
 * rather than throwing — one bad override must not stop every other mail on
 * the platform from going out.
 */
open class EmailProcessor(
    private val emailTransport: EmailTransport,
    private val redis: RedisCommands<String, String>?,
    templateDir: String? = null,
    /** The header band and host small-print every mail carries; see [MailBranding]. */
    private val branding: MailBranding = MailBranding(),
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val deliveryLog = LoggerFactory.getLogger("email.delivery")
    private val templateCache = ConcurrentHashMap<String, String>()
    private val layoutHtml: String by lazy {
        readOverride(LAYOUT_FILE) ?: loadResource("/email-templates/$LAYOUT_FILE")
    }

    /**
     * The canonical template root, or null when none is configured (or the
     * configured one is unusable — which degrades to classpath-only rather than
     * refusing to start).
     */
    private val templateRoot: File? = resolveRoot(templateDir)

    init {
        // Said once, at startup, so an operator who configured an override can
        // see it took effect — and so one that silently did not is visible.
        if (templateDir.isNullOrBlank()) {
            log.info("email templates: packaged templates only (no template directory configured)")
        } else if (templateRoot == null) {
            log.warn(
                "email templates: configured directory '{}' is not a readable directory — " +
                    "falling back to the packaged templates only",
                templateDir,
            )
        } else {
            log.info(
                "email templates: '{}' is consulted first, packaged templates are the fallback",
                templateRoot,
            )
        }
    }

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
            // Branding first, then the sender's variables: the brand rows are our
            // own markup, the variables are escaped text, and doing it in this
            // order means a variable value can never be read as a brand key.
            htmlBody = renderTemplate(applyBranding(templateHtml), vars)
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
     * Substitutes the brand header and the host footer into a template or the
     * layout. Both placeholders are always resolved — the footer to an empty
     * string when there is no small print — so a rendered mail never shows one.
     */
    private fun applyBranding(html: String): String =
        branding.placeholders().entries.fold(html) { acc, (key, value) -> acc.replace("{{$key}}", value) }

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
        val withContent = applyBranding(layoutHtml).replace("{{content}}", body)
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
     * Checks idempotency for the given message id.
     * Returns true if this message should be processed, false if it's a duplicate.
     */
    open fun checkIdempotency(id: String): Boolean {
        if (redis == null) return true
        val result = redis.set("email:sent:$id", "", SetArgs().nx().ex(86400))
        return result != null
    }

    /**
     * The HTML for [type], from the configured directory if it has one and from
     * the classpath otherwise, or null when neither does.
     *
     * The relative path is checked once, before either lookup. Both need it: the
     * classloader silently collapses `//` and `.` segments, so a type of
     * `..system..invite` ("//system//invite.html") resolves on the classpath to
     * the very template a caller was not allowed to name that way.
     */
    private fun loadTemplate(type: String): String? {
        templateCache[type]?.let { return it }
        // type = "system.invite" → relative = "system/invite.html"
        val relative = "${type.replace('.', '/')}.html"
        if (!isSafeRelativePath(relative)) {
            log.error("refusing template type '{}': '{}' is not a path inside the template tree", type, relative)
            return null
        }
        val html = readOverride(relative)
            ?: javaClass.getResourceAsStream("/email-templates/$relative")
                ?.bufferedReader()?.readText()
            ?: return null
        templateCache[type] = html
        return html
    }

    /**
     * Reads [relativePath] from the configured template directory, or null when
     * there is no directory, no such file, or anything at all goes wrong.
     *
     * The path is treated as untrusted even though only internal publishers name
     * one: a `type` of `../../etc/passwd` would otherwise turn a queue message
     * into an arbitrary-file read whose contents are then mailed to an address
     * the same message chose. Two defences, because either alone has a way past
     * it — the segments are checked before the file is touched, and the
     * canonical result must still sit under the canonical root (which is what
     * catches a symlink pointing out of the directory).
     */
    private fun readOverride(relativePath: String): String? {
        val root = templateRoot ?: return null
        // Belt and braces: every caller checks too, but this is the one that
        // turns a path into a file read.
        if (!isSafeRelativePath(relativePath)) return null
        return try {
            val file = File(root, relativePath).canonicalFile
            if (!file.path.startsWith(root.path + File.separator)) {
                log.error("refusing template path '{}': it resolves outside {}", relativePath, root)
                return null
            }
            if (!file.isFile || !file.canRead()) return null
            file.readText()
        } catch (e: Exception) {
            // A broken override is not allowed to take down the mail path: fall
            // back to what this service shipped with and say why.
            log.error("could not read template override '{}' under {}: {}", relativePath, root, e.message)
            null
        }
    }

    /**
     * Substitutes `{{key}}` placeholders with the job's variables.
     *
     * Values are HTML-escaped before substitution. The templates are HTML
     * documents and several variables are user-controlled — an inviter's display
     * name, an organization name, the reset recipient's name — reaching here
     * straight from what a person typed. Substituting them raw let markup (a
     * `<a>` phishing link, a `<style>` overlay) be injected into DKIM-signed mail
     * the platform sends on the user's behalf. Escaping centrally, once, means
     * every current and future template is safe by default and no publisher has
     * to remember to escape at its call site. Escaping a URL variable
     * (`inviteLink`, `resetLink`) only turns `&` into `&amp;`, which is the
     * correct encoding inside an `href`, so links still work.
     */
    private fun renderTemplate(template: String, vars: JsonObject): String {
        var result = template
        for ((key, value) in vars) {
            val strValue = escapeHtml(value.jsonPrimitive.content)
            result = result.replace("{{$key}}", strValue)
        }
        return result
    }

    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&#39;")

    private companion object {
        /** The optional footer region of the layout, markers included. */
        val FOOTER_BLOCK = Regex(
            "<!-- FOOTER_START.*?FOOTER_END -->",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )

        /** The wrapper for body-mode mail, overridable like any other template. */
        const val LAYOUT_FILE = "layout.html"

        /**
         * The configured directory as a canonical [File], or null when it is
         * unset or unusable. Never throws: a template root that cannot be
         * resolved leaves this service running on its packaged templates.
         */
        fun resolveRoot(configured: String?): File? {
            if (configured.isNullOrBlank()) return null
            return try {
                File(configured).canonicalFile.takeIf { it.isDirectory }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * True when [path] is a plain relative path that cannot walk out of the
         * directory it is resolved against. Rejects absolute paths, drive
         * letters, `..` segments, backslashes and embedded NULs.
         */
        fun isSafeRelativePath(path: String): Boolean {
            if (path.isEmpty()) return false
            if (path.contains('\u0000') || path.contains('\\')) return false
            if (path.startsWith("/") || path.startsWith("~")) return false
            if (Regex("^[A-Za-z]:").containsMatchIn(path)) return false
            val segments = path.split('/')
            return segments.all { it.isNotEmpty() && it != "." && it != ".." }
        }
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
