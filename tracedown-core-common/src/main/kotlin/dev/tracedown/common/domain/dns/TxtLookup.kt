package dev.tracedown.common.domain.dns

import javax.naming.Context
import javax.naming.directory.InitialDirContext
import java.util.Hashtable

/**
 * Minimal DNS TXT/NS reader over JNDI — the same resolver the domain verifier
 * uses, shared so ownership checks and DNS-provider discovery can never
 * disagree about what a zone publishes.
 *
 * [txt] and [ns] let lookup failures out: a caller deciding whether a domain
 * still proves ownership must be able to tell "the record is gone" from "the
 * resolver did not answer". [txtOrEmpty] is for callers that only want to know
 * whether a record is there, where either answer means the same thing.
 */
object TxtLookup {

    /** TXT values for [name], unquoted. Empty when the name has no TXT records. */
    fun txt(name: String): List<String> = records(name, "TXT")

    /** NS values for [name], lowercased and without the trailing dot. */
    fun ns(name: String): List<String> = records(name, "NS").map { it.trim().trimEnd('.').lowercase() }

    /** [txt], with every failure — missing name included — reported as absent. */
    fun txtOrEmpty(name: String): List<String> = try {
        txt(name)
    } catch (_: Exception) {
        emptyList()
    }

    /** [ns], with every failure reported as absent. */
    fun nsOrEmpty(name: String): List<String> = try {
        ns(name)
    } catch (_: Exception) {
        emptyList()
    }

    private fun records(name: String, type: String): List<String> {
        val env = Hashtable<String, String>()
        env[Context.INITIAL_CONTEXT_FACTORY] = "com.sun.jndi.dns.DnsContextFactory"
        val attribute = InitialDirContext(env).getAttributes(name, arrayOf(type)).get(type)
            ?: return emptyList()
        return (0 until attribute.size()).map { attribute.get(it).toString().trim('"', ' ') }
    }
}
