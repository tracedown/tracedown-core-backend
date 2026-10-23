package dev.tracedown.common.net

import dev.tracedown.common.variables.ScriptReferences
import java.net.InetAddress

/**
 * Decides which addresses a probe script is allowed to reach.
 *
 * Probes are dispatched to agents, and an agent may sit anywhere — including
 * inside the network the operator runs it in. Without a policy, a script is a
 * request-forgery primitive against that network: status codes, response
 * headers, assertion outcomes and five timing measurements all come back and
 * are shown in the app, which is enough to map internal services even when
 * bodies are suppressed.
 *
 * Probing a private address is nevertheless a *primary* use of a self-hosted
 * install — a LAN service, a Docker-internal name, an appliance on RFC1918 —
 * so the restriction cannot be unconditional. It is a policy with two modes:
 *
 * - [Mode.ALLOW_PRIVATE] — any address is a legitimate target. Only the scheme
 *   is enforced (a probe is an HTTP probe; `file:`/`gopher:` are never a
 *   monitoring target and are refused in both modes).
 * - [Mode.PUBLIC_ONLY] — the target must be a public internet address: no
 *   loopback, RFC1918, link-local, CGNAT, IPv6 unique-local or wildcard
 *   address, and no internal-only hostname. This is the mode for an install
 *   whose scripts are written by parties other than the operator.
 *
 * [resolveMode] picks between them from the operator's existing declarations
 * when the setting is left at `auto`; see there for the reasoning.
 *
 * ### What this cannot do
 * The check runs where the script is *saved* and where it is *dispatched* —
 * both in this platform's own processes. The connection itself is made by the
 * agent, in its own network with its own resolver, from a URL the executor may
 * rewrite (redirects) or build at runtime. A name that resolves publicly here
 * and privately there, a redirect into a private address, and a URL assembled
 * from a value read out of an earlier response are therefore all outside what
 * this can see. Enforcement in the executor is what closes those; this closes
 * the direct, static case, which is the one a user actually writes.
 */
object ProbeTargetPolicy {

    /** How targets that resolve to private or internal addresses are treated. */
    enum class Mode {
        /** Any address may be probed. Non-HTTP schemes are still refused. */
        ALLOW_PRIVATE,

        /** Only public internet addresses may be probed. */
        PUBLIC_ONLY,
    }

    /** Configured value meaning "decide from the rest of the configuration". */
    const val AUTO = "auto"

    /** The target's scheme is not `http` or `https`. */
    const val REASON_SCHEME = "target_scheme_not_http"

    /** The target names a host that only ever exists inside a private network. */
    const val REASON_INTERNAL_HOST = "target_internal_host"

    /** The target resolves to a loopback / private / link-local / CGNAT address. */
    const val REASON_PRIVATE_ADDRESS = "target_private_address"

    /** The target is not a URL this policy can parse. */
    const val REASON_MALFORMED = "target_malformed"

    /**
     * The target's host is assembled at runtime, so no address can be judged
     * before the agent connects. Refused under [Mode.PUBLIC_ONLY] because an
     * unjudgeable host is exactly the shape a bypass takes.
     */
    const val REASON_DYNAMIC_HOST = "target_dynamic_host"

    /** One call URL found in a script, and the verdict on it. */
    data class Decision(
        val allowed: Boolean,
        /** One of the `REASON_*` codes. */
        val reason: String? = null,
        /**
         * The offending target as the script spells it, variables
         * unsubstituted. The substituted URL is deliberately not kept: it can
         * carry the plaintext of an encrypted variable.
         */
        val source: String? = null,
        /** Which call of the script, from 0, when the verdict was made per call ([refusalsBySyntax]). */
        val callIndex: Int? = null,
    ) {
        companion object {
            val ALLOWED = Decision(allowed = true)
        }
    }

    /**
     * Resolves the effective mode from the configured value.
     *
     * `auto` — the default — reads the two declarations an operator has
     * already made rather than inventing a third:
     *
     * - **Trusted-domain mode on** means "this install only probes
     *   infrastructure I own". Private targets are that statement's whole
     *   point, so they are allowed.
     * - **Anything short of a production deployment** is a developer or a test
     *   stack, which probes `localhost` and container names constantly.
     *   Allowed, so a checkout keeps working with no configuration.
     * - What remains — a production deployment that requires domain ownership
     *   to be proven — is an install accepting scripts it did not write. That
     *   is the one that gets [Mode.PUBLIC_ONLY].
     *
     * An explicit `allow-private` / `public-only` always wins. An unrecognised
     * value is treated as `auto` rather than failing startup: this is a
     * hardening policy, and a typo must not take a monitoring platform down.
     */
    fun resolveMode(configured: String?, trustedDomainMode: Boolean, production: Boolean): Mode {
        return when (configured?.trim()?.lowercase()) {
            "allow-private", "allow_private" -> Mode.ALLOW_PRIVATE
            "public-only", "public_only" -> Mode.PUBLIC_ONLY
            else -> if (trustedDomainMode || !production) Mode.ALLOW_PRIVATE else Mode.PUBLIC_ONLY
        }
    }

    /** Human-readable note for the startup log, naming the setting an operator would change. */
    fun describe(mode: Mode, configured: String?): String = when (mode) {
        Mode.ALLOW_PRIVATE ->
            "probe targets on private/internal addresses are ALLOWED " +
                "(PROBE_TARGET_POLICY=${configured ?: AUTO}). Correct for an install that only probes its own " +
                "infrastructure; set PROBE_TARGET_POLICY=public-only where scripts come from parties other " +
                "than the operator."
        Mode.PUBLIC_ONLY ->
            "probe targets are restricted to public internet addresses " +
                "(PROBE_TARGET_POLICY=${configured ?: AUTO}). Set PROBE_TARGET_POLICY=allow-private to probe " +
                "a private network from this install."
    }

    // A call and the body of its URL string literal, escapes included (§2.2).
    private val CALL_RE = Regex("""\b(?:get|post|put|patch|delete)\s*\(\s*"((?:[^"\\]|\\.)*)"""")

    // scheme://authority, taken textually: a URL still carrying a `$` in its
    // path is legal and judgeable, and java.net.URI would refuse some of them.
    private val ORIGIN_RE = Regex("""^([A-Za-z][A-Za-z0-9+.\-]*)://([^/?#]*)""")

    /**
     * Every call URL a script targets, in source order, as the string values
     * the Lace lexer makes of them — escapes resolved (§2.2), so `\$p.host`
     * reads as the reference it interpolates as, and `\t` as a tab.
     */
    fun targetUrls(script: String): List<String> =
        CALL_RE.findAll(script).map { ScriptReferences.unescape(it.groupValues[1]) }.toList()

    /**
     * Replaces each reference [ScriptReferences] reads in [url] with the
     * variable it names in [vars] — keyed as injected (`p_baseUrl` for
     * `$p.baseUrl`, so a script judged before the scheduler's rewrite and the
     * rewritten one read the same map the same way). A braced reference is
     * replaced whole. A run-scope reference, or one with no value, is left as
     * written, and with it a `$` that keeps its host unjudgeable.
     */
    fun substituteVars(url: String, vars: Map<String, String>): String =
        ScriptReferences.replace(url) { ref -> if (ref.run) null else vars[ref.variable] }

    /**
     * The lowercase host of one already-substituted target URL, or null when
     * there is no host any check here can be made about: a URL this policy
     * cannot parse (an authority with a backslash or a control character
     * among them), or a host still assembled at runtime (`$o.endpoint`), which
     * is the case [REASON_DYNAMIC_HOST] already covers where it matters.
     */
    fun hostOf(url: String): String? {
        val origin = parseOrigin(url) ?: return null
        val host = origin.host ?: return null
        if (host.contains('$') || unparseableAuthority(origin.authority)) return null
        return host.lowercase()
    }

    /**
     * Judges one target without touching DNS.
     *
     * This is the write-time half: it catches the literal cases (an IP, an
     * internal name, a non-HTTP scheme) with no network round-trip in a request
     * handler, and defers everything else to [checkResolved] at dispatch.
     *
     * A host that still carries a variable inside a URL (`https://$p.host/`)
     * is deferred, even under [Mode.PUBLIC_ONLY]: at save time the variable may
     * simply not be set yet. A URL that is a variable from its first character
     * (`$p.baseUrl/health`) is not: with the variable unset it has no scheme,
     * so under [Mode.PUBLIC_ONLY] it is refused as [REASON_MALFORMED].
     *
     * @return a `REASON_*` code, or null when the target is acceptable.
     */
    fun checkSyntax(url: String, mode: Mode): String? {
        val origin = parseOrigin(url) ?: return if (mode == Mode.PUBLIC_ONLY) REASON_MALFORMED else null
        if (origin.scheme != "http" && origin.scheme != "https") return REASON_SCHEME
        if (mode == Mode.ALLOW_PRIVATE) return null
        val host = origin.host ?: return REASON_MALFORMED
        if (unparseableAuthority(origin.authority) || nonCanonicalIpv4(host)) return REASON_MALFORMED
        if (host.contains('$')) return null // deferred to dispatch
        if (SsrfGuard.isInternalHostname(host)) return REASON_INTERNAL_HOST
        val literal = parseLiteralIp(host)
        if (literal != null && SsrfGuard.isBlockedAddress(literal)) return REASON_PRIVATE_ADDRESS
        return null
    }

    /**
     * Judges one fully-substituted target, resolving its host.
     *
     * A host that cannot be resolved is **allowed** through: a name whose DNS is
     * down is precisely what a monitoring platform exists to notice, and turning
     * that into a skipped tick would replace the alert with silence. The agent
     * will fail the probe and the failure will be recorded as one.
     *
     * @param resolve injectable for tests; defaults to the system resolver.
     * @return a `REASON_*` code, or null when the target is acceptable.
     */
    fun checkResolved(
        url: String,
        mode: Mode,
        resolve: (String) -> List<InetAddress> = ::systemResolve,
    ): String? {
        val origin = parseOrigin(url) ?: return if (mode == Mode.PUBLIC_ONLY) REASON_MALFORMED else null
        if (origin.scheme != "http" && origin.scheme != "https") return REASON_SCHEME
        if (mode == Mode.ALLOW_PRIVATE) return null
        val host = origin.host ?: return REASON_MALFORMED
        if (unparseableAuthority(origin.authority) || nonCanonicalIpv4(host)) return REASON_MALFORMED
        // The host itself is built at runtime — nothing here can judge it.
        if (host.contains('$')) return REASON_DYNAMIC_HOST
        if (SsrfGuard.isInternalHostname(host)) return REASON_INTERNAL_HOST

        val literal = parseLiteralIp(host)
        if (literal != null) {
            return if (SsrfGuard.isBlockedAddress(literal)) REASON_PRIVATE_ADDRESS else null
        }

        val addresses = try {
            resolve(host)
        } catch (_: Exception) {
            return null // unresolvable: let the probe run and fail honestly
        }
        if (addresses.isEmpty()) return null
        return if (addresses.any { SsrfGuard.isBlockedAddress(it) }) REASON_PRIVATE_ADDRESS else null
    }

    /**
     * Judges every call in [script] with its variables substituted, returning the
     * first refusal. [vars] is a flat name→value map in whatever naming the
     * script uses.
     */
    fun evaluate(
        script: String,
        vars: Map<String, String>,
        mode: Mode,
        resolve: (String) -> List<InetAddress> = ::systemResolve,
    ): Decision {
        for (raw in targetUrls(script)) {
            val url = substituteVars(raw, vars)
            val reason = checkResolved(url, mode, resolve)
            if (reason != null) return Decision(allowed = false, reason = reason, source = raw)
        }
        return Decision.ALLOWED
    }

    /**
     * Syntactic twin of [evaluate] for save-time validation: no DNS, and hosts
     * that are still templated are left for dispatch to judge.
     */
    fun evaluateSyntax(script: String, vars: Map<String, String>, mode: Mode): Decision =
        refusalsBySyntax(script, vars, mode).firstOrNull() ?: Decision.ALLOWED

    /**
     * Every refusal [evaluateSyntax] would make, in script order, one per call
     * — the same judgement, for a caller that reports them all instead of
     * stopping at the first. Empty when every call is acceptable.
     */
    fun refusalsBySyntax(script: String, vars: Map<String, String>, mode: Mode): List<Decision> =
        targetUrls(script).withIndex().mapNotNull { (index, raw) ->
            checkSyntax(substituteVars(raw, vars), mode)?.let { Decision(allowed = false, reason = it, source = raw, callIndex = index) }
        }

    private fun systemResolve(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList()

    private data class Origin(val scheme: String, val authority: String, val host: String?)

    /**
     * An authority a URL parser could read more than one way: a backslash
     * (some parsers take it for `/`) or a control character. Lace hands such a
     * string to the agent as it is, so judging a host out of it here would be
     * judging a host the agent may never see.
     */
    private fun unparseableAuthority(authority: String): Boolean =
        authority.any { it == '\\' || it < ' ' || it == '\u007f' }

    private val NUMERIC_PART_RE = Regex("""0[xX][0-9a-fA-F]*|[0-9]+""")
    private val CANONICAL_OCTET_RE = Regex("""0|[1-9][0-9]{0,2}""")

    /**
     * A host made only of numbers that is not a canonical dotted quad: octal
     * (`0177.0.0.1`), hex (`0x7f.0.0.1`), a single number (`2130706433`), a
     * short form (`127.1`) or a trailing dot (`127.0.0.1.`). `inet_aton` and
     * the resolvers built on it read these as addresses — `0177.0.0.1` is
     * loopback there — while a strict parser reads them differently or not at
     * all, so no verdict made here would be about the address the agent
     * connects to. Refused instead.
     */
    private fun nonCanonicalIpv4(host: String): Boolean {
        val parts = host.removeSuffix(".").split('.')
        if (parts.any { !NUMERIC_PART_RE.matches(it) }) return false
        if (parts.size != 4 || host.endsWith('.')) return true
        return parts.any { !CANONICAL_OCTET_RE.matches(it) || it.toInt() > 255 }
    }

    private fun parseOrigin(url: String): Origin? {
        val m = ORIGIN_RE.find(url.trim()) ?: return null
        val scheme = m.groupValues[1].lowercase()
        val rawAuthority = m.groupValues[2]
        val authority = rawAuthority.substringAfterLast('@')
        val host = when {
            authority.isEmpty() -> null
            authority.startsWith("[") -> authority.substringAfter('[').substringBefore(']')
                .takeIf { it.isNotEmpty() }
            else -> authority.substringBefore(':').takeIf { it.isNotEmpty() }
        }
        return Origin(scheme, rawAuthority, host)
    }

    private fun parseLiteralIp(host: String): InetAddress? {
        val looksNumeric = host.all { it.isDigit() || it == '.' } || host.contains(':')
        if (!looksNumeric) return null
        return try {
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }
}
