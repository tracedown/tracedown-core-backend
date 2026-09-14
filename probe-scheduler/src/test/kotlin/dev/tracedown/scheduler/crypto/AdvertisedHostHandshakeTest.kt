package dev.tracedown.scheduler.crypto

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.runBlocking
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.Date
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
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

    private companion object {
        const val SLUG = "runner-agent"

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    private fun key(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    private val caKey = key()
    private val caCert: X509Certificate = certificate(
        subject = "CN=test-ca",
        publicKey = caKey,
        issuer = null,
        sans = emptyList(),
        eku = null,
        isCa = true,
    )

    private val schedulerKey = key()
    private val schedulerCert = certificate(
        subject = "CN=tracedown-scheduler",
        publicKey = schedulerKey,
        issuer = caKey.private,
        sans = listOf(GeneralName(GeneralName.dNSName, "tracedown-scheduler")),
        eku = KeyPurposeId.id_kp_clientAuth,
    )

    private fun certificate(
        subject: String,
        publicKey: KeyPair,
        issuer: PrivateKey?,
        sans: List<GeneralName>,
        eku: KeyPurposeId?,
        isCa: Boolean = false,
    ): X509Certificate {
        val now = Instant.now()
        val holder = JcaX509v3CertificateBuilder(
            if (isCa) X500Name(subject) else X500Name("CN=test-ca"),
            BigInteger(64, SecureRandom()),
            Date.from(now.minus(Duration.ofDays(1))),
            Date.from(now.plus(Duration.ofDays(30))),
            X500Name(subject),
            publicKey.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(isCa))
            if (isCa) {
                addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            } else {
                addExtension(
                    Extension.keyUsage,
                    true,
                    KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
                )
            }
            if (eku != null) addExtension(Extension.extendedKeyUsage, true, ExtendedKeyUsage(eku))
            if (sans.isNotEmpty()) {
                addExtension(Extension.subjectAlternativeName, false, GeneralNames(sans.toTypedArray()))
            }
        }.build(JcaContentSignerBuilder("SHA256withRSA").build(issuer ?: publicKey.private))
        return JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
    }

    /** An agent certificate with the slug SAN followed by [extra] names. */
    private fun agentCert(agentKey: KeyPair, extra: List<GeneralName>): X509Certificate = certificate(
        subject = "CN=$SLUG",
        publicKey = agentKey,
        issuer = caKey.private,
        sans = listOf(GeneralName(GeneralName.dNSName, SLUG)) + extra,
        eku = KeyPurposeId.id_kp_serverAuth,
    )

    /**
     * Serves one HTTPS request on a loopback port with [cert], requiring a client
     * certificate from the test CA (mutual TLS, as the agent does), and returns
     * the port.
     */
    private fun serveOnce(agentKey: KeyPair, cert: X509Certificate): SSLServerSocket {
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("agent", agentKey.private, CharArray(0), arrayOf(cert, caCert))
        }
        val trustStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setCertificateEntry("ca", caCert)
        }
        val context = SSLContext.getInstance("TLS").apply {
            init(
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    .apply { init(keyStore, CharArray(0)) }.keyManagers,
                TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                    .apply { init(trustStore) }.trustManagers,
                SecureRandom(),
            )
        }
        val server = context.serverSocketFactory
            .createServerSocket(0, 1, InetAddress.getLoopbackAddress()) as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf("TLSv1.2")
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
        val factory = AgentMtlsClientFactory(
            schedulerCert = schedulerCert,
            schedulerKey = schedulerKey.private,
            caCert = caCert,
            trustedCas = listOf(caCert),
            revocationChecker = RevocationChecker(ttlMillis = 0) { emptySet() },
        )
        try {
            return runBlocking { factory.client(SLUG).get(url).bodyAsText() }
        } finally {
            factory.close()
        }
    }

    @Test
    fun `a handshake to the advertised host named on the certificate verifies`() {
        val agentKey = key()
        // "localhost" stands in for a public name like runner.example.com: it is
        // a DNS name that differs from the slug and resolves in any test sandbox.
        serveOnce(agentKey, agentCert(agentKey, listOf(GeneralName(GeneralName.dNSName, "localhost")))).use {
            assertEquals("ok", dial("https://localhost:${it.localPort}/"))
        }
    }

    @Test
    fun `a slug-only certificate fails a handshake to any other name`() {
        // The bug this guards: the certificate only names the slug, so the
        // dialed host is not on it and every dispatch fails the handshake.
        val agentKey = key()
        serveOnce(agentKey, agentCert(agentKey, emptyList())).use {
            assertThrows<Exception> { dial("https://localhost:${it.localPort}/") }
        }
    }

    @Test
    fun `the advertised host does not replace the slug pin`() {
        // A certificate naming the dialed host but a different slug is still
        // refused: the host SAN satisfies the hostname check, never the pin.
        val agentKey = key()
        val otherAgent = certificate(
            subject = "CN=other-agent",
            publicKey = agentKey,
            issuer = caKey.private,
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
