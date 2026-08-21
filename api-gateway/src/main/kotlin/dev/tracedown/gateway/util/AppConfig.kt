package dev.tracedown.gateway.util

import dev.tracedown.common.email.EmailConfig
import dev.tracedown.common.onboarding.DefaultGroupConfig
import dev.tracedown.common.email.ConsoleConfig
import dev.tracedown.common.email.FileConfig
import dev.tracedown.common.email.MailgunConfig
import dev.tracedown.common.email.ResendConfig
import dev.tracedown.common.email.SmtpConfig
import dev.tracedown.common.email.TlsMode
import dev.tracedown.common.util.processUri
import dev.tracedown.common.variables.VariableLimits
import io.ktor.server.application.ApplicationEnvironment

data class DatabaseConfig(
    val url: String,
    val user: String,
    val password: String
)

data class RedisConfig(
    val aUrl: String,
    val bUrl: String,
    val cUrl: String?,
    val cacheTtlSeconds: Long,
)

data class JwtConfig(
    val secret: String,
    val ttlMinutes: Long
)

data class UriConfig(
    val appUrl: String,
    val invite: String,
    val passwordReset: String,
) {
    fun inviteUrl(token: String): String = "$appUrl$invite/$token"
    fun passwordResetUrl(token: String): String = "$appUrl$passwordReset/$token"
}

data class SeedConfig(
    val enabled: Boolean,
    val projectName: String,
    val serviceName: String,
    val targetUrl: String,
    val schedule: String,
)

data class PlatformConfig(
    val uri: UriConfig,
    val aesKey: String,
    val singleOrgMode: Boolean,
    val demoUserEmail: String,
    val demoUserPassword: String,
    val inviteTtlDays: Long,
    val inviteResendCooldownMinutes: Long,
    val defaultGroups: List<DefaultGroupConfig>,
    val trustedDomainMode: Boolean,
    val allowProfileEdit: Boolean,
    val metricsPublicUrl: String,
    val seed: SeedConfig,
)

data class PasswordPolicyConfig(
    val minLength: Int,
    val minUppercase: Int,
    val minDigits: Int,
    val minSpecial: Int,
)

data class AuthConfig(
    val passwordPolicy: PasswordPolicyConfig,
    val totpIssuer: String,
)

data class RequestLimitsConfig(
    val requestTimeoutMs: Long,
    val maxRetries: Int,
    val maxRedirects: Int
)

data class SystemLimitsConfig(
    val auditLogRetentionDays: Int,
    val purgeRetentionDays: Int,
    /** Probe-result retention (days) — caps the usage window. Mirrors the worker's. */
    val resultRetentionDays: Int,
    /**
     * Most variables one resource may hold — counted per org, per workspace, per
     * project, per service and per webhook. A guard against runaway creation,
     * identical for every organization.
     */
    val maxVarsPerResource: Int,
)

data class AppConfig(
    val database: DatabaseConfig,
    val redis: RedisConfig,
    val jwt: JwtConfig,
    val auth: AuthConfig,
    val email: EmailConfig,
    val platform: PlatformConfig,
    val requestLimits: RequestLimitsConfig,
    val systemLimits: SystemLimitsConfig,
) {
    companion object {
        fun load(environment: ApplicationEnvironment): AppConfig {
            val config = environment.config
            return AppConfig(
                database = DatabaseConfig(
                    url = config.property("database.url").getString(),
                    user = config.property("database.user").getString(),
                    password = config.property("database.password").getString()
                ),
                redis = RedisConfig(
                    aUrl = config.property("redis.a.url").getString(),
                    bUrl = config.property("redis.b.url").getString(),
                    cUrl = config.propertyOrNull("redis.c.url")?.getString()?.ifBlank { null },
                    cacheTtlSeconds = config.propertyOrNull("redis.c.ttlSeconds")
                        ?.getString()?.toLong() ?: 3600L,
                ),
                jwt = JwtConfig(
                    secret = config.property("jwt.secret").getString(),
                    ttlMinutes = config.property("jwt.ttlMinutes").getString().toLong()
                ),
                auth = AuthConfig(
                    passwordPolicy = PasswordPolicyConfig(
                        minLength = config.property("auth.passwordPolicy.minLength").getString().toInt(),
                        minUppercase = config.property("auth.passwordPolicy.minUppercase").getString().toInt(),
                        minDigits = config.property("auth.passwordPolicy.minDigits").getString().toInt(),
                        minSpecial = config.property("auth.passwordPolicy.minSpecial").getString().toInt(),
                    ),
                    totpIssuer = config.property("auth.totpIssuer").getString(),
                ),
                email = loadEmailConfig(config),
                platform = PlatformConfig(
                    uri = UriConfig(
                        appUrl = processUri(config.property("platform.uri.appUrl").getString()),
                        invite = processUri(config.property("platform.uri.invite").getString()),
                        passwordReset = processUri(config.property("platform.uri.passwordReset").getString()),
                    ),
                    aesKey = config.property("platform.aesKey").getString(),
                    singleOrgMode = config.property("platform.singleOrgMode").getString().toBoolean(),
                    demoUserEmail = config.property("platform.demoUserEmail").getString(),
                    demoUserPassword = config.property("platform.demoUserPassword").getString(),
                    inviteTtlDays = config.property("platform.inviteTtlDays").getString().toLong(),
                    inviteResendCooldownMinutes = config.property("platform.inviteResendCooldownMinutes").getString().toLong(),
                    defaultGroups = loadDefaultGroups(config),
                    trustedDomainMode = config.property("platform.trustedDomainMode").getString().toBoolean(),
                    allowProfileEdit = config.property("platform.allowProfileEdit").getString().toBoolean(),
                    metricsPublicUrl = config.property("platform.metricsPublicUrl").getString(),
                    seed = SeedConfig(
                        enabled = config.property("platform.seed.enabled").getString().toBoolean(),
                        projectName = config.property("platform.seed.projectName").getString(),
                        serviceName = config.property("platform.seed.serviceName").getString(),
                        targetUrl = config.property("platform.seed.targetUrl").getString(),
                        schedule = config.property("platform.seed.schedule").getString(),
                    ),
                ),
                requestLimits = RequestLimitsConfig(
                    requestTimeoutMs = config.property("requestLimits.requestTimeoutMs").getString().toLong(),
                    maxRetries = config.property("requestLimits.maxRetries").getString().toInt(),
                    maxRedirects = config.property("requestLimits.maxRedirects").getString().toInt()
                ),
                systemLimits = SystemLimitsConfig(
                    auditLogRetentionDays = config.property("systemLimits.auditLogRetentionDays").getString().toInt(),
                    purgeRetentionDays = config.property("systemLimits.purgeRetentionDays").getString().toInt(),
                    resultRetentionDays = config.propertyOrNull("systemLimits.resultRetentionDays")?.getString()?.toInt() ?: 90,
                    maxVarsPerResource = config.propertyOrNull("systemLimits.maxVarsPerResource")?.getString()?.toInt()
                        ?: VariableLimits.DEFAULT_MAX_PER_RESOURCE,
                )
            )
        }

        private fun loadDefaultGroups(config: io.ktor.server.config.ApplicationConfig): List<DefaultGroupConfig> {
            return config.configList("platform.defaultGroups").map { group ->
                val settings = group.property("settings").getString().toShort()
                DefaultGroupConfig(
                    name = group.property("name").getString(),
                    users = group.property("users").getString().toShort(),
                    settings = settings,
                    domains = group.property("domains").getString().toShort(),
                    webhooks = group.property("webhooks").getString().toShort(),
                    // Notification templates used to live under the settings gate;
                    // default the new section to the group's settings level so
                    // existing default groups keep managing them unless overridden.
                    notifications = group.propertyOrNull("notifications")?.getString()?.toShort() ?: settings,
                    // Admin (org identity/policy + danger zone) is high-trust:
                    // default off unless a group explicitly declares it.
                    admin = group.propertyOrNull("admin")?.getString()?.toShort() ?: 0,
                    workspaces = group.property("workspaces").getString().toShort(),
                )
            }
        }

        private fun loadEmailConfig(config: io.ktor.server.config.ApplicationConfig): EmailConfig {
            val provider = config.property("email.provider").getString()
            return EmailConfig(
                provider = provider,
                fromAddress = config.property("email.fromAddress").getString(),
                fromName = config.property("email.fromName").getString(),
                smtp = if (provider == "smtp") SmtpConfig(
                    host = config.property("email.smtp.host").getString(),
                    port = config.property("email.smtp.port").getString().toInt(),
                    username = config.property("email.smtp.username").getString(),
                    password = config.property("email.smtp.password").getString(),
                    tlsMode = TlsMode.valueOf(config.property("email.smtp.tlsMode").getString()),
                ) else null,
                resend = if (provider == "resend") ResendConfig(
                    apiKey = config.property("email.resend.apiKey").getString(),
                ) else null,
                mailgun = if (provider == "mailgun") MailgunConfig(
                    apiKey = config.property("email.mailgun.apiKey").getString(),
                    domain = config.property("email.mailgun.domain").getString(),
                    region = config.property("email.mailgun.region").getString(),
                ) else null,
                console = ConsoleConfig(
                    attachmentDir = config.propertyOrNull("email.console.attachmentDir")?.getString()
                        ?: "build/email-attachments",
                ),
                file = if (provider == "file") FileConfig(
                    path = config.property("email.file.path").getString(),
                ) else null,
            )
        }
    }
}
