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

    /**
     * True when [host] names infrastructure that only ever exists inside a
     * private network (`localhost`, `*.internal`, `*.local`, …). Public because
     * probe-target policy applies the same list — the two guards must agree on
     * what "internal" means.
     */
    fun isInternalHostname(host: String): Boolean {
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
     * IPv6 unique-local (fc00::/7), IPv4 CGNAT (100.64.0.0/10), the
     * benchmarking and reserved ranges, the well-known instance-metadata address —
     * and every IPv6 form that carries an IPv4 address inside it, which is how
     * a blocked v4 target is otherwise reached through a v6 literal.
     */
    fun isBlockedAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isAnyLocalAddress ||
            addr.isLinkLocalAddress || addr.isMulticastAddress ||
            addr.isSiteLocalAddress
        ) {
            return true
        }
        val b = addr.address
        if (b.size == 4) return isBlockedV4(b)
        if (b.size == 16) {
            // IPv6 unique-local fc00::/7.
            if ((b[0].toInt() and 0xFE) == 0xFC) return true
            // 100::/64 — the discard-only prefix (RFC 6666).
            if ((b[0].toInt() and 0xFF) == 0x01 && b[1].toInt() == 0 &&
                (2..7).all { b[it].toInt() == 0 }
            ) {
                return true
            }
            // Run the embedded address through the whole test, not just the
            // extra ranges: 10/8 and its neighbours are the JDK's predicates,
            // which only see the address they are asked about.
            embeddedV4(b)?.let { v4 ->
                return runCatching { isBlockedAddress(InetAddress.getByAddress(v4)) }.getOrDefault(true)
            }
        }
        return false
    }

    /** IPv4 ranges no outbound request may reach, over and above the JDK's own predicates. */
    private fun isBlockedV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xFF
        val o1 = b[1].toInt() and 0xFF
        val o2 = b[2].toInt() and 0xFF
        val o3 = b[3].toInt() and 0xFF
        // 0.0.0.0/8 this-network, 100.64.0.0/10 CGNAT, 198.18.0.0/15
        // benchmarking, 240.0.0.0/4 reserved (255.255.255.255 included).
        if (o0 == 0) return true
        if (o0 == 100 && o1 in 64..127) return true
        if (o0 == 198 && (o1 == 18 || o1 == 19)) return true
        if (o0 >= 240) return true
        // The other address hosting providers answer instance metadata on,
        // next to 169.254.169.254 (link-local, already blocked).
        if (o0 == 168 && o1 == 63 && o2 == 129 && o3 == 16) return true
        return false
    }

    /**
     * The IPv4 address embedded in an IPv6 one, or null. Covers
     * IPv4-mapped (`::ffff:a.b.c.d`), IPv4-compatible (`::a.b.c.d`), 6to4
     * (`2002:AABB:CCDD::/48`), Teredo (`2001:0::/32`, where the client IPv4 is
     * the last four bytes, inverted) and NAT64 (`64:ff9b::/96` and
     * `64:ff9b:1::/48`).
     */
    private fun embeddedV4(b: ByteArray): ByteArray? {
        fun at(i: Int) = byteArrayOf(b[i], b[i + 1], b[i + 2], b[i + 3])
        val leading = (0..9).all { b[it].toInt() == 0 }
        // ::ffff:a.b.c.d and ::a.b.c.d (the latter only when something is set).
        if (leading) {
            val mapped = (b[10].toInt() and 0xFF) == 0xFF && (b[11].toInt() and 0xFF) == 0xFF
            if (mapped || (b[10].toInt() == 0 && b[11].toInt() == 0)) {
                val v4 = at(12)
                if (v4.any { it.toInt() != 0 }) return v4
            }
        }
        // 6to4: 2002:<v4>::/48.
        if ((b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x02) return at(2)
        // NAT64 well-known prefix 64:ff9b::/96 and the local prefix 64:ff9b:1::/48.
        if ((b[0].toInt() and 0xFF) == 0x00 && (b[1].toInt() and 0xFF) == 0x64 &&
            (b[2].toInt() and 0xFF) == 0xFF && (b[3].toInt() and 0xFF) == 0x9B
        ) {
            if ((b[4].toInt() == 0 && b[5].toInt() == 0) && (6..11).all { b[it].toInt() == 0 }) return at(12)
            if (b[4].toInt() == 0 && (b[5].toInt() and 0xFF) == 0x01) return at(12)
        }
        // Teredo 2001:0000::/32 — the client's IPv4 is the last 32 bits, inverted.
        if ((b[0].toInt() and 0xFF) == 0x20 && (b[1].toInt() and 0xFF) == 0x01 &&
            b[2].toInt() == 0 && b[3].toInt() == 0
        ) {
            return byteArrayOf(
                (b[12].toInt().inv() and 0xFF).toByte(),
                (b[13].toInt().inv() and 0xFF).toByte(),
                (b[14].toInt().inv() and 0xFF).toByte(),
                (b[15].toInt().inv() and 0xFF).toByte(),
            )
        }
        return null
    }
}
