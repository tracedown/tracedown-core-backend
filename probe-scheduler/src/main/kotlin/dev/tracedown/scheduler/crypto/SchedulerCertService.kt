package dev.tracedown.scheduler.crypto

import dev.tracedown.common.models.CaRoot
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Generates an ephemeral mTLS client certificate for the scheduler.
 *
 * On startup, reads the CA from the database, decrypts the CA private
 * key, generates a short-lived RSA-2048 keypair, and signs it with the
 * CA. Exposes the cert, key, and CA cert for building an mTLS client.
 */
class SchedulerCertService(private val aesKeyHex: String) {

    private val log = LoggerFactory.getLogger(javaClass)

    lateinit var certificate: X509Certificate
        private set
    lateinit var privateKey: PrivateKey
        private set

    /**
     * [certificate] in PEM, for sealing into a dispatch so the agent can seal
     * its answer back. Derived rather than stored: the certificate is ephemeral
     * and regenerated on every start, so there is no PEM to keep.
     */
    val certificatePem: String
        get() = buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(java.util.Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(certificate.encoded))
            append("\n-----END CERTIFICATE-----\n")
        }

    lateinit var caCertificate: X509Certificate
        private set

    /** All non-expired CA certificates — the trust bundle for verifying agents. */
    lateinit var trustedCaCertificates: List<X509Certificate>
        private set

    companion object {
        private const val CERT_VALIDITY_DAYS = 30L
        private const val GCM_TAG_BITS = 128

        /**
         * The scheduler's certificate identity (CN + DNS SAN). Agents grant
         * inbound access on the strength of a `clientAuth` certificate, which
         * only the scheduler is issued; this name is the human-readable pin.
         */
        const val SCHEDULER_IDENTITY = "tracedown-scheduler"

        init {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(BouncyCastleProvider())
            }
        }
    }

    /** Initializes the mTLS credentials. Must be called after DB is connected. */
    fun init() {
        val (caCert, caPrivateKey, trusted) = transaction { loadCa() }
        this.caCertificate = caCert
        this.trustedCaCertificates = trusted

        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()

        val now = Instant.now()
        val notAfter = now.plus(Duration.ofDays(CERT_VALIDITY_DAYS))

        val certHolder = JcaX509v3CertificateBuilder(
            caCert,
            BigInteger(128, SecureRandom()),
            Date.from(now),
            Date.from(notAfter),
            javax.security.auth.x500.X500Principal("CN=$SCHEDULER_IDENTITY,O=Tracedown"),
            keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.digitalSignature))
            // clientAuth only — this certificate authenticates the scheduler as a
            // TLS *client* to agents. Agents (serverAuth-only) can never present
            // one, so a leaked agent cert cannot impersonate the scheduler.
            addExtension(
                Extension.extendedKeyUsage,
                true,
                ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth),
            )
            addExtension(
                Extension.subjectAlternativeName,
                false,
                GeneralNames(GeneralName(GeneralName.dNSName, SCHEDULER_IDENTITY)),
            )
            // RFC 5280 key identifiers, as the gateway's CaService issues them:
            // agents verify this certificate with OpenSSL, and Python 3.13's
            // default context enforces the RFC (X509_STRICT).
            val ext = JcaX509ExtensionUtils()
            addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(keyPair.public))
            addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert.publicKey))
        }.build(JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey))

        this.certificate = JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(certHolder)
        this.privateKey = keyPair.private

        log.info("mTLS client certificate generated (expires {})", notAfter)
    }

    // ── Internal ──

    private fun loadCa(): Triple<X509Certificate, PrivateKey, List<X509Certificate>> {
        val rows = CaRoot.selectAll().toList()
        val activeRow = rows.filter { it[CaRoot.rotatedAt] == null }.maxByOrNull { it[CaRoot.id] }
            ?: throw IllegalStateException("CA root not found — run --agent-bootstrap on the gateway first")

        val cf = CertificateFactory.getInstance("X.509")
        val caCert = cf.generateCertificate(activeRow[CaRoot.certificatePem].byteInputStream()) as X509Certificate

        val caPrivateKey = decryptPrivateKey(
            activeRow[CaRoot.privateKeyEncrypted],
            activeRow[CaRoot.privateKeyIv],
        )

        // Trust every non-expired CA so agents signed by a still-trusted (e.g.
        // just-rotated) CA continue to verify during a rotation overlap.
        val now = Instant.now()
        val trusted = rows.filter { it[CaRoot.expiresAt] > now }
            .map { cf.generateCertificate(it[CaRoot.certificatePem].byteInputStream()) as X509Certificate }

        return Triple(caCert, caPrivateKey, trusted)
    }

    private fun decryptPrivateKey(encryptedBase64: String, ivHex: String): PrivateKey {
        val aesBytes = hexToBytes(aesKeyHex)
        val aes = SecretKeySpec(aesBytes, "AES")
        val iv = hexToBytes(ivHex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decrypted))
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
