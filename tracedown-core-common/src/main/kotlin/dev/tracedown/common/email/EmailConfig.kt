package dev.tracedown.common.email

enum class TlsMode { STARTTLS, SMTPS, PLAIN }

data class EmailConfig(
    val provider: String,
    val fromAddress: String,
    val fromName: String,
    val smtp: SmtpConfig?,
    val resend: ResendConfig?,
    val mailgun: MailgunConfig?,
    val file: FileConfig?,
    val console: ConsoleConfig? = null,
)

data class SmtpConfig(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val tlsMode: TlsMode,
    val connectionTimeoutMs: Int = 10_000,
    val readTimeoutMs: Int = 10_000,
)

data class ResendConfig(
    val apiKey: String,
    /**
     * Svix signing secret (`whsec_…`) for the delivery webhook. Blank disables
     * the endpoint: an unverified bounce webhook would let anyone suppress any
     * address, so no secret means no webhook.
     */
    val webhookSecret: String = "",
)

data class MailgunConfig(
    val apiKey: String,
    val domain: String,
    val region: String = "us",
    /**
     * Webhook signing key — distinct from [apiKey], and issued separately in the
     * Mailgun dashboard. Blank disables the endpoint (see [ResendConfig]).
     */
    val webhookSigningKey: String = "",
)

data class FileConfig(
    val path: String,
)

/** Where the console transport drops attachments so they can be opened. */
data class ConsoleConfig(
    val attachmentDir: String,
)

/**
 * Builds the concrete [EmailTransport] for the configured provider. [logBodies]
 * only affects the dev `console` transport: when true it prints full message
 * bodies (a local dev aid), when false (default) bodies are omitted from the log
 * since they carry reset links / invite tokens.
 */
fun createTransport(config: EmailConfig, logBodies: Boolean = false): EmailTransport {
    return when (config.provider) {
        "smtp" -> {
            val smtp = requireNotNull(config.smtp) { "smtp config required when provider is 'smtp'" }
            SmtpTransport(config.fromAddress, config.fromName, smtp)
        }
        "resend" -> {
            val resend = requireNotNull(config.resend) { "resend config required when provider is 'resend'" }
            ResendTransport(config.fromAddress, config.fromName, resend)
        }
        "mailgun" -> {
            val mailgun = requireNotNull(config.mailgun) { "mailgun config required when provider is 'mailgun'" }
            MailgunTransport(config.fromAddress, config.fromName, mailgun)
        }
        "console" -> ConsoleTransport(
            config.fromAddress,
            config.fromName,
            config.console?.attachmentDir ?: ConsoleTransport.DEFAULT_ATTACHMENT_DIR,
            logBodies = logBodies,
        )
        "file" -> {
            val file = requireNotNull(config.file) { "file config required when provider is 'file'" }
            FileTransport(config.fromAddress, config.fromName, file.path)
        }
        else -> throw IllegalArgumentException("Unknown email provider: ${config.provider}. Supported: smtp, resend, mailgun, console, file")
    }
}
