package dev.tracedown.common.storage

import dev.tracedown.common.net.SsrfGuard
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration

/** Raised when a body store's endpoint resolves, or connects, to an address it may not reach. */
class StoreEndpointBlockedException(reason: String) : UnknownHostException(reason)

/**
 * Keeps a body store's S3 endpoint from being used to reach the platform's own
 * network (SSRF). A store's endpoint is typed in by a person with settings
 * access, and the gateway, the ingestor and the worker then dial it with the
 * store's credentials — without a guard it is a way to make those services
 * talk to a metadata service, a database or an internal admin port.
 *
 * Three layers:
 * - [validate], when a store is saved and again whenever a client is built:
 *   `https` only, no private, loopback, link-local or internal-only host.
 * - [GuardedDns], at connect time: every address a hostname resolves to is
 *   checked, so a name that resolved to something public when it was saved and
 *   to `10.0.0.5` now is refused (DNS rebinding).
 * - [ConnectedAddressCheck], on the socket itself: the address actually
 *   connected to is checked once more — this also covers an IP-literal host,
 *   which OkHttp connects to without asking [Dns].
 *
 * Redirects are never followed and no proxy is used ([httpClient]), so the
 * checked address is the only one the request goes to.
 *
 * Local development exception: when [allowLoopbackHttp] is set (any deployment
 * environment other than `production`), an endpoint whose host is `localhost`
 * or a loopback literal is allowed — over `http` too — so a MinIO on the same
 * machine works. It widens nothing else: a public name that resolves to
 * loopback is still refused.
 */
object StoreEndpointGuard {

    /** Returns null when [endpoint] may be used, otherwise a short reason. */
    fun validate(endpoint: String, allowLoopbackHttp: Boolean): String? {
        val uri = try {
            URI(endpoint.trim())
        } catch (_: Exception) {
            return "malformed_url"
        }
        val scheme = uri.scheme?.lowercase() ?: return "malformed_url"
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return "no_host"
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return "malformed_url"
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return "malformed_url"

        val loopback = isLoopbackHost(host)
        if (loopback) {
            if (!allowLoopbackHttp) return "private_address"
            return if (scheme == "https" || scheme == "http") null else "scheme_not_https"
        }
        if (scheme != "https") return "scheme_not_https"
        if (SsrfGuard.isInternalHostname(host)) return "internal_host"
        literalAddress(host)?.let { if (SsrfGuard.isBlockedAddress(it)) return "private_address" }
        return null
    }

    /** `localhost` or a loopback IP literal — the only hosts the development exception covers. */
    fun isLoopbackHost(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").trimEnd('.').lowercase()
        if (h == "localhost") return true
        return literalAddress(h)?.isLoopbackAddress == true
    }

    private fun literalAddress(host: String): InetAddress? {
        val h = host.removePrefix("[").removeSuffix("]")
        val numeric = (h.isNotEmpty() && h.all { it.isDigit() || it == '.' } && h.contains('.')) || h.contains(':')
        if (!numeric) return null
        return try {
            InetAddress.getByName(h)
        } catch (_: Exception) {
            null
        }
    }

    /** Whether a connection to [address] for [host] is allowed under the same rules as [validate]. */
    fun addressAllowed(host: String, address: InetAddress, allowLoopbackHttp: Boolean): Boolean {
        if (allowLoopbackHttp && isLoopbackHost(host)) return address.isLoopbackAddress
        return !SsrfGuard.isBlockedAddress(address)
    }

    /**
     * Resolves through [resolver] and refuses the whole lookup when any address is
     * off limits — one private answer among public ones is still a way in.
     */
    class GuardedDns(
        private val allowLoopbackHttp: Boolean,
        private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!(allowLoopbackHttp && isLoopbackHost(hostname)) && SsrfGuard.isInternalHostname(hostname)) {
                throw StoreEndpointBlockedException("internal host $hostname")
            }
            val addresses = resolver(hostname)
            if (addresses.isEmpty()) throw UnknownHostException(hostname)
            for (address in addresses) {
                if (!addressAllowed(hostname, address, allowLoopbackHttp)) {
                    throw StoreEndpointBlockedException("$hostname resolves to a blocked address")
                }
            }
            return addresses
        }
    }

    /** Network interceptor: checks the address the socket is actually connected to. */
    class ConnectedAddressCheck(private val allowLoopbackHttp: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val connection = chain.connection() ?: throw IOException("no connection")
            val address = connection.route().socketAddress.address
            val host = chain.request().url.host
            if (address == null || !addressAllowed(host, address, allowLoopbackHttp)) {
                throw StoreEndpointBlockedException("connection to a blocked address refused")
            }
            return chain.proceed(chain.request())
        }
    }

    /** The HTTP client every body-store S3 client uses: guarded DNS and socket, no redirects, no proxy. */
    fun httpClient(
        timeoutSeconds: Long,
        allowLoopbackHttp: Boolean,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): OkHttpClient {
        val timeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))
        return OkHttpClient.Builder()
            .dns(GuardedDns(allowLoopbackHttp, resolver))
            .addNetworkInterceptor(ConnectedAddressCheck(allowLoopbackHttp))
            .followRedirects(false)
            .followSslRedirects(false)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(timeout)
            .readTimeout(timeout)
            .writeTimeout(timeout)
            .callTimeout(timeout.multipliedBy(2))
            .build()
    }

    /** True when [t] or anything in its cause chain is a guard refusal. */
    fun isBlocked(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is StoreEndpointBlockedException) return true
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }
}
