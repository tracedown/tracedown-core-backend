package dev.tracedown.common.domain

import dev.tracedown.common.domain.dns.TxtLookup
import dev.tracedown.common.net.SsrfGuard
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/**
 * Verifies domain ownership via HTTP well-known file or DNS TXT record.
 *
 * HTTP (http-01): fetches `http://{domain}/.well-known/tracedown-verify.txt`
 * and checks that the response body contains the challenge token.
 *
 * DNS (dns-01): looks up TXT records for `_tracedown-verify.{domain}`
 * and checks that one of them matches `tracedown-verify={challenge}`.
 *
 * ## Redirects
 *
 * The domain is supplied by whoever is claiming it, so this fetch is the one
 * place a user chooses an address the platform then connects to. Following
 * redirects blindly turns that into a request-forgery primitive: the claimant
 * answers the well-known fetch with a 302 to an internal address and the
 * platform makes the call. The body is only compared against a challenge, so
 * nothing is *read back*, but the request still leaves — against a metadata
 * endpoint or an unauthenticated internal service, sending it is the whole
 * attack.
 *
 * So the client follows nothing ([OkHttpClient.followRedirects] and
 * [OkHttpClient.followSslRedirects] both off) and exactly one redirect is
 * honoured deliberately, by [sameHostUpgrade]: an `http` → `https` upgrade to
 * the **same host and the same path**. That is the one redirect that is not a
 * forgery — it names the host the claimant already named, on the path the
 * platform already chose — and it is the common case, since a site that
 * redirects everything to HTTPS would otherwise fail a check it used to pass.
 *
 * Everything else fails the attempt: a different host, a different path, a
 * scheme that is not an upgrade, a second redirect. DNS TXT (dns-01) makes no
 * outbound HTTP request at all and is unaffected — it remains the method to
 * prefer.
 */
class HttpDnsDomainVerifier(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build(),
    private val httpScheme: String = "https",
    private val httpPort: Int? = null,
    /**
     * Whether an http-01 target that resolves to an internal/private/loopback
     * address is allowed. Off in production — the claimant chooses the host and
     * the platform then connects to it, so a domain pointed at `127.0.0.1` or a
     * metadata IP would be a request-forgery primitive. Only local integration
     * tests, which necessarily verify against a loopback server, turn it on.
     */
    private val allowInternalTargets: Boolean = false,
) : DomainVerifier {

    private val log = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** The only port a "this site is HTTPS-only" redirect may name. */
        const val HTTPS_PORT = 443

        /**
         * The single failure message returned to the caller for every http-01
         * failure. It deliberately says nothing about the URL, the HTTP status,
         * or the connection error: the claimant supplies the host and the
         * platform connects to it, so a specific message ("HTTP 500 from …",
         * "connection refused") turns the verify endpoint into a probe oracle for
         * internal services. The detail is logged, not returned.
         */
        const val GENERIC_HTTP_FAILURE = "verification_failed"
    }

    override fun verify(domain: String, challenge: String, verificationType: String): VerificationResult {
        return when (verificationType) {
            "http-01" -> verifyHttp(domain, challenge)
            "dns-01" -> verifyDns(domain, challenge)
            else -> VerificationResult(verified = false, error = "Unknown verification type: $verificationType")
        }
    }

    private fun verifyHttp(domain: String, challenge: String): VerificationResult {
        // The claimant chose this host; refuse to connect to it if it names or
        // resolves to internal/private space, before any packet leaves.
        blockedTargetReason(domain)?.let { reason ->
            log.debug("http-01 verification refused for {}: {}", domain, reason)
            return VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE)
        }

        val host = if (httpPort != null) "$domain:$httpPort" else domain
        val url = "$httpScheme://$host${DomainChallenge.WELL_KNOWN_PATH}"
        return try {
            when (val first = fetch(url, challenge)) {
                is Attempt.Done -> first.result
                is Attempt.Redirect -> {
                    val upgraded = sameHostUpgrade(url, first.location)
                        ?: run {
                            log.debug("http-01 for {}: HTTP {} redirect not a same-host upgrade", domain, first.code)
                            return VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE)
                        }
                    when (val second = fetch(upgraded, challenge)) {
                        is Attempt.Done -> second.result
                        // One hop and no further: a redirect off the upgraded URL
                        // is no longer the "site forces HTTPS" case this allows.
                        is Attempt.Redirect -> {
                            log.debug("http-01 for {}: HTTP {} second redirect not followed", domain, second.code)
                            VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            log.debug("HTTP verification failed for {}: {}", domain, e.message)
            VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE)
        }
    }

    /**
     * Why [domain] must not be dialled, or null when it is a public target.
     * Runs the same internal-name and private/loopback/CGNAT/link-local address
     * checks the webhook SSRF guard uses, so the two agree on what "internal"
     * means. Skipped entirely when [allowInternalTargets] is set (local tests).
     */
    private fun blockedTargetReason(domain: String): String? {
        if (allowInternalTargets) return null
        if (SsrfGuard.isInternalHostname(domain)) return "internal_host"
        val addresses = try {
            InetAddress.getAllByName(domain).toList()
        } catch (e: Exception) {
            return "dns_resolution_failed"
        }
        if (addresses.isEmpty()) return "dns_no_addresses"
        if (addresses.any { SsrfGuard.isBlockedAddress(it) }) return "private_address"
        return null
    }

    /** One well-known fetch: either a verdict, or a redirect the caller may judge. */
    private sealed interface Attempt {
        data class Done(val result: VerificationResult) : Attempt
        data class Redirect(val code: Int, val location: String?) : Attempt
    }

    private fun fetch(url: String, challenge: String): Attempt {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use {
            if (it.isRedirect) {
                return Attempt.Redirect(it.code, it.header("Location"))
            }
            if (!it.isSuccessful) {
                log.debug("http-01 fetch of {} returned HTTP {}", url, it.code)
                return Attempt.Done(
                    VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE),
                )
            }

            val body = it.body?.string()?.trim() ?: ""
            return Attempt.Done(
                if (body == challenge) {
                    VerificationResult(verified = true)
                } else {
                    log.debug("http-01 challenge mismatch at {}", url)
                    VerificationResult(verified = false, error = GENERIC_HTTP_FAILURE)
                },
            )
        }
    }

    /**
     * The redirect target, but only when it is the same resource over HTTPS:
     * plain `http` upgrading to `https`, identical host, identical path, no
     * query. Null for anything else — including a target this cannot parse,
     * which is refused rather than guessed at.
     *
     * The port is required to be the HTTPS default. A redirect that also moves
     * the port is naming a different listener, and "the site forces HTTPS" does
     * not describe it.
     */
    private fun sameHostUpgrade(from: String, location: String?): String? {
        if (location.isNullOrBlank()) return null
        val source = from.toHttpUrlOrNull() ?: return null
        // Resolved against the source, so a relative Location is handled — and
        // a relative one can never change host, which is the point.
        val target = source.resolve(location) ?: return null

        if (source.scheme != "http" || target.scheme != "https") return null
        if (!target.host.equals(source.host, ignoreCase = true)) return null
        if (target.encodedPath != source.encodedPath) return null
        if (target.query != null) return null
        if (target.port != HTTPS_PORT) return null
        return target.toString()
    }

    private fun verifyDns(domain: String, challenge: String): VerificationResult {
        val lookupDomain = DomainChallenge.recordName(domain)
        val expectedValue = DomainChallenge.recordValue(challenge)
        return try {
            val values = TxtLookup.txt(lookupDomain)

            if (values.isEmpty()) {
                return VerificationResult(
                    verified = false,
                    error = "No TXT records found for $lookupDomain",
                )
            }

            if (values.any { it == expectedValue }) {
                VerificationResult(verified = true)
            } else {
                VerificationResult(
                    verified = false,
                    error = "No matching TXT record at $lookupDomain",
                )
            }
        } catch (e: javax.naming.NameNotFoundException) {
            VerificationResult(
                verified = false,
                error = "No DNS records found for $lookupDomain",
            )
        } catch (e: Exception) {
            log.debug("DNS verification failed for {}: {}", domain, e.message)
            VerificationResult(
                verified = false,
                error = "DNS lookup failed for $lookupDomain: ${e.message}",
            )
        }
    }
}
