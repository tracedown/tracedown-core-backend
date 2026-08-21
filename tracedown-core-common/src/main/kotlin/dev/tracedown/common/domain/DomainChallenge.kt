package dev.tracedown.common.domain

/**
 * Where a domain's ownership challenge lives, in one place: the verifier reads
 * these and the instructions show them. Nothing else may spell the record out.
 */
object DomainChallenge {

    /** Label the TXT record sits under, below the domain being verified. */
    const val RECORD_PREFIX = "_tracedown-verify"

    /** Prefix of the TXT value, so the record is self-describing in a zone. */
    const val VALUE_PREFIX = "tracedown-verify="

    /** Path fetched for `http-01`, below the domain being verified. */
    const val WELL_KNOWN_PATH = "/.well-known/tracedown-verify.txt"

    fun recordName(domain: String): String = "$RECORD_PREFIX.$domain"

    fun recordValue(challenge: String): String = "$VALUE_PREFIX$challenge"
}
