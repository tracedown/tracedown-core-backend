package dev.tracedown.gateway.controllers.agents

import dev.tracedown.common.models.CaRoot
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.openssl.jcajce.JcaPEMWriter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.StringReader
import java.io.StringWriter
import java.math.BigInteger
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.Date
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Manages the internal CA used to sign agent certificates.
 *
 * The CA root (certificate + encrypted private key) is stored in the
 * `ca_root` table.  The private key is encrypted with AES-256-GCM
 * using the platform encryption key (`PLATFORM_AES_KEY` env var).
 */
object CaService {

    private const val CA_VALIDITY_YEARS = 10
    private const val AGENT_CERT_VALIDITY_DAYS = 365L
    private const val GCM_TAG_BITS = 128
    private const val GCM_IV_BYTES = 12

    /**
     * Minimum RSA modulus accepted in an agent CSR. The agent generates
     * RSA-4096 keys; this floor rejects any downgraded/weak key an attacker
     * might submit while still allowing the canonical 4096-bit key.
     */
    private const val MIN_AGENT_RSA_BITS = 3072

    /**
     * Raised when a submitted CSR is malformed, has an invalid self-signature
     * (no proof-of-possession), or carries a key that fails policy. Callers map
     * this to a 400 rather than a 500.
     */
    class CsrValidationException(message: String) : IllegalArgumentException(message)

    private lateinit var aesKeyHex: String

    /** Initialize with the platform AES key. Must be called before any CA operations. */
    fun init(aesKeyHex: String) {
        this.aesKeyHex = aesKeyHex
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }

    // ── Public API ──

    /**
     * Ensures an active CA root exists. Creates one if none is active.
     * Returns the active CA certificate PEM.
     */
    fun ensureCaRoot(): String =
        activeCaRow()?.get(CaRoot.certificatePem) ?: createCa()

    /**
     * Rotates the CA (make-before-break): retires the current active CA — which
     * stays in the trust bundle until it expires, so existing agents keep working
     * — and issues a fresh active CA that new certificates are signed against.
     * Agents migrate to the new CA (and drop the old) as they re-issue.
     */
    fun rotateCa(): String {
        activeCaRow()?.let { row ->
            CaRoot.update({ CaRoot.id eq row[CaRoot.id] }) { it[rotatedAt] = Instant.now() }
        }
        return createCa()
    }

    /**
     * All currently-trusted (non-expired) CA certificates, PEM-concatenated.
     * Handed to agents as their trust bundle so they trust every CA that might
     * have signed a peer certificate during a rotation overlap.
     */
    fun caBundle(): String =
        trustedRows().joinToString("") { it[CaRoot.certificatePem].trim() + "\n" }

    /** Generates a new CA root and inserts it as the active (non-rotated) row. */
    private fun createCa(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(4096, SecureRandom())
        }.generateKeyPair()

        val now = Instant.now()
        val notAfter = now.plus(Duration.ofDays(CA_VALIDITY_YEARS * 365L))
        val issuer = X500Name("CN=Tracedown Internal CA,O=Tracedown")

        val certHolder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now.toEpochMilli()),
            Date.from(now),
            Date.from(notAfter),
            issuer,
            keyPair.public,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(true))
            // RFC 5280: a CA certificate carries keyUsage (keyCertSign,
            // §4.2.1.3) and a Subject Key Identifier (§4.2.1.2), and every
            // certificate it issues names that identifier in its Authority Key
            // Identifier. Verifiers that enforce the RFC — OpenSSL's
            // X509_STRICT, on by default in Python 3.13's
            // ssl.create_default_context() — refuse a chain missing any of
            // the three. Roots minted before this lack keyUsage and SKID; the
            // agent relaxes strict verification for its private CA, and
            // rotateCa() mints a compliant root.
            addExtension(Extension.keyUsage, true, KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign))
            val ext = JcaX509ExtensionUtils()
            addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(keyPair.public))
            addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(keyPair.public))
        }.build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))

        val certPem = holderToPem(certHolder)
        val (encryptedKey, iv) = encryptPrivateKey(keyPair.private)

        CaRoot.insert {
            it[certificatePem] = certPem
            it[privateKeyEncrypted] = encryptedKey
            it[privateKeyIv] = iv
            it[expiresAt] = notAfter
            it[createdAt] = now
        }
        return certPem
    }

    /** The active (current signer) CA row — the newest one not yet rotated. */
    private fun activeCaRow() =
        CaRoot.selectAll().toList()
            .filter { it[CaRoot.rotatedAt] == null }
            .maxByOrNull { it[CaRoot.id] }

    /** All non-expired CA rows (the trust bundle), newest first. */
    private fun trustedRows() =
        CaRoot.selectAll().toList()
            .filter { it[CaRoot.expiresAt] > Instant.now() }
            .sortedByDescending { it[CaRoot.id] }

    /**
     * Signs an agent CSR with the CA private key, binding the certificate
     * identity to [subjectSlug] — the slug the authenticated bootstrap token
     * (or existing agent) maps to, NOT anything the CSR itself claims.
     *
     * Security invariants enforced here (a CSR is attacker-controlled input):
     *  - Proof-of-possession: the CSR's self-signature must verify against its
     *    own public key, proving the requester holds the matching private key.
     *  - Key floor: RSA only, modulus ≥ [MIN_AGENT_RSA_BITS]. Weak keys rejected.
     *  - Subject is ignored: CN and SubjectAltName are set server-side to
     *    [subjectSlug], so a CSR cannot claim `CN=tracedown-scheduler` or any
     *    other identity. The SAN (DNS = slug) is what the scheduler pins.
     *  - Constrained usage: basicConstraints CA:false, keyUsage
     *    digitalSignature+keyEncipherment, EKU serverAuth only — an agent
     *    certificate can act as a TLS server but never as a client (so it can
     *    never dial another agent) nor as a CA.
     *
     * @param csrPem PEM-encoded PKCS#10 CSR from the agent.
     * @param subjectSlug the agent slug to bind as the certificate identity.
     * @return pair of (signed agent certificate PEM, CA trust-bundle PEM).
     * @throws CsrValidationException if the CSR is malformed or fails policy.
     */
    fun signCsr(csrPem: String, subjectSlug: String): Pair<String, String> {
        val caRow = activeCaRow()
            ?: throw IllegalStateException("CA root not initialized — run --agent-bootstrap first")

        val caPrivateKey = decryptPrivateKey(
            caRow[CaRoot.privateKeyEncrypted],
            caRow[CaRoot.privateKeyIv],
        )
        val caCertPem = caRow[CaRoot.certificatePem]
        val caCert = parseCertPem(caCertPem)

        val csr = parseCsrPem(csrPem)

        // Proof-of-possession: the CSR must be validly self-signed by the key it
        // carries. Without this a caller could submit a CSR built around someone
        // else's public key and obtain a certificate for a key they don't hold.
        val verifierProvider = try {
            JcaContentVerifierProviderBuilder().setProvider("BC").build(csr.subjectPublicKeyInfo)
        } catch (e: Exception) {
            throw CsrValidationException("CSR public key is unreadable: ${e.message}")
        }
        val posValid = try {
            csr.isSignatureValid(verifierProvider)
        } catch (e: Exception) {
            false
        }
        if (!posValid) throw CsrValidationException("CSR self-signature is invalid (proof-of-possession failed)")

        // Extract and vet the public key: RSA only, at or above the key floor.
        val csrPublicKey = try {
            KeyFactory.getInstance("RSA", "BC")
                .generatePublic(java.security.spec.X509EncodedKeySpec(csr.subjectPublicKeyInfo.encoded))
        } catch (e: Exception) {
            throw CsrValidationException("CSR does not carry an RSA public key")
        }
        val rsaKey = csrPublicKey as? RSAPublicKey
            ?: throw CsrValidationException("CSR key is not RSA")
        if (rsaKey.modulus.bitLength() < MIN_AGENT_RSA_BITS) {
            throw CsrValidationException(
                "CSR key is ${rsaKey.modulus.bitLength()}-bit; minimum is $MIN_AGENT_RSA_BITS",
            )
        }

        val now = Instant.now()
        val notAfter = now.plus(Duration.ofDays(AGENT_CERT_VALIDITY_DAYS))
        val serial = BigInteger(128, SecureRandom())

        // Identity is assigned by us, not the CSR: CN + DNS SAN = the slug.
        val subject = X500Name("CN=$subjectSlug")
        val sanDns = GeneralNames(GeneralName(GeneralName.dNSName, subjectSlug))

        val certHolder = JcaX509v3CertificateBuilder(
            caCert,
            serial,
            Date.from(now),
            Date.from(notAfter),
            subject,
            csrPublicKey,
        ).apply {
            addExtension(Extension.basicConstraints, true, BasicConstraints(false))
            addExtension(
                Extension.keyUsage,
                true,
                KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
            )
            // serverAuth only — agents serve TLS but must never be TLS clients.
            addExtension(
                Extension.extendedKeyUsage,
                true,
                ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth),
            )
            addExtension(Extension.subjectAlternativeName, false, sanDns)
            // Key identifiers: see createCa(). The AKID is the CA's key id, so it
            // links to the root whether or not that root was minted with a SKID.
            val ext = JcaX509ExtensionUtils()
            addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(csrPublicKey))
            addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(caCert.publicKey))
        }.build(JcaContentSignerBuilder("SHA256withRSA").build(caPrivateKey))

        val agentCertPem = holderToPem(certHolder)
        return agentCertPem to caBundle()
    }

    /** Computes the SHA-256 fingerprint of a PEM certificate. */
    fun fingerprint(certPem: String): String {
        val cert = parseCertPem(certPem)
        val digest = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        return bytesToHex(digest)
    }

    // ── Crypto ──

    private fun aesKey(): SecretKeySpec {
        require(::aesKeyHex.isInitialized) { "CaService.init() must be called first" }
        require(aesKeyHex.length == 64) { "AES key must be 64 hex characters (256 bits)" }
        return SecretKeySpec(hexToBytes(aesKeyHex), "AES")
    }

    private fun encryptPrivateKey(key: PrivateKey): Pair<String, String> {
        val aes = aesKey()
        val iv = ByteArray(GCM_IV_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, aes, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(key.encoded)
        return Base64.getEncoder().encodeToString(encrypted) to bytesToHex(iv)
    }

    private fun decryptPrivateKey(encryptedBase64: String, ivHex: String): PrivateKey {
        val aes = aesKey()
        val iv = hexToBytes(ivHex)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, aes, GCMParameterSpec(GCM_TAG_BITS, iv))
        val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
        return KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decrypted))
    }

    // ── PEM parsing ──

    private fun holderToPem(holder: org.bouncycastle.cert.X509CertificateHolder): String {
        val cert = JcaX509CertificateConverter().setProvider("BC").getCertificate(holder)
        val writer = StringWriter()
        JcaPEMWriter(writer).use { it.writeObject(cert) }
        return writer.toString()
    }

    private fun parseCertPem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(pem.byteInputStream()) as X509Certificate

    private fun parseCsrPem(pem: String): PKCS10CertificationRequest {
        PEMParser(StringReader(pem)).use { parser ->
            return parser.readObject() as? PKCS10CertificationRequest
                ?: throw IllegalArgumentException("Invalid CSR PEM")
        }
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
