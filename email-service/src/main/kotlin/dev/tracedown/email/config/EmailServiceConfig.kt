package dev.tracedown.email.config

import dev.tracedown.common.email.EmailConfig
import dev.tracedown.common.email.ConsoleConfig
import dev.tracedown.common.email.FileConfig
import dev.tracedown.common.email.MailgunConfig
import dev.tracedown.common.email.ResendConfig
import dev.tracedown.common.email.SmtpConfig
import dev.tracedown.common.email.TlsMode
import dev.tracedown.email.processing.MailBranding
import io.ktor.server.application.ApplicationEnvironment

/**
 * Typed configuration for the email-service.
 */
data class EmailServiceConfig(
    val redisAUrl: String,
    val email: EmailConfig,
    val popTimeoutSeconds: Long,
    /**
     * Optional filesystem directory of mail templates, laid out exactly like the
     * packaged `email-templates/` tree. Consulted before the packaged templates,
     * so it both adds new types and overrides shipped ones. Null (the default)
     * means packaged templates only. See `EmailProcessor`.
     */
    val templateDir: String?,
    /** The header band and host small-print every mail carries. */
    val branding: MailBranding = MailBranding(),
) {
    companion object {
        /** Loads configuration from the Ktor application environment. */
        fun load(env: ApplicationEnvironment): EmailServiceConfig {
            val config = env.config
            return EmailServiceConfig(
                redisAUrl = config.property("redis.a.url").getString(),
                email = loadEmailConfig(env),
                popTimeoutSeconds = config.propertyOrNull("emailService.popTimeoutSeconds")
                    ?.getString()?.toLong() ?: 5L,
                templateDir = config.propertyOrNull("emailService.templateDir")
                    ?.getString()?.takeIf { it.isNotBlank() },
                branding = MailBranding(
                    logoUrl = config.propertyOrNull("emailService.branding.logoUrl")?.getString()?.takeIf { it.isNotBlank() },
                    productUrl = config.propertyOrNull("emailService.branding.productUrl")?.getString()?.takeIf { it.isNotBlank() },
                    footerHtml = config.propertyOrNull("emailService.branding.footerHtml")?.getString()?.takeIf { it.isNotBlank() },
                ),
            )
        }

        private fun loadEmailConfig(env: ApplicationEnvironment): EmailConfig {
            val config = env.config
            val provider = config.propertyOrNull("email.provider")?.getString() ?: "console"
            return EmailConfig(
                provider = provider,
                fromAddress = config.propertyOrNull("email.fromAddress")?.getString()
                    ?: "notifications@tracedown.dev",
                fromName = config.propertyOrNull("email.fromName")?.getString()
                    ?: "Tracedown",
                smtp = if (provider == "smtp") SmtpConfig(
                    host = config.property("email.smtp.host").getString(),
                    port = config.property("email.smtp.port").getString().toInt(),
                    username = config.property("email.smtp.username").getString(),
                    password = config.property("email.smtp.password").getString(),
                    tlsMode = TlsMode.valueOf(
                        config.propertyOrNull("email.smtp.tlsMode")?.getString() ?: "STARTTLS"
                    ),
                ) else null,
                resend = if (provider == "resend") ResendConfig(
                    apiKey = config.property("email.resend.apiKey").getString(),
                ) else null,
                mailgun = if (provider == "mailgun") MailgunConfig(
                    apiKey = config.property("email.mailgun.apiKey").getString(),
                    domain = config.property("email.mailgun.domain").getString(),
                    region = config.propertyOrNull("email.mailgun.region")?.getString() ?: "us",
                ) else null,
                console = ConsoleConfig(
                    attachmentDir = config.propertyOrNull("email.console.attachmentDir")?.getString()
                        ?: "build/email-attachments",
                ),
                file = if (provider == "file") FileConfig(
                    path = config.propertyOrNull("email.file.path")?.getString() ?: "./emails",
                ) else null,
            )
        }
    }
}
