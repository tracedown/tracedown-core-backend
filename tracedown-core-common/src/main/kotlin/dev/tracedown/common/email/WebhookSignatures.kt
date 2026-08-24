package dev.tracedown.common.email

import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

/**
 * Verifies that a delivery webhook really came from the provider.
 *
 * These endpoints are unauthenticated by necessity — a provider cannot hold a
 * session — so the signature is the ONLY thing standing between the open
 * internet and the suppression list. An unverified endpoint would let anyone
 * suppress any address, which is a silent denial of every alert to that
 * recipient. Fail closed: no secret configured means no webhook accepted.
 */
object WebhookSignatures {

    /** Reject anything older than this, so a captured payload cannot be replayed. */
    private const val TOLERANCE_SECONDS = 300L

    /**
     * Mailgun signs `timestamp + token` with the webhook signing key (HMAC-SHA256,
     * hex). The signing key is distinct from the sending API key.
     */
    fun verifyMailgun(signingKey: String, timestamp: String, token: String, signature: String): Boolean {
        if (signingKey.isBlank()) return false
        if (!withinTolerance(timestamp.toLongOrNull())) return false
        val expected = hmacSha256(signingKey.toByteArray(), (timestamp + token).toByteArray())
        return constantTimeEquals(toHex(expected), signature.lowercase())
    }

    /**
     * Resend signs through Svix: the signed content is `id.timestamp.body`, the
     * secret is base64 after its `whsec_` prefix, and the header carries
     * space-separated `v1,<base64>` entries — more than one during a secret
     * rotation, so any match is a pass.
     */
    fun verifyResend(secret: String, id: String, timestamp: String, body: String, signatureHeader: String): Boolean {
        if (secret.isBlank()) return false
        if (!withinTolerance(timestamp.toLongOrNull())) return false
        val key = try {
            Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        } catch (_: IllegalArgumentException) {
            return false
        }
        val expected = Base64.getEncoder().encodeToString(
            hmacSha256(key, "$id.$timestamp.$body".toByteArray())
        )
        return signatureHeader.split(" ")
            .mapNotNull { it.substringAfter("v1,", "").takeIf(String::isNotEmpty) }
            .any { constantTimeEquals(expected, it) }
    }

    /** A signature far enough from now is a replay, however well it verifies. */
    private fun withinTolerance(timestamp: Long?): Boolean {
        if (timestamp == null) return false
        return abs(Instant.now().epochSecond - timestamp) <= TOLERANCE_SECONDS
    }

    private fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    /**
     * Compared without an early exit: a byte-at-a-time comparison leaks, through
     * timing, how much of a guess was right, which is enough to forge a signature
     * one byte at a time.
     */
    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
}
