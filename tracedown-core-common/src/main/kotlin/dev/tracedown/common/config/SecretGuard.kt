package dev.tracedown.common.config

import org.slf4j.LoggerFactory

/**
 * Fail-fast startup guard for insecure default secrets.
 *
 * The dev stack ships deliberately insecure defaults (all-zero AES keys, dev JWT
 * secrets, a seed admin, console email) so a fresh
 * checkout runs with zero configuration. Those defaults must never reach a real
 * deployment. This guard refuses to start a service when a known-insecure value is
 * still in place **and** the process is running in production — dev stays untouched.
 *
 * The deployment environment is resolved from the Ktor config key
 * `deployment.environment` (which the shipped configs wire to the `DEPLOYMENT_ENV`
 * environment variable), defaulting to `dev`. Only the exact value `production`
 * arms the guards. An explicit escape hatch, `ALLOW_INSECURE_DEV_KEYS=true`,
 * suppresses the guards even in production (for a throwaway prod-like sandbox); it
 * is never needed for ordinary development, which is not `production` to begin with.
 *
 * Arming on one exact string means the guards are fail-open by construction: a
 * `prod` typo, an unrecognised value or an unset variable all leave a service
 * running on dev secrets. They are never fail-*silent*, though — an unarmed
 * guard says so at startup ([announce]), and a value that was clearly reaching
 * for production says so at ERROR.
 */
object SecretGuard {
    const val ENV_VAR = "DEPLOYMENT_ENV"
    const val OVERRIDE_VAR = "ALLOW_INSECURE_DEV_KEYS"
    const val PRODUCTION = "production"

    private val log = LoggerFactory.getLogger(SecretGuard::class.java)

    /**
     * The resolved deployment environment. An explicit [configValue] (from
     * `deployment.environment`) wins; otherwise the `DEPLOYMENT_ENV` env var is
     * consulted; otherwise it defaults to `dev`.
     */
    fun environment(configValue: String? = null): String =
        (configValue?.takeIf { it.isNotBlank() } ?: System.getenv(ENV_VAR))
            ?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "dev"

    /** True when the resolved environment is exactly `production`. */
    fun isProduction(configValue: String? = null): Boolean = environment(configValue) == PRODUCTION

    private fun overrideSet(): Boolean =
        System.getenv(OVERRIDE_VAR)?.trim()?.lowercase() in setOf("1", "true", "yes")

    /** True when the insecure-default guards must fire (production, no explicit override). */
    fun enforcing(configValue: String? = null): Boolean {
        if (!isProduction(configValue)) return false
        if (overrideSet()) {
            log.warn(
                "{} is set — insecure-default startup guards are DISABLED even though the environment is production.",
                OVERRIDE_VAR,
            )
            return false
        }
        return true
    }

    /**
     * Fails startup with a single [IllegalStateException] listing every offending
     * setting when the guards are enforcing. [checks] maps a human-readable setting
     * name to whether it currently holds an insecure default (`true` = insecure).
     * A no-op in dev (or when the override is set) — but never a *silent* one:
     * see [announce].
     */
    fun requireSecure(configValue: String?, service: String, checks: Map<String, Boolean>) {
        if (!enforcing(configValue)) {
            announce(configValue, service, checks)
            return
        }
        val violations = checks.filterValues { it }.keys
        if (violations.isEmpty()) return
        throw IllegalStateException(
            "$service refusing to start: insecure default configuration is not allowed in production for " +
                violations.joinToString(", ") +
                ". Configure real values (or set $OVERRIDE_VAR=true to override — not recommended).",
        )
    }

    /**
     * Says, at startup, that the guards are NOT armed and why.
     *
     * The guards arm on one exact string, so every way of getting it wrong —
     * `prod`, a stray value, the variable never set — silently produces a
     * *running* service with dev secrets in it. That is the failure worth
     * shouting about: nothing else in the boot sequence mentions it. Call this
     * directly from services that have no insecure-default checks of their own,
     * so `DEPLOYMENT_ENV` is reported uniformly across the fleet;
     * [requireSecure] calls it for the rest.
     *
     * A value that *looks* like an attempt at production (`prod`, `prd`,
     * `live`, `production-eu`…) is logged at ERROR, because it is a typo with
     * security consequences rather than an ordinary dev run.
     */
    fun announce(configValue: String?, service: String, checks: Map<String, Boolean> = emptyMap()) {
        val env = environment(configValue)
        val insecure = checks.filterValues { it }.keys
        val stillInsecure = if (insecure.isEmpty()) "" else " Dev defaults in place: ${insecure.joinToString(", ")}."

        if (env == PRODUCTION) {
            // enforcing() already warned about the override; make the consequence explicit.
            log.error(
                "SECURITY: {} is running in production with the insecure-default guards DISABLED by {}.{}",
                service, OVERRIDE_VAR, stillInsecure,
            )
            return
        }

        if (looksLikeProduction(env)) {
            log.error(
                "SECURITY: {} read {}='{}', which is NOT the literal '{}' — the insecure-default startup " +
                    "guards are NOT armed. Set {}={} exactly.{}",
                service, ENV_VAR, env, PRODUCTION, ENV_VAR, PRODUCTION, stillInsecure,
            )
            return
        }

        log.warn(
            "{} starting with {}='{}' — insecure-default startup guards are NOT armed (only '{}' arms them).{}",
            service, ENV_VAR, env, PRODUCTION, stillInsecure,
        )
    }

    /**
     * Near-misses for [PRODUCTION]. Anything reaching for production without
     * hitting it exactly leaves a deployment unguarded, so it is called out
     * louder than an ordinary dev value.
     */
    internal fun looksLikeProduction(env: String): Boolean =
        env != PRODUCTION && (env.startsWith("prod") || env.startsWith("prd") || env == "live")
}
