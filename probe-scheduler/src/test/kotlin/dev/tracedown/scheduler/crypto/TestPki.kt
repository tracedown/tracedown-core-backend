package dev.tracedown.scheduler.crypto

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

/**
 * A throwaway PKI with the shape the gateway issues: one CA, a clientAuth
 * scheduler certificate, serverAuth agent certificates naming their slug first.
 * Shared by the tests that dial a real mutual-TLS loopback agent.
 */
object TestPki {
    const val SLUG = "runner-agent"

    init {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    fun key(): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048, SecureRandom()) }.generateKeyPair()

    val caKey: KeyPair = key()
    val caCert: X509Certificate = certificate(
        subject = "CN=test-ca",
        publicKey = caKey,
        issuer = null,
        sans = emptyList(),
        eku = null,
        isCa = true,
    )

    val schedulerKey: KeyPair = key()
    val schedulerCert: X509Certificate = certificate(
        subject = "CN=tracedown-scheduler",
        publicKey = schedulerKey,
        issuer = caKey.private,
        sans = listOf(GeneralName(GeneralName.dNSName, "tracedown-scheduler")),
        eku = KeyPurposeId.id_kp_clientAuth,
    )

    fun certificate(
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
    fun agentCert(agentKey: KeyPair, extra: List<GeneralName>): X509Certificate = certificate(
        subject = "CN=$SLUG",
        publicKey = agentKey,
        issuer = caKey.private,
        sans = listOf(GeneralName(GeneralName.dNSName, SLUG)) + extra,
        eku = KeyPurposeId.id_kp_serverAuth,
    )

    /**
     * A loopback TLS server socket presenting [cert] and requiring a client
     * certificate from the test CA (mutual TLS, as the agent does). The caller
     * accepts on it.
     */
    fun agentServerSocket(agentKey: KeyPair, cert: X509Certificate): SSLServerSocket {
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
        return server
    }

    /** The scheduler's pinned client factory, trusting only the test CA. */
    fun clientFactory(): AgentMtlsClientFactory = AgentMtlsClientFactory(
        schedulerCert = schedulerCert,
        schedulerKey = schedulerKey.private,
        caCert = caCert,
        trustedCas = listOf(caCert),
        revocationChecker = RevocationChecker(ttlMillis = 0) { emptySet() },
    )
}
