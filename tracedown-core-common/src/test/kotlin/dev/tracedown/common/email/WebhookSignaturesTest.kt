package dev.tracedown.common.email

import java.time.Instant
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The webhook endpoints are unauthenticated, so these checks are the whole of
 * their access control. A pass that should have failed lets a stranger suppress
 * any address — silently stopping every alert to that recipient.
 */
class WebhookSignaturesTest {

    // Deliberately not shaped like a real Mailgun key ("key-" + 32 hex): secret
    // scanning cannot tell a fixture from a leak, and a push blocked by a fake
    // teaches everyone to click through the next real one.
    private val mailgunKey = "test-signing-key-not-a-real-credential"
    private val resendSecret = "whsec_" + Base64.getEncoder().encodeToString("a-signing-secret".toByteArray())

    private fun now() = Instant.now().epochSecond.toString()

    private fun hmacHex(key: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(message.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun svixSignature(secret: String, id: String, ts: String, body: String): String {
        val key = Base64.getDecoder().decode(secret.removePrefix("whsec_"))
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return "v1," + Base64.getEncoder().encodeToString(mac.doFinal("$id.$ts.$body".toByteArray()))
    }

    // ── Mailgun ──

    @Test
    fun `a correctly signed mailgun payload verifies`() {
        val ts = now()
        val token = "abc123"
        assertTrue(
            WebhookSignatures.verifyMailgun(mailgunKey, ts, token, hmacHex(mailgunKey, ts + token))
        )
    }

    @Test
    fun `a mailgun signature made with the wrong key is refused`() {
        val ts = now()
        val token = "abc123"
        assertFalse(
            WebhookSignatures.verifyMailgun(mailgunKey, ts, token, hmacHex("test-wrong-key", ts + token))
        )
    }

    @Test
    fun `a mailgun signature over a different token is refused`() {
        val ts = now()
        assertFalse(
            WebhookSignatures.verifyMailgun(mailgunKey, ts, "real-token", hmacHex(mailgunKey, ts + "other-token"))
        )
    }

    @Test
    fun `an old mailgun signature is refused even though it verifies`() {
        // Captured from a real delivery and replayed an hour later: the HMAC is
        // genuine, which is exactly why the timestamp has to be checked too.
        val ts = (Instant.now().epochSecond - 3600).toString()
        val token = "abc123"
        assertFalse(
            WebhookSignatures.verifyMailgun(mailgunKey, ts, token, hmacHex(mailgunKey, ts + token))
        )
    }

    @Test
    fun `mailgun verification fails closed when no key is configured`() {
        // No HMAC is computed here on purpose: an unconfigured endpoint must
        // refuse before it touches the crypto, whatever signature is presented.
        val ts = now()
        assertFalse(WebhookSignatures.verifyMailgun("", ts, "t", hmacHex(mailgunKey, ts + "t")))
    }

    // ── Resend ──

    @Test
    fun `a correctly signed resend payload verifies`() {
        val id = "msg_1"
        val ts = now()
        val body = """{"type":"email.bounced"}"""
        assertTrue(
            WebhookSignatures.verifyResend(resendSecret, id, ts, body, svixSignature(resendSecret, id, ts, body))
        )
    }

    @Test
    fun `a resend signature over a different body is refused`() {
        val id = "msg_1"
        val ts = now()
        val signed = svixSignature(resendSecret, id, ts, """{"type":"email.bounced"}""")
        // The body the endpoint would act on is not the body that was signed.
        assertFalse(
            WebhookSignatures.verifyResend(resendSecret, id, ts, """{"type":"email.complained"}""", signed)
        )
    }

    @Test
    fun `resend accepts any of several signatures during a secret rotation`() {
        val id = "msg_1"
        val ts = now()
        val body = """{"type":"email.bounced"}"""
        val header = "v1,AAAAnotarealsignature== " + svixSignature(resendSecret, id, ts, body)
        assertTrue(WebhookSignatures.verifyResend(resendSecret, id, ts, body, header))
    }

    @Test
    fun `an old resend signature is refused`() {
        val id = "msg_1"
        val ts = (Instant.now().epochSecond - 3600).toString()
        val body = """{"type":"email.bounced"}"""
        assertFalse(
            WebhookSignatures.verifyResend(resendSecret, id, ts, body, svixSignature(resendSecret, id, ts, body))
        )
    }

    @Test
    fun `resend verification fails closed on a missing secret or malformed header`() {
        val id = "msg_1"
        val ts = now()
        val body = "{}"
        assertFalse(WebhookSignatures.verifyResend("", id, ts, body, "v1,whatever"))
        assertFalse(WebhookSignatures.verifyResend(resendSecret, id, ts, body, ""))
        assertFalse(WebhookSignatures.verifyResend(resendSecret, id, ts, body, "garbage"))
    }
}
