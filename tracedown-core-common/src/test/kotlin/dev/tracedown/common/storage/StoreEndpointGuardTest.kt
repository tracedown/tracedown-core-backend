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
        assertNull(StoreEndpointGuard.validate("https://s3.eu-central-1.amazonaws.com", allowPrivate = false))
        assertNull(StoreEndpointGuard.validate("https://acct.r2.cloudflarestorage.com/", allowPrivate = false))
    }

    @Test
    fun `private, link-local, CGNAT and reserved literals are refused`() {
        for (endpoint in listOf(
            "https://10.0.0.5", "https://192.168.1.10:9000", "https://172.16.0.1",
            "https://169.254.169.254", "https://100.64.0.1", "https://[fd00::1]", "https://0.0.0.0",
            // 168.63.129.16 answers instance metadata at one hosting provider,
            // and is an ordinary public-looking address everywhere else.
            "https://168.63.129.16",
            // Benchmarking (198.18/15) and reserved (240/4) space.
            "https://198.18.0.1", "https://198.19.255.255", "https://240.0.0.1", "https://255.255.255.255",
            // Every IPv6 spelling that carries an IPv4 address inside it.
            "https://[::ffff:169.254.169.254]", "https://[::10.0.0.5]",
            "https://[64:ff9b::a00:5]", "https://[64:ff9b:1::a00:5]",
            // 6to4 and Teredo, both carrying 10.0.0.5 (Teredo stores the
            // client's address inverted: 10.0.0.5 -> f5ff:fffa).
            "https://[2002:a00:5::1]", "https://[2001:0:4136:e378:8000:63bf:f5ff:fffa]",
            // The discard-only prefix, 100::/64.
            "https://[100::1]",
        )) {
            assertEquals("private_address", StoreEndpointGuard.validate(endpoint, allowPrivate = false), endpoint)
        }
    }

    @Test
    fun `internal-only names are refused`() {
        assertEquals("internal_host", StoreEndpointGuard.validate("https://minio.railway.internal", false))
        assertEquals("internal_host", StoreEndpointGuard.validate("https://metadata.internal", false))
        assertEquals("internal_host", StoreEndpointGuard.validate("https://store.local", false))
    }

    @Test
    fun `a single-label host is refused`() {
        // `minio` is whatever the container runtime's search domain says, which
        // is a different machine in every network and inside a stack an internal
        // one. A store endpoint has to name a host the same way everywhere.
        assertEquals("single_label_host", StoreEndpointGuard.validate("https://minio", false))
        assertEquals("single_label_host", StoreEndpointGuard.validate("https://storage.", false))
        assertNull(StoreEndpointGuard.validate("https://minio.example.com", false))
        // With private endpoints allowed, a bare service name is exactly what an
        // operator means, and is accepted.
        assertNull(StoreEndpointGuard.validate("http://minio", allowPrivate = true))
    }

    @Test
    fun `plain http and private hosts are refused unless private endpoints are allowed`() {
        for (endpoint in listOf(
            "http://s3.example.com", "http://localhost:9000", "http://127.0.0.1:9000",
            "http://minio:9000", "https://10.0.0.5:9000",
        )) {
            assertNotNull(StoreEndpointGuard.validate(endpoint, allowPrivate = false), endpoint)
        }
        for (endpoint in listOf(
            "http://s3.example.com", "http://localhost:9000", "http://127.0.0.1:9000",
            "http://minio:9000", "https://10.0.0.5:9000", "http://minio.railway.internal:9000",
        )) {
            assertNull(StoreEndpointGuard.validate(endpoint, allowPrivate = true), endpoint)
        }
        // The setting relaxes the network, never the URL shape.
        assertEquals("scheme_not_https", StoreEndpointGuard.validate("ftp://minio", allowPrivate = true))
        assertEquals("has_path", StoreEndpointGuard.validate("http://minio/bucket", allowPrivate = true))
    }

    @Test
    fun `credentials, paths and queries in the endpoint are refused`() {
        assertEquals("malformed_url", StoreEndpointGuard.validate("https://user:pw@s3.example.com", false))
        assertEquals("has_path", StoreEndpointGuard.validate("https://s3.example.com/bucket", false))
        assertEquals("malformed_url", StoreEndpointGuard.validate("https://s3.example.com?x=1", false))
        assertEquals("no_host", StoreEndpointGuard.validate("https:///nohost", false))
    }

    @Test
    fun `a name that resolves to a private address is refused at connect time`() {
        val private = InetAddress.getByName("10.1.2.3")
        val public = InetAddress.getByName("93.184.216.34")
        val rebinding = StoreEndpointGuard.GuardedDns(allowPrivate = false) { listOf(private) }
        assertThrows(StoreEndpointBlockedException::class.java) { rebinding.lookup("store.example.com") }
        // One private answer among public ones is still a way in.
        val mixed = StoreEndpointGuard.GuardedDns(allowPrivate = false) { listOf(public, private) }
        assertThrows(StoreEndpointBlockedException::class.java) { mixed.lookup("store.example.com") }
        val clean = StoreEndpointGuard.GuardedDns(allowPrivate = false) { listOf(public) }
        assertEquals(listOf(public), clean.lookup("store.example.com"))
    }

    @Test
    fun `without the setting no name reaches an internal host or loopback`() {
        val dns = StoreEndpointGuard.GuardedDns(allowPrivate = false) { listOf(InetAddress.getByName("127.0.0.1")) }
        assertThrows(StoreEndpointBlockedException::class.java) { dns.lookup("evil.example.com") }
        assertThrows(StoreEndpointBlockedException::class.java) { dns.lookup("minio.railway.internal") }
    }

    @Test
    fun `with the setting private and internal answers are allowed`() {
        val loopback = InetAddress.getByName("127.0.0.1")
        val dns = StoreEndpointGuard.GuardedDns(allowPrivate = true) { listOf(loopback) }
        assertEquals(listOf(loopback), dns.lookup("minio"))
        assertEquals(listOf(loopback), dns.lookup("minio.railway.internal"))
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
                httpClient = StoreEndpointGuard.httpClient(timeoutSeconds = 5, allowPrivate = true),
            )
            val error = client.probe()
            assertNotNull(error, "a redirect is a failed probe, not a success")
            assertEquals("unexpected_response", error, "a redirect answers, it is not a network failure")
            assertTrue(originHits.get() >= 1, "the store was asked")
            assertEquals(0, targetHits.get(), "the redirect target was never contacted")
        } finally {
            origin.stop(0)
            target.stop(0)
        }
    }

    @Test
    fun `a loopback literal is refused on the socket without the private-endpoint setting`() {
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
                httpClient = StoreEndpointGuard.httpClient(timeoutSeconds = 5, allowPrivate = false),
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
