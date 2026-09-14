package dev.tracedown.common.storage

import dev.tracedown.common.net.SsrfGuard
import org.apache.http.HttpHost
import org.apache.http.conn.DnsResolver
import org.apache.http.conn.socket.ConnectionSocketFactory
import org.apache.http.conn.ssl.SSLConnectionSocketFactory
import org.apache.http.protocol.HttpContext
import software.amazon.awssdk.http.SdkHttpClient
import software.amazon.awssdk.http.apache.ApacheHttpClient
import software.amazon.awssdk.http.apache.ProxyConfiguration
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
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
 *   to `10.0.0.5` now is refused (DNS rebinding). The connection manager asks
 *   it for every host, IP literals included, so nothing reaches a socket
 *   without passing through here.
 * - [GuardedSocketFactory], on the socket itself: the address actually being
 *   connected to is checked once more, immediately before the connect. This is
 *   the TLS socket factory, so it covers every endpoint the guard actually
 *   protects — without `allowPrivate` [validate] admits `https` and nothing
 *   else, and with it nothing is blocked in the first place.
 *
 * Redirects are never followed (the SDK's Apache client calls
 * `disableRedirectHandling`) and no proxy is used ([httpClient]), so the checked
 * address is the only one the request goes to.
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
        // A bare `storage` resolves through whatever search domain the container
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
    ) : DnsResolver {
        fun lookup(hostname: String): List<InetAddress> {
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

        override fun resolve(host: String): Array<InetAddress> = lookup(host).toTypedArray()
    }

    /**
     * The TLS socket factory, checking the address the socket is about to be
     * connected to. The connection manager has already resolved the host through
     * [GuardedDns]; this sees the very address that is handed to `connect`, so a
     * route computed some other way is still refused.
     */
    class GuardedSocketFactory(
        private val allowPrivate: Boolean,
        private val delegate: ConnectionSocketFactory = SSLConnectionSocketFactory.getSystemSocketFactory(),
    ) : ConnectionSocketFactory {

        override fun createSocket(context: HttpContext?): Socket = delegate.createSocket(context)

        override fun connectSocket(
            connectTimeout: Int,
            sock: Socket?,
            host: HttpHost,
            remoteAddress: InetSocketAddress,
            localAddress: InetSocketAddress?,
            context: HttpContext?,
        ): Socket {
            val address = remoteAddress.address
            if (address == null || !addressAllowed(address, allowPrivate)) {
                throw StoreEndpointBlockedException("connection to a blocked address refused")
            }
            return delegate.connectSocket(connectTimeout, sock, host, remoteAddress, localAddress, context)
        }
    }

    /** The HTTP client every body-store S3 client uses: guarded DNS and socket, no redirects, no proxy. */
    fun httpClient(
        timeoutSeconds: Long,
        allowPrivate: Boolean,
        resolver: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
    ): SdkHttpClient {
        val timeout = Duration.ofSeconds(timeoutSeconds.coerceAtLeast(1))
        return ApacheHttpClient.builder()
            .dnsResolver(GuardedDns(allowPrivate, resolver))
            .socketFactory(GuardedSocketFactory(allowPrivate))
            .connectionTimeout(timeout)
            .socketTimeout(timeout)
            .connectionAcquisitionTimeout(timeout)
            // Roughly what the pool this replaces kept an idle connection for.
            .connectionTimeToLive(POOL_TTL)
            // The reaper is a process-wide registry holding a strong reference to
            // every connection manager ever handed to it. A client here can be
            // built per store — and, for an agent's own corner of one, per result
            // — so registering them would grow without bound. Connections are
            // instead revalidated when they are leased.
            .useIdleConnectionReaper(false)
            .proxyConfiguration(
                ProxyConfiguration.builder()
                    .useSystemPropertyValues(false)
                    .useEnvironmentVariableValues(false)
                    .build(),
            )
            .build()
    }

    /** How long a pooled connection may be reused before it is dropped. */
    private val POOL_TTL: Duration = Duration.ofMinutes(5)

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
