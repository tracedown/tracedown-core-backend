package dev.tracedown.common.domain

import dev.tracedown.common.models.OrgDomains
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import java.util.UUID

/**
 * Anti-abuse policy for probes against unverified domains (spec §18.4;
 * limits: max 3 calls per script, no body saving, minimum 5-minute
 * interval). Only consulted when trustedDomainMode is off.
 *
 * A script is "covered" when every call URL's host is provably owned by the
 * org: exact domain match, or a wildcard-enabled domain suffix that isn't
 * excluded. Hosts that can't be resolved at dispatch time (URLs built from
 * `$$runVars`) count as uncovered — ownership can't be proven.
 */
object DomainPolicy {

    /** Minimum seconds between probes of a service targeting unverified domains. */
    const val MIN_INTERVAL_SECONDS = 300L

    /** Maximum calls per script when any target is unverified. */
    const val MAX_CALLS = 3

    /** Minimum schedule interval in minutes when any target is unverified. */
    const val MIN_INTERVAL_MINUTES = 5

    private val CALL_RE = Regex("""\b(?:get|post|put|patch|delete)\s*\(\s*"([^"]+)"""")

    // `includes(...)` is a substring content-oracle: against a domain the org
    // doesn't own it turns probes into a scraping primitive, so it is forbidden
    // whenever any target is unverified (spec §18.4). Detected textually, in step
    // with the rest of this policy — a stray match is failed safe (blocked).
    private val INCLUDES_RE = Regex("""\bincludes\s*\(""")

    // Idents may be dotted: raw scripts carry scoped refs ($p.baseUrl), the
    // scheduler's rewritten scripts carry underscored ones ($p_baseUrl) —
    // the caller's vars map decides which keys exist.
    private val VAR_RE = Regex("""\$\{?([a-zA-Z_][a-zA-Z0-9_.]*[a-zA-Z0-9_])\}?|\$([a-zA-Z_])\}?""")

    data class Evaluation(val covered: Boolean, val callCount: Int, val usesIncludes: Boolean = false)

    /** Must be called within a transaction. `vars` is a flat name→value map. */
    fun evaluate(script: String, vars: Map<String, String>, orgId: UUID): Evaluation {
        val usesIncludes = INCLUDES_RE.containsMatchIn(script)
        val urls = CALL_RE.findAll(script).map { it.groupValues[1] }.toList()
        if (urls.isEmpty()) return Evaluation(covered = true, callCount = 0, usesIncludes = usesIncludes)

        val hosts = urls.map { hostOf(substituteVars(it, vars)) }
        if (hosts.any { it == null }) return Evaluation(covered = false, callCount = urls.size, usesIncludes = usesIncludes)

        val domains = OrgDomains.selectAll()
            .where {
                (OrgDomains.organizationId eq orgId) and
                    (OrgDomains.status eq "verified") and
                    (OrgDomains.lapsed eq false) and
                    (OrgDomains.deleted eq false)
            }
            .map { Triple(it[OrgDomains.domain], it[OrgDomains.wildcardEnabled], it[OrgDomains.exceptions] ?: emptyList()) }

        val covered = hosts.all { host -> domains.any { covers(host!!, it.first, it.second, it.third) } }
        return Evaluation(covered = covered, callCount = urls.size, usesIncludes = usesIncludes)
    }

    /** Replaces `$ident` / `${ident}` with resolved variable values. */
    private fun substituteVars(url: String, vars: Map<String, String>): String {
        return VAR_RE.replace(url) { m ->
            val name = m.groupValues[1].ifEmpty { m.groupValues[2] }
            vars[name] ?: m.value
        }
    }

    /** Extracts the lowercase host; null when unresolvable (leftover `$`). */
    private fun hostOf(url: String): String? {
        if (url.contains('$')) return null
        return try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }

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
