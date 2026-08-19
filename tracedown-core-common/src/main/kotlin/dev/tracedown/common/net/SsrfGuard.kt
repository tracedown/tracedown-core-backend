package dev.tracedown.common.net

import java.net.IDN
import java.net.InetAddress
import java.net.URI

/**
 * Guards outbound, user-supplied HTTP targets (webhook URLs) against SSRF.
 *
 * Two layers:
 * - [validateUrlSyntax] runs at write-time (DTO validation, no DNS): enforces
 *   an https scheme and rejects hosts that are literal private/loopback IPs or
 *   internal names. It is intentionally lenient about templated hosts
 *   (`$o.…` / `$h.…`), which cannot be resolved until delivery.
 * - [assertAllowed] runs at delivery-time on the fully-resolved URL: re-checks
 *   the scheme, resolves the host, and rejects if ANY resolved address is
 *   loopback / link-local / private / unique-local / CGNAT / wildcard, or the
 *   host is an internal name. This is the authoritative check and also the hop
 *   re-validation point when redirects are disabled.
 */
object SsrfGuard {

    class BlockedException(val reason: String) : RuntimeException(reason)

    /** Host suffixes that only ever name internal infrastructure. */
    private val INTERNAL_SUFFIXES = listOf(
        ".railway.internal", ".internal", ".local", ".localhost", ".cluster.local",
    )

    private val INTERNAL_EXACT = setOf("localhost")

    /**
     * Write-time syntactic check. Returns an error code or null.
     * `field` names the offending field for the returned code.
     */
    fun validateUrlSyntax(field: String, url: String?): String? {
        if (url == null) return null

        // Enforce the scheme by prefix so it holds even for a templated URL that
        // the URI parser can't fully model.
        if (!url.trimStart().lowercase().startsWith("https://")) return "invalid_$field"

        // A URL carrying a variable ref (`$o.key` / `$h.key`) can't be fully judged
        // until delivery resolves it — the authoritative DNS-based check runs
        // then (assertAllowed). Here we only guarantee the https scheme.
        val templated = url.contains('$')

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            return if (templated) null else "invalid_$field"
        }
        val host = uri.host ?: return if (templated) null else "invalid_$field"
        if (host.contains('$')) return null // templated host — deferred

        if (isInternalHostname(host)) return "invalid_$field"
        // If the host is a literal IP, reject it here when it is in a blocked range.
        val literal = parseLiteralIp(host)
        if (literal != null && isBlockedAddress(literal)) return "invalid_$field"
        return null
    }

    /**
     * Delivery-time check on a concrete (fully-resolved) URL. Throws
     * [BlockedException] if the target is not a safe public https endpoint.
     * Returns the resolved addresses so the caller may pin them if desired.
     */
    fun assertAllowed(url: String): List<InetAddress> {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw BlockedException("malformed_url")
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw BlockedException("scheme_not_https")
        }
        val host = uri.host ?: throw BlockedException("no_host")
        if (isInternalHostname(host)) throw BlockedException("internal_host")

        val addresses = try {
            InetAddress.getAllByName(host).toList()
        } catch (e: Exception) {
            throw BlockedException("dns_resolution_failed")
        }
        if (addresses.isEmpty()) throw BlockedException("dns_no_addresses")
        for (addr in addresses) {
            if (isBlockedAddress(addr)) throw BlockedException("private_address")
        }
        return addresses
    }

    private fun isInternalHostname(host: String): Boolean {
        val normalized = try {
            IDN.toASCII(host).trimEnd('.').lowercase()
        } catch (_: Exception) {
            host.trimEnd('.').lowercase()
        }
        if (normalized in INTERNAL_EXACT) return true
        return INTERNAL_SUFFIXES.any { normalized.endsWith(it) }
    }

    private fun parseLiteralIp(host: String): InetAddress? {
        // Strip IPv6 brackets. Only treat as literal if it parses without DNS.
        val h = host.removePrefix("[").removeSuffix("]")
        val looksNumeric = h.all { it.isDigit() || it == '.' } ||
            h.contains(':') // IPv6
        if (!looksNumeric) return null
        return try {
            InetAddress.getByName(h)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * True when [addr] must never be reached from an outbound webhook: any
     * form of loopback, link-local, wildcard, multicast, RFC1918/site-local,
     * IPv6 unique-local (fc00::/7), or IPv4 CGNAT (100.64.0.0/10).
     */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress ||
            addr.isSiteLocalAddress
        ) {
            return true
        }
        val b = addr.address
        if (b.size == 4) {
            val o0 = b[0].toInt() and 0xFF
            val o1 = b[1].toInt() and 0xFF
            // 0.0.0.0/8 (this-network), 100.64.0.0/10 (CGNAT).
            if (o0 == 0) return true
            if (o0 == 100 && o1 in 64..127) return true
        } else if (b.size == 16) {
            // IPv6 unique-local fc00::/7.
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
        }
        return false
    }
}
