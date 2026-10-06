package dev.tracedown.common.agents

/**
 * What an agent's slug may be.
 *
 * The slug is the agent's identity: the CN and DNS SAN of the certificate it
 * enrols with, the path it writes bodies under, and the name every list and
 * channel keys it by. Lowercase letters, digits and hyphens, starting with a
 * letter or digit, 64 characters at most. Every way of minting a bootstrap
 * token — the API and the gateway's `--agent-bootstrap` — holds a slug to this.
 */
object AgentSlugs {

    private val PATTERN = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

    fun isValid(slug: String): Boolean = PATTERN.matches(slug)
}
