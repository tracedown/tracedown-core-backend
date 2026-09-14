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
 * access, and the gateway and the ingestor then dial it with the store's
 * credentials — without a guard it is a way to make those services talk to a
 * metadata service, a database or an internal admin port.
 *
 * Three layers:
 * - [validate], when a store is saved and again whenever a client is built:
 *   `https` only, no private, loopback, link-local or internal-only host, and
 *   no single-label host (which resolves through a search domain and so means
 *   something different in every network).
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
 * **Private endpoints.** A self-hoster whose object store sits on the same
 * private network as the platform sets `BODY_STORE_PRIVATE_ENDPOINTS=true` on
 * the gateway and the ingestor; [allowPrivate] then carries that decision
 * through all three layers, allowing `http://`, private, CGNAT, loopback and
 * internal-suffix hosts alike. It is off by default, and an operator who turns
 * it on is deciding that anything a settings-writer can type in this
 * deployment may be dialled.
 */
object StoreEndpointGuard {

    /**
     * Returns null when [endpoint] may be used, otherwise a short reason —
     * one of `malformed_url`, `no_host`, `has_path`, `scheme_not_https`,
     * `single_label_host`, `internal_host`, `private_address`.
     */
    fun validate(endpoint: String, allowPrivate: Boolean): String? {
        val uri = try {
            URI(endpoint.trim())
        } catch (_: Exception) {
            return "malformed_url"
        }
        val scheme = uri.scheme?.lowercase() ?: return "malformed_url"
        val host = uri.host?.removePrefix("[")?.removeSuffix("]")?.takeIf { it.isNotBlank() } ?: return "no_host"
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return "malformed_url"
        if (!uri.rawPath.isNullOrEmpty() && uri.rawPath != "/") return "has_path"

        if (allowPrivate) {
            return if (scheme == "https" || scheme == "http") null else "scheme_not_https"
        }
        if (scheme != "https") return "scheme_not_https"
        if (SsrfGuard.isInternalHostname(host)) return "internal_host"
        val literal = literalAddress(host)
        if (literal != null) {
            if (SsrfGuard.isBlockedAddress(literal)) return "private_address"
            return null
        }
        // A bare `minio` resolves through whatever search domain the container
        // runtime hands out — a different machine in every network, and inside
        // a compose stack always an internal one.
        if (!host.trimEnd('.').contains('.')) return "single_label_host"
        return null
    }

    /** `localhost` or a loopback IP literal. */
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

    /** Whether a connection to [address] is allowed under the same rules as [validate]. */
    fun addressAllowed(address: InetAddress, allowPrivate: Boolean): Boolean =
        allowPrivate || !SsrfGuard.isBlockedAddress(address)

    /**
     * Resolves through [resolver] and refuses the whole lookup when any address is
     * off limits — one private answer among public ones is still a way in.
     */
    class GuardedDns(
        private val allowPrivate: Boolean,
        private val resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ) : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (!allowPrivate && SsrfGuard.isInternalHostname(hostname)) {
                throw StoreEndpointBlockedException("internal host $hostname")
            }
            val addresses = resolver(hostname)
            if (addresses.isEmpty()) throw UnknownHostException(hostname)
            for (address in addresses) {
                if (!addressAllowed(address, allowPrivate)) {
                    throw StoreEndpointBlockedException("$hostname resolves to a blocked address")
                }
            }
            return addresses
        }
    }

    /** Network interceptor: checks the address the socket is actually connected to. */
    class ConnectedAddressCheck(private val allowPrivate: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val connection = chain.connection() ?: throw IOException("no connection")
            val address = connection.route().socketAddress.address
            if (address == null || !addressAllowed(address, allowPrivate)) {
                throw StoreEndpointBlockedException("connection to a blocked address refused")
            }
            return chain.proceed(chain.request())
        }
    }

    /** The HTTP client every body-store S3 client uses: guarded DNS and socket, no redirects, no proxy. */
    fun httpClient(
        timeoutSeconds: Long,
        allowPrivate: Boolean,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): OkHttpClient {
        val timeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))
        return OkHttpClient.Builder()
            .dns(GuardedDns(allowPrivate, resolver))
            .addNetworkInterceptor(ConnectedAddressCheck(allowPrivate))
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
