package dev.tracedown.scheduler.crypto

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.KeyPair
import java.security.cert.X509Certificate
import javax.net.ssl.SSLServerSocket
import kotlin.concurrent.thread

/**
 * A real mutual-TLS handshake from the scheduler's pinned client to an agent
 * reached at an advertised host rather than its bare slug.
 *
 * The client verifies the dialed host against the agent certificate on top of
 * the slug pin, so an agent certificate must name both: the slug (what the
 * scheduler pins) and the host in the agent's URI (what it dials). The
 * certificates here have the shape the gateway issues — CA-signed, serverAuth
 * only, the slug SAN first and the advertised host after it.
 *
 * There is no IP-literal case here: the client names an IP peer by its reverse
 * lookup when one exists, and every loopback address has one, so a loopback
 * handshake cannot exercise the IP SAN. Issuance of the IP SAN is covered on the
 * gateway side.
 */
class AdvertisedHostHandshakeTest {

    /**
     * Serves one HTTPS request on a loopback port with [cert], requiring a client
     * certificate from the test CA (mutual TLS, as the agent does), and returns
     * the port.
     */
    private fun serveOnce(agentKey: KeyPair, cert: X509Certificate): SSLServerSocket {
        val server = TestPki.agentServerSocket(agentKey, cert)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                        flush()
                    }
                }
            }
        }
        return server
    }

    private fun dial(url: String): String {
        val factory = TestPki.clientFactory()
        try {
            return runBlocking { factory.client(TestPki.SLUG).get(url).bodyAsText() }
        } finally {
            factory.close()
        }
    }

    @Test
    fun `a handshake to the advertised host named on the certificate verifies`() {
        val agentKey = TestPki.key()
        // "localhost" stands in for a public name like runner.example.com: it is
        // a DNS name that differs from the slug and resolves in any test sandbox.
        serveOnce(agentKey, TestPki.agentCert(agentKey, listOf(GeneralName(GeneralName.dNSName, "localhost")))).use {
            assertEquals("ok", dial("https://localhost:${it.localPort}/"))
        }
    }

    @Test
    fun `a slug-only certificate fails a handshake to any other name`() {
        // The bug this guards: the certificate only names the slug, so the
        // dialed host is not on it and every dispatch fails the handshake.
        val agentKey = TestPki.key()
        serveOnce(agentKey, TestPki.agentCert(agentKey, emptyList())).use {
            assertThrows<Exception> { dial("https://localhost:${it.localPort}/") }
        }
    }

    @Test
    fun `the advertised host does not replace the slug pin`() {
        // A certificate naming the dialed host but a different slug is still
        // refused: the host SAN satisfies the hostname check, never the pin.
        val agentKey = TestPki.key()
        val otherAgent = TestPki.certificate(
            subject = "CN=other-agent",
            publicKey = agentKey,
            issuer = TestPki.caKey.private,
            sans = listOf(
                GeneralName(GeneralName.dNSName, "other-agent"),
                GeneralName(GeneralName.dNSName, "localhost"),
            ),
            eku = KeyPurposeId.id_kp_serverAuth,
        )
        serveOnce(agentKey, otherAgent).use {
            assertThrows<Exception> { dial("https://localhost:${it.localPort}/") }
        }
    }
}
