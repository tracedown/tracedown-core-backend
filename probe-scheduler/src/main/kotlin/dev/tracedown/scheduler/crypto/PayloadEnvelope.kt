package dev.tracedown.scheduler.crypto

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/**
 * Seals a dispatch to the agent that will run it, and opens the answer.
 *
 * mTLS already authenticates both ends and this scheduler pins the agent's
 * certificate, so a network attacker cannot read a dispatch. What the tunnel
 * does not survive is an intermediary that terminates TLS *deliberately* — an
 * ingress controller, a managed edge, a tunnel daemon holding the keys. Sealing
 * the payload to the peer's public key closes that: the script, the resolved
 * variables and the secret plaintexts stay unreadable however many hops the
 * bytes take.
 *
 * Optional by design (see `probe.payloadEncryption`), because it buys nothing
 * against a compromised agent — which must decrypt to run the probe at all.
 *
 * Format, both directions:
 * ```
 * {"v":1,"alg":"RSA-OAEP-256+A256GCM","ek":b64,"iv":b64,"ct":b64}
 * ```
 *
 * **The OAEP parameters are load-bearing and must not be simplified.** Java's
 * `"RSA/ECB/OAEPWithSHA-256AndMGF1Padding"` uses SHA-256 for the digest but
 * **SHA-1 for MGF1** unless an explicit [OAEPParameterSpec] overrides it. The
 * agent uses SHA-256 for both. Dropping the spec below does not fail here — it
 * produces a wrapped key the agent unwraps to garbage, which surfaces as every
 * probe failing to decrypt with no clue why.
 */
object PayloadEnvelope {

    const val VERSION = 1
    const val ALG = "RSA-OAEP-256+A256GCM"

    private const val GCM_TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val CONTENT_KEY_BITS = 256

    private val random = SecureRandom()

    /** Matched byte-for-byte to the agent's `padding.OAEP(mgf=MGF1(SHA256), algorithm=SHA256)`. */
    private fun oaepSpec() = OAEPParameterSpec(
        "SHA-256",
        "MGF1",
        MGF1ParameterSpec.SHA256,
        PSource.PSpecified.DEFAULT,
    )

    /** Whether a response body is a sealed envelope rather than a plain result. */
    fun isEnvelope(body: JsonObject): Boolean = "ek" in body && "ct" in body

    /** Seals [payload] to the public key in [recipientCertPem]. */
    fun seal(payload: JsonObject, recipientCertPem: String): JsonObject {
        val publicKey = publicKeyOf(recipientCertPem)

        val contentKey = ByteArray(CONTENT_KEY_BITS / 8).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)

        val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        val ciphertext = gcm.doFinal(payload.toString().toByteArray(Charsets.UTF_8))

        val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.ENCRYPT_MODE, publicKey, oaepSpec())
        }
        val wrappedKey = rsa.doFinal(contentKey)

        val encoder = Base64.getEncoder()
        return buildJsonObject {
            put("v", VERSION)
            put("alg", ALG)
            put("ek", encoder.encodeToString(wrappedKey))
            put("iv", encoder.encodeToString(iv))
            put("ct", encoder.encodeToString(ciphertext))
        }
    }

    /**
     * Opens an envelope sealed to [privateKey]. Throws [EnvelopeException] on any
     * failure — deliberately without saying which step failed, since that
     * distinction is a decryption oracle and the caller acts the same either way.
     */
    fun open(envelope: JsonObject, privateKey: PrivateKey): JsonObject {
        val version = envelope["v"]?.jsonPrimitive?.contentOrNull
        if (version != VERSION.toString()) throw EnvelopeException("unsupported envelope version")
        if (envelope["alg"]?.jsonPrimitive?.contentOrNull != ALG) {
            throw EnvelopeException("unsupported envelope algorithm")
        }
        return try {
            val decoder = Base64.getDecoder()
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                init(Cipher.DECRYPT_MODE, privateKey, oaepSpec())
            }
            val contentKey = rsa.doFinal(decoder.decode(envelope["ek"]!!.jsonPrimitive.content))
            val iv = decoder.decode(envelope["iv"]!!.jsonPrimitive.content)
            val gcm = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            val plaintext = gcm.doFinal(decoder.decode(envelope["ct"]!!.jsonPrimitive.content))
            kotlinx.serialization.json.Json.parseToJsonElement(plaintext.toString(Charsets.UTF_8)) as JsonObject
        } catch (e: EnvelopeException) {
            throw e
        } catch (e: Exception) {
            throw EnvelopeException("could not open envelope")
        }
    }

    private fun publicKeyOf(certPem: String): RSAPublicKey {
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certPem.toByteArray())) as X509Certificate
        return cert.publicKey as? RSAPublicKey
            ?: throw EnvelopeException("certificate does not carry an RSA key")
    }

    class EnvelopeException(message: String) : RuntimeException(message)
}
