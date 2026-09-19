package dev.tracedown.gateway.util

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.util.AttributeKey

/** Where the resolved address lives for the duration of one call. */
private val ClientAddressKey = AttributeKey<String>("ClientAddress")

/**
 * Resolves the caller's address once per request, so everything that needs it
 * — the rate-limit key, the address recorded on a session — reads the same
 * value and the two can never disagree.
 *
 * The TCP peer is not that address. A deployment behind a reverse proxy sees
 * the proxy on every connection, so recording the peer records the proxy: one
 * address for the whole world, which is useless on a session list and worse
 * than useless as evidence. [resolveClientIp] walks the forwarded chain instead
 * and takes the hop [trustedProxies] out, which a client cannot move.
 *
 * Installed before the rate limiter on purpose, and unconditionally: the
 * limiter skips some paths entirely and can be switched off altogether, and the
 * address still has to be there for the routes that record it.
 */
fun Application.installClientAddress(trustedProxies: Int) {
    install(createApplicationPlugin("ClientAddress") {
        onCall { call ->
            call.attributes.put(
                ClientAddressKey,
                resolveClientIp(
                    xff = call.request.headers["X-Forwarded-For"],
                    // The literal peer — `origin.remoteHost` may reverse-resolve
                    // DNS, which stalls the event loop and yields a name the
                    // caller controls.
                    directPeer = call.request.local.remoteAddress,
                    trustedProxies = trustedProxies,
                ),
            )
        }
    })
}

/**
 * The caller's address for this call: the real client when the deployment sits
 * behind the configured number of reverse proxies, the direct peer otherwise.
 *
 * Falls back to the direct peer if [installClientAddress] never ran, so a call
 * assembled outside the server pipeline still answers with something true.
 */
fun ApplicationCall.clientIp(): String =
    attributes.getOrNull(ClientAddressKey) ?: request.local.remoteAddress
