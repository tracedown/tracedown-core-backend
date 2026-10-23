package dev.tracedown.common.domain

import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.net.ProbeTargetPolicy
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import java.util.UUID

/**
 * Anti-abuse policy for probes against unverified domains (spec §18.4;
 * limits: max 3 calls per script, no body saving, minimum 5-minute
 * interval). Only consulted when trustedDomainMode is off.
 *
 * A script is "covered" when every call URL's host is provably owned by the
 * org: exact domain match, or a wildcard-enabled domain suffix that isn't
 * excluded. Hosts that can't be resolved before the run (URLs built from
 * `$$runVars`, or from a variable with no value) count as uncovered —
 * ownership can't be proven.
 */
object DomainPolicy {

    /** Minimum seconds between probes of a service targeting unverified domains. */
    const val MIN_INTERVAL_SECONDS = 300L

    /** Maximum calls per script when any target is unverified. */
    const val MAX_CALLS = 3

    /** Minimum schedule interval in minutes when any target is unverified. */
    const val MIN_INTERVAL_MINUTES = 5

    // `includes(...)` is a substring content-oracle: against a domain the org
    // doesn't own it turns probes into a scraping primitive, so it is forbidden
    // whenever any target is unverified (spec §18.4). Detected textually, in step
    // with the rest of this policy — a stray match is failed safe (blocked).
    private val INCLUDES_RE = Regex("""\bincludes\s*\(""")

    /**
     * [unverifiedHosts] names what keeps [covered] false: each target host no
     * verified domain covers, or the URL as written when its host could not be
     * resolved (a variable with no value) or was built from a concealed one.
     * Distinct, in script order, empty when covered — the client shows them
     * beside the setting they restrict.
     */
    data class Evaluation(
        val covered: Boolean,
        val callCount: Int,
        val usesIncludes: Boolean = false,
        val unverifiedHosts: List<String> = emptyList(),
    )

    /**
     * Must be called within a transaction. [vars] is keyed as injected
     * (`p_baseUrl`), and the script's calls, targets and references are read by
     * [ProbeTargetPolicy] — the same reading the address policy makes — so the
     * raw script at save time and the rewritten one at dispatch judge the same
     * hosts.
     *
     * [concealed] names the entries of [vars] whose values the caller of the
     * evaluation may not be shown (decrypted variables). They are used to judge
     * like any other, but a host built from one is never named in
     * [Evaluation.unverifiedHosts]: the call's URL is listed as the script
     * spells it instead, the same way an unresolved host is.
     */
    fun evaluate(
        script: String,
        vars: Map<String, String>,
        orgId: UUID,
        concealed: Set<String> = emptySet(),
    ): Evaluation = evaluateCalls(ProbeTargetPolicy.targetUrls(script), usesIncludes(script), vars, orgId, concealed)

    /** Whether [script] uses `includes(...)` — read as [evaluate] reads it. */
    fun usesIncludes(script: String): Boolean = INCLUDES_RE.containsMatchIn(script)

    /**
     * [evaluate] over a given list of call URLs as the script writes them —
     * for a caller that judges only some of a script's calls (the ones whose
     * hosts it can see). Must be called within a transaction.
     */
    fun evaluateCalls(
        urls: List<String>,
        usesIncludes: Boolean,
        vars: Map<String, String>,
        orgId: UUID,
        concealed: Set<String> = emptySet(),
    ): Evaluation {
        if (urls.isEmpty()) return Evaluation(covered = true, callCount = 0, usesIncludes = usesIncludes)

        val hosts = urls.map { hostOf(it, vars) }
        if (hosts.any { it == null }) {
            val unresolved = urls.filterIndexed { i, _ -> hosts[i] == null }.distinct()
            return Evaluation(
                covered = false,
                callCount = urls.size,
                usesIncludes = usesIncludes,
                unverifiedHosts = unresolved,
            )
        }

        val domains = verifiedDomains(orgId)

        // A host is named only when the script and the shown variables alone
        // spell it (a concealed value in the path hides nothing of the host);
        // otherwise the call's own text stands in for it.
        val shown = vars - concealed
        val uncovered = urls.indices
            .filter { i -> domains.none { covers(hosts[i]!!, it.first, it.second, it.third) } }
            .map { i ->
                val host = hosts[i]!!
                if (concealed.isEmpty() || hostOf(urls[i], shown) == host) host else urls[i]
            }
            .distinct()
        return Evaluation(
            covered = uncovered.isEmpty(),
            callCount = urls.size,
            usesIncludes = usesIncludes,
            unverifiedHosts = uncovered,
        )
    }

    /**
     * Whether any domain the org has proven it owns covers [host] — the same
     * rows and the same wildcard/exception semantics [evaluate] applies, so a
     * host can never count as owned by one caller and unowned by another. A
     * lapsed, unverified or deleted domain proves nothing and is not consulted.
     *
     * Must be called within a transaction.
     */
    fun verifiedCovers(host: String, orgId: UUID): Boolean =
        verifiedDomains(orgId).any { covers(host.lowercase(), it.first, it.second, it.third) }

    /** The org's proven domains: `domain`, `wildcardEnabled`, `exceptions`. */
    private fun verifiedDomains(orgId: UUID): List<Triple<String, Boolean, List<String>>> =
        OrgDomains.selectAll()
            .where {
                (OrgDomains.organizationId eq orgId) and
                    (OrgDomains.status eq "verified") and
                    (OrgDomains.lapsed eq false) and
                    (OrgDomains.deleted eq false)
            }
            .map { Triple(it[OrgDomains.domain], it[OrgDomains.wildcardEnabled], it[OrgDomains.exceptions] ?: emptyList()) }

    /** The host [url] targets once [vars] are substituted; null while it is still assembled at runtime. */
    private fun hostOf(url: String, vars: Map<String, String>): String? =
        ProbeTargetPolicy.hostOf(ProbeTargetPolicy.substituteVars(url, vars))

    /**
     * Whether a verified [domain] row covers [host]: an exact match, or — with
     * [wildcard] — any subdomain not carved out by [exceptions] (each exception
     * excludes itself and its subdomains). Public because host-classification
     * consumers outside this policy must match its semantics exactly.
     */
    fun covers(host: String, domain: String, wildcard: Boolean, exceptions: List<String>): Boolean {
        val d = domain.lowercase()
        if (host == d) return true
        if (!wildcard || !host.endsWith(".$d")) return false
        return exceptions.none { exception ->
            val e = exception.lowercase().removePrefix("*.")
            host == e || host.endsWith(".$e")
        }
    }
    /**
     * Conservative lower bound (minutes) between fires of a 5-field cron.
     * Only the minute field matters for the 5-minute rule: hour-or-coarser
     * schedules always pass. Unparseable minute specs assume the worst (1).
     */
    fun minIntervalMinutes(cron: String?): Int {
        val minute = cron?.trim()?.split(Regex("\\s+"))?.firstOrNull() ?: return Int.MAX_VALUE
        return when {
            minute == "*" -> 1
            minute.contains('/') -> minute.substringAfter('/').toIntOrNull() ?: 1
            minute.contains(',') -> {
                val values = minute.split(',').mapNotNull { it.toIntOrNull() }.sorted()
                if (values.size < 2) 60
                else (values.zipWithNext { a, b -> b - a } + (values.first() + 60 - values.last())).min()
            }
            minute.contains('-') -> 1
            else -> 60
        }
    }

}
