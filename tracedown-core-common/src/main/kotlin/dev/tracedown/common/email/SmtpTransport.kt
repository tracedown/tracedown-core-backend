package dev.tracedown.common.email

import jakarta.mail.Message
import org.simplejavamail.api.mailer.Mailer
import org.simplejavamail.api.mailer.config.TransportStrategy
import org.simplejavamail.email.EmailBuilder
import org.simplejavamail.mailer.MailerBuilder
import org.simplejavamail.recipient.RecipientsBuilder
import org.slf4j.LoggerFactory

class SmtpTransport(
    private val fromAddress: String,
    private val fromName: String,
    private val config: SmtpConfig,
) : EmailTransport {

    private val log = LoggerFactory.getLogger(SmtpTransport::class.java)

    private val mailer: Mailer = MailerBuilder
        .withSMTPServer(config.host, config.port, config.username, config.password)
        .withTransportStrategy(
            when (config.tlsMode) {
                TlsMode.STARTTLS -> TransportStrategy.SMTP_TLS
                TlsMode.SMTPS -> TransportStrategy.SMTPS
                TlsMode.PLAIN -> TransportStrategy.SMTP
            }
        )
        .withProperty("mail.smtp.connectiontimeout", config.connectionTimeoutMs.toString())
        .withProperty("mail.smtp.timeout", config.readTimeoutMs.toString())
        // Synchronous on purpose: an async mailer returns a future that this
        // transport discarded, so a failed SMTP send threw on a background
        // thread and every job still logged "sent". Sending inline lets the
        // failure propagate to EmailProcessor's try/catch, which records the
        // real outcome and reports it back on the status queue.
        .buildMailer()

    override fun send(message: EmailMessage) {
        mailer.sendMail(buildEmail(message))
    }

    override fun sendBatch(messages: List<EmailMessage>) {
        log.info("Sending batch of {} emails via SMTP", messages.size)
        for (message in messages) {
            // async = false: the send must throw here if it fails, not on a
            // detached thread whose exception nobody sees.
            mailer.sendMail(buildEmail(message), false)
        }
    }

    override fun close() {
        mailer.close()
    }

    private fun buildEmail(message: EmailMessage): org.simplejavamail.api.email.Email {
        return EmailBuilder.startingBlank()
            .from(fromName, fromAddress)
            // Simple Java Mail 9 dropped the ~100 to()/cc()/bcc() overloads;
            // recipients are now assembled through a RecipientsBuilder. This
            // call is the exact delegate the old to(String) used, so a
            // comma/semicolon-delimited address list still expands the same way.
            .withRecipients(
                RecipientsBuilder()
                    .withRecipientsWithDefaultName(null, Message.RecipientType.TO, message.to)
                    .buildRecipients()
            )
            .withSubject(message.subject)
            .withHTMLText(message.htmlBody)
            .apply {
                message.plainTextBody?.let { withPlainText(it) }
                message.replyTo?.let { withReplyTo(it) }
                for (attachment in message.attachments) {
                    withAttachment(attachment.filename, attachment.content, attachment.contentType)
                }
            }
            .buildEmail()
    }
}
