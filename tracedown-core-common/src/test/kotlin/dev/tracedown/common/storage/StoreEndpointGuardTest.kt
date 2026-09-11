package dev.tracedown.common.storage

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

/**
 * A body store's endpoint is typed in by a person and then dialled by the
 * platform with the store's credentials, so it must never become a way into the
 * platform's own network: refused when saved, refused again at connect time,
 * and never redirected anywhere else.
 */
class StoreEndpointGuardTest {

    @Test
    fun `a public https endpoint is accepted`() {
        assertNull(StoreEndpointGuard.validate("https://s3.eu-central-1.amazonaws.com", allowLoopbackHttp = false))
        assertNull(StoreEndpointGuard.validate("https://acct.r2.cloudflarestorage.com/", allowLoopbackHttp = false))
    }

    @Test
    fun `private, link-local and CGNAT literals are refused`() {
        for (endpoint in listOf(
            "https://10.0.0.5", "https://192.168.1.10:9000", "https://172.16.0.1",
            "https://169.254.169.254", "https://100.64.0.1", "https://[fd00::1]", "https://0.0.0.0",
        )) {
            assertEquals("private_address", StoreEndpointGuard.validate(endpoint, allowLoopbackHttp = true), endpoint)
        }
    }

    @Test
    fun `internal-only names are refused`() {
        assertEquals("internal_host", StoreEndpointGuard.validate("https://minio.railway.internal", false))
        assertEquals("internal_host", StoreEndpointGuard.validate("https://metadata.internal", false))
        assertEquals("internal_host", StoreEndpointGuard.validate("https://store.local", false))
    }

    @Test
    fun `plain http is refused except for a local endpoint outside production`() {
        assertEquals("scheme_not_https", StoreEndpointGuard.validate("http://s3.example.com", allowLoopbackHttp = true))
        assertNull(StoreEndpointGuard.validate("http://localhost:9000", allowLoopbackHttp = true))
        assertNull(StoreEndpointGuard.validate("http://127.0.0.1:9000", allowLoopbackHttp = true))
        assertEquals("private_address", StoreEndpointGuard.validate("http://localhost:9000", allowLoopbackHttp = false))
        assertEquals("private_address", StoreEndpointGuard.validate("https://127.0.0.1", allowLoopbackHttp = false))
    }

    @Test
    fun `credentials, paths and queries in the endpoint are refused`() {
        assertEquals("malformed_url", StoreEndpointGuard.validate("https://user:pw@s3.example.com", false))
        assertEquals("malformed_url", StoreEndpointGuard.validate("https://s3.example.com/bucket", false))
        assertEquals("malformed_url", StoreEndpointGuard.validate("https://s3.example.com?x=1", false))
        assertEquals("no_host", StoreEndpointGuard.validate("https:///nohost", false))
    }

    @Test
    fun `a name that resolves to a private address is refused at connect time`() {
        val private = InetAddress.getByName("10.1.2.3")
        val public = InetAddress.getByName("93.184.216.34")
        val rebinding = StoreEndpointGuard.GuardedDns(allowLoopbackHttp = false) { listOf(private) }
        assertThrows(StoreEndpointBlockedException::class.java) { rebinding.lookup("store.example.com") }
        // One private answer among public ones is still a way in.
        val mixed = StoreEndpointGuard.GuardedDns(allowLoopbackHttp = false) { listOf(public, private) }
        assertThrows(StoreEndpointBlockedException::class.java) { mixed.lookup("store.example.com") }
        val clean = StoreEndpointGuard.GuardedDns(allowLoopbackHttp = false) { listOf(public) }
        assertEquals(listOf(public), clean.lookup("store.example.com"))
    }

    @Test
    fun `the local exception does not cover a public name resolving to loopback`() {
        val dns = StoreEndpointGuard.GuardedDns(allowLoopbackHttp = true) { listOf(InetAddress.getByName("127.0.0.1")) }
        assertThrows(StoreEndpointBlockedException::class.java) { dns.lookup("evil.example.com") }
        assertEquals(1, dns.lookup("localhost").size)
    }

    @Test
    fun `a redirect from the store is not followed`() {
        val targetHits = AtomicInteger()
        val originHits = AtomicInteger()
        val target = server { exchange ->
            targetHits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        val origin = server { exchange ->
            originHits.incrementAndGet()
            exchange.responseHeaders.add("Location", "http://127.0.0.1:${target.address.port}${exchange.requestURI}")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        try {
            val client = BodyStorageClient(
                s3Config = S3Config("http://127.0.0.1:${origin.address.port}", "key", "secret", timeoutSeconds = 5),
                confinement = BodyConfinement(s3Bucket = "bucket"),
                httpClient = StoreEndpointGuard.httpClient(timeoutSeconds = 5, allowLoopbackHttp = true),
            )
            val error = client.probe()
            assertNotNull(error, "a redirect is a failed probe, not a success")
            assertTrue(originHits.get() >= 1, "the store was asked")
            assertEquals(0, targetHits.get(), "the redirect target was never contacted")
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `a loopback literal is refused on the socket outside development`() {
        val hits = AtomicInteger()
        val origin = server { exchange ->
            hits.incrementAndGet()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        try {
            val client = BodyStorageClient(
                s3Config = S3Config("http://127.0.0.1:${origin.address.port}", "key", "secret", timeoutSeconds = 5),
                confinement = BodyConfinement(s3Bucket = "bucket"),
                httpClient = StoreEndpointGuard.httpClient(timeoutSeconds = 5, allowLoopbackHttp = false),
            )
            assertEquals("blocked_endpoint", client.probe())
            assertEquals(0, hits.get(), "no request reached the blocked address")
        } finally {
            origin.stop(0)
        }
    }

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit): HttpServer =
        HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/") { handler(it) }
            start()
        }
}
