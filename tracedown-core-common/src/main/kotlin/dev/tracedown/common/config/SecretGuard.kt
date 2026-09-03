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
 * What counts as insecure is judged structurally rather than from a list of
 * remembered literals — see [credentialWeakness]. A guard written as a list only
 * ever refuses the values somebody thought to write down, while the values an
 * operator reaches for are whichever ones the tracked example files happen to
 * ship; that gap is how a "guarded" production deploy ended up running a
 * published key-encryption key.
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
     * setting when the guards are enforcing.
     *
     * [checks] maps a human-readable setting name to whether it currently holds
     * an insecure default (`true` = insecure) — for conditions that are not a
     * credential value, such as a required setting being unset.
     *
     * [credentials] maps an environment-variable name to the credential value
     * actually in force. Each is judged by [credentialWeakness], which is where
     * the real coverage lives: comparing against a hand-written list of known-bad
     * literals only ever catches the literals someone remembered to add, and the
     * values an operator is most likely to copy are the ones in the tracked
     * example files. Prefer this parameter over encoding a comparison in [checks].
     *
     * A no-op in dev (or when the override is set) — but never a *silent* one:
     * see [announce].
     */
    fun requireSecure(
        configValue: String?,
        service: String,
        checks: Map<String, Boolean>,
        credentials: Map<String, String?> = emptyMap(),
    ) {
        val credentialFindings = credentials.mapNotNull { (name, value) ->
            credentialWeakness(value)?.let { "$name ($it)" }
        }
        if (!enforcing(configValue)) {
            announce(configValue, service, checks + credentialFindings.associateWith { true })
            return
        }
        val violations = checks.filterValues { it }.keys.toList() + credentialFindings
        if (violations.isEmpty()) return
        throw IllegalStateException(
            "$service refusing to start: insecure default configuration is not allowed in production for " +
                violations.joinToString(", ") +
                ". Configure real values (or set $OVERRIDE_VAR=true to override — not recommended).",
        )
    }

    /**
     * The shortest a production credential may be.
     *
     * Every credential these guards cover is machine-generated (a 64-hex key, a
     * random signing secret) — nobody types one — so a floor well above what a
     * human would choose costs a correctly-configured deployment nothing and
     * refuses the whole class of "tracedown" / "changeme" values.
     */
    const val MIN_CREDENTIAL_LENGTH = 24

    /**
     * Words that only appear in a credential somebody wrote by hand. Six
     * characters or longer, because these are matched as substrings: a random
     * hex or base64 secret containing one of them by chance is a rounding error
     * away from impossible.
     */
    private val PLACEHOLDER_SUBSTRINGS = listOf(
        "changeme", "change-me", "change_me", "insecure", "placeholder", "password",
        "example", "default", "development", "localhost", "tracedown", "notsecure",
        "replaceme", "replace-me", "sample", "dummy", "secret-key", "yoursecret",
    )

    /**
     * Words that give a hand-written credential away but are too short to match
     * as substrings without false positives, so they must appear as a whole
     * delimiter-separated word (`dev-jwt-secret-…`, `my.test.key`).
     */
    private val PLACEHOLDER_WORDS = setOf(
        "dev", "devel", "test", "testing", "demo", "temp", "tmp", "todo", "xxx",
        "secret", "passwd", "pass", "admin", "root", "local", "prod", "foo", "bar",
        "change", "replace", "unsafe", "fake", "mock", "seed",
    )

    /**
     * Credential values published in this repository's tracked files, read from
     * the `insecure-credentials.txt` resource. Loaded once; an unreadable or
     * missing resource degrades to the structural checks rather than failing
     * every service's startup.
     */
    private val publishedValues: Set<String> by lazy {
        val stream = SecretGuard::class.java.classLoader.getResourceAsStream(PUBLISHED_VALUES_RESOURCE)
        if (stream == null) {
            log.warn(
                "{} is not on the classpath — the published-credential check is degraded to its structural half.",
                PUBLISHED_VALUES_RESOURCE,
            )
            emptySet()
        } else {
            stream.bufferedReader().useLines { lines ->
                lines.map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toSet()
            }
        }
    }

    /** Name of the classpath resource holding [publishedValues]. */
    const val PUBLISHED_VALUES_RESOURCE = "insecure-credentials.txt"

    /** The published dev/example credential values this guard refuses outright. */
    fun publishedCredentialValues(): Set<String> = publishedValues

    /**
     * Why [value] is unfit to be a production credential, or null if nothing is
     * visibly wrong with it.
     *
     * Structural, not a list of literals. A list only refuses the values someone
     * thought to write down, and the values an operator actually reaches for are
     * whatever the tracked example files happen to ship on the day they deploy —
     * which is how a "guarded" production install ended up running a published
     * key-encryption key. What is checked instead is the shape every published
     * dev value has and no generated credential does: it is short, or it is a
     * pattern repeated to length, or it has almost no entropy, or it says in
     * words that it is a placeholder. The published list is consulted too, as a
     * backstop for a dev default that manages to look random.
     *
     * This is deliberately conservative about *legitimate* values: it can only
     * refuse a real credential that is under [MIN_CREDENTIAL_LENGTH] characters
     * or spells out a placeholder word, and either is worth refusing anyway.
     */
    fun credentialWeakness(value: String?): String? {
        val v = value?.trim()
        if (v.isNullOrEmpty()) return "unset"
        if (v in publishedValues) return "a value published in this repository"
        if (v.length < MIN_CREDENTIAL_LENGTH) {
            return "shorter than $MIN_CREDENTIAL_LENGTH characters"
        }
        val lower = v.lowercase()
        PLACEHOLDER_SUBSTRINGS.firstOrNull { lower.contains(it) }?.let {
            return "contains the placeholder text '$it'"
        }
        val words = lower.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (words.size > 1) {
            words.firstOrNull { it in PLACEHOLDER_WORDS }?.let {
                return "contains the placeholder word '$it'"
            }
        }
        repeatedUnitLength(v)?.let {
            return "a $it-character pattern repeated to length"
        }
        if (shannonEntropyPerChar(v) < MIN_ENTROPY_BITS_PER_CHAR) {
            return "too little entropy to be a generated credential"
        }
        return null
    }

    /**
     * Entropy floor, in bits per character. Random hex sits near 3.9 and random
     * base64 near 5.5, so this only catches values built from a handful of
     * distinct characters — an all-zero key being the extreme.
     */
    const val MIN_ENTROPY_BITS_PER_CHAR = 3.0

    /**
     * Length of the shortest unit [value] is a whole repetition of, or null when
     * it is not a repetition. Catches the padded-to-length dev keys that pass an
     * entropy check because their unit is varied — `0123456789abcdef` four times
     * over scores a perfect four bits per character.
     */
    private fun repeatedUnitLength(value: String): Int? {
        for (unit in 1..value.length / 2) {
            if (value.length % unit != 0) continue
            val head = value.substring(0, unit)
            if ((0 until value.length / unit).all { i ->
                    value.regionMatches(i * unit, head, 0, unit)
                }
            ) {
                return unit
            }
        }
        return null
    }

    /** Shannon entropy of [value] in bits per character. */
    private fun shannonEntropyPerChar(value: String): Double {
        val counts = HashMap<Char, Int>()
        for (c in value) counts[c] = (counts[c] ?: 0) + 1
        val n = value.length.toDouble()
        return counts.values.sumOf { count ->
            val p = count / n
            -p * (Math.log(p) / Math.log(2.0))
        }
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
            if (overrideSet()) {
                // enforcing() already warned about the override; make the consequence explicit.
                log.error(
                    "SECURITY: {} is running in production with the insecure-default guards DISABLED by {}.{}",
                    service, OVERRIDE_VAR, stillInsecure,
                )
            } else if (insecure.isNotEmpty()) {
                // Production, guards armed, and yet a dev default is in place: the
                // caller reported it here instead of through requireSecure, so say
                // so at the level a violation deserves.
                log.error("SECURITY: {} is running in production with dev defaults in place: {}.", service, insecure.joinToString(", "))
            } else {
                // The ordinary production boot. Services with no secrets of their
                // own call this directly (metrics, realtime, aggregate-worker), and
                // before this branch existed they logged the override ERROR above
                // on every healthy production start — a false alarm in the one log
                // line operators are told to grep for.
                log.info("{} running in production — insecure-default startup guards armed.", service)
            }
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
