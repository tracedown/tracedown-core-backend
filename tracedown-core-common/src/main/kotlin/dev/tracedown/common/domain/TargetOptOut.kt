package dev.tracedown.common.domain

import dev.tracedown.common.domain.dns.TxtLookup

/**
 * How a target asks not to be probed, in one place: the dispatch path reads
 * these and the documentation shows them. Nothing else may spell the record
 * out.
 *
 * A host that publishes a TXT record at `_tracedown-noprobe.<host>` — any
 * value, the record's presence is the whole statement — is not probed by this
 * platform. The record is the opposite side of [DomainChallenge]: that one is
 * an operator proving a zone is theirs, this one is an operator declining to
 * be monitored from here by someone who cannot prove that.
 *
 * ### Which names are asked
 * The exact host first, then each parent while the name still has more than
 * [MIN_PARENT_LABELS] labels, so a zone can opt out once at its apex and cover
 * everything under it:
 *
 *     api.eu.example.com -> api.eu.example.com, eu.example.com, example.com
 *
 * The walk stops at a two-label name — asking `com` is asking a registry a
 * question about somebody else's zone — and never exceeds [MAX_LOOKUPS]
 * queries per host, so a deeply nested name cannot turn one dispatch into a
 * long chain of DNS round-trips.
 *
 * ### What a silent resolver means
 * Nothing. A lookup that fails is read as "no record", the same stance
 * [dev.tracedown.common.net.ProbeTargetPolicy] takes on a name it cannot
 * resolve: a DNS outage must not quietly stop monitoring, because silence on a
 * monitoring dashboard reads as "all fine".
 */
object TargetOptOut {

    /** Label the TXT record sits under, below the host being probed. */
    const val RECORD_PREFIX = "_tracedown-noprobe"

    /** Most names asked about one host, however deep the host's name is. */
    const val MAX_LOOKUPS = 3

    /**
     * Labels a name must exceed before its parent is asked as well. Two, so the
     * walk ends at the registrable name and never climbs into a public suffix.
     */
    const val MIN_PARENT_LABELS = 2

    /** Where the record sits for [name]. */
    fun recordName(name: String): String = "$RECORD_PREFIX.$name"

    /**
     * The names asked about [host], nearest first. Empty for an address
     * literal: an IP has no zone to publish a record in, and asking would send
     * a reverse-shaped question nobody answers.
     */
    fun candidates(host: String): List<String> {
        val name = host.trim().trimEnd('.').lowercase()
        if (name.isEmpty() || isAddressLiteral(name)) return emptyList()

        val names = mutableListOf(name)
        var current = name
        while (names.size < MAX_LOOKUPS && current.count { it == '.' } + 1 > MIN_PARENT_LABELS) {
            current = current.substringAfter('.')
            names.add(current)
        }
        return names
    }

    /**
     * Whether [host] — or a parent of it the walk reaches — publishes the
     * record.
     *
     * @param lookup TXT values for a name; injectable for tests, and defaulting
     *   to the resolver the domain verifier uses. Every failure, including one
     *   the caller's own lookup throws, counts as no record.
     */
    fun published(host: String, lookup: (String) -> List<String> = TxtLookup::txtOrEmpty): Boolean =
        candidates(host).any { name ->
            try {
                lookup(recordName(name)).isNotEmpty()
            } catch (_: Exception) {
                false
            }
        }

    /** Whether [host] is an IPv4/IPv6 literal rather than a name in a zone. */
    fun isAddressLiteral(host: String): Boolean =
        host.contains(':') || host.isNotEmpty() && host.all { it.isDigit() || it == '.' }
}
