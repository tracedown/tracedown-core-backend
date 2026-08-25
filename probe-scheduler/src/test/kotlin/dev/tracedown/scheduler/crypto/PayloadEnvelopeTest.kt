package dev.tracedown.scheduler.crypto

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The envelope has to interoperate with a Python agent, and the way it fails if
 * it does not is silent: RSA-OAEP with a mismatched MGF1 digest wraps happily
 * and unwraps to garbage. So the load-bearing test here is not a round trip
 * within Kotlin — that would pass with both sides equally wrong — but opening a
 * fixture the agent's own code produced.
 *
 * `src/test/resources/envelope/` holds a throwaway keypair and a payload sealed
 * by `mtls/envelope.py`. Regenerate it from the agent repo if the format
 * changes; do not hand-edit it.
 */
class PayloadEnvelopeTest {

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/envelope/$name")!!.bufferedReader().readText()

    private fun fixtureKey(): PrivateKey {
        val pem = resource("agent-key.pem")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace(Regex("\\s"), "")
        return KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
    }

    @Test
    fun `opens an envelope sealed by the Python agent`() {
        val sealed = Json.parseToJsonElement(resource("sealed-by-python.json")).jsonObject
        val expected = Json.parseToJsonElement(resource("expected-plaintext.json")).jsonObject

        assertEquals(expected, PayloadEnvelope.open(sealed, fixtureKey()))
    }

    @Test
    fun `seals to the fixture certificate and opens it again`() {
        val payload = buildJsonObject {
            put("script", "GET https://example.test")
            put("variables", buildJsonObject { put("s_token", "hunter2") })
        }
        val sealed = PayloadEnvelope.seal(payload, resource("agent-cert.pem"))

        assertTrue(PayloadEnvelope.isEnvelope(sealed))
        assertEquals(payload, PayloadEnvelope.open(sealed, fixtureKey()))
    }

    @Test
    fun `the secret does not survive into the wire form`() {
        val payload = buildJsonObject { put("token", "hunter2") }
        val sealed = PayloadEnvelope.seal(payload, resource("agent-cert.pem"))

        assertFalse(sealed.toString().contains("hunter2"))
        val ciphertext = Base64.getDecoder().decode(sealed["ct"]!!.jsonPrimitive.content)
        assertFalse(String(ciphertext, Charsets.ISO_8859_1).contains("hunter2"))
    }

    @Test
    fun `a plain result is not mistaken for an envelope`() {
        val plain = Json.parseToJsonElement("""{"outcome":"success","calls":[]}""").jsonObject
        assertFalse(PayloadEnvelope.isEnvelope(plain))
    }

    @Test
    fun `a tampered ciphertext is refused rather than decrypted`() {
        val sealed = PayloadEnvelope.seal(buildJsonObject { put("a", "b") }, resource("agent-cert.pem"))
        val raw = Base64.getDecoder().decode(sealed["ct"]!!.jsonPrimitive.content)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val tampered = JsonObject(sealed.toMutableMap().apply {
            put("ct", kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(raw)))
        })

        // GCM authenticates: a flipped bit is a failure, never a different plaintext.
        assertFailsWith<PayloadEnvelope.EnvelopeException> {
            PayloadEnvelope.open(tampered, fixtureKey())
        }
    }

    @Test
    fun `an unknown version or algorithm is refused`() {
        val sealed = PayloadEnvelope.seal(buildJsonObject { put("a", "b") }, resource("agent-cert.pem"))

        val wrongVersion = JsonObject(sealed.toMutableMap().apply {
            put("v", kotlinx.serialization.json.JsonPrimitive(99))
        })
        val wrongAlg = JsonObject(sealed.toMutableMap().apply {
            put("alg", kotlinx.serialization.json.JsonPrimitive("RSA-OAEP-1+A256GCM"))
        })

        assertFailsWith<PayloadEnvelope.EnvelopeException> { PayloadEnvelope.open(wrongVersion, fixtureKey()) }
        assertFailsWith<PayloadEnvelope.EnvelopeException> { PayloadEnvelope.open(wrongAlg, fixtureKey()) }
    }

    @Test
    fun `the failure message says nothing about which step failed`() {
        val sealed = PayloadEnvelope.seal(buildJsonObject { put("a", "b") }, resource("agent-cert.pem"))
        val raw = Base64.getDecoder().decode(sealed["ek"]!!.jsonPrimitive.content)
        raw[0] = (raw[0].toInt() xor 0x01).toByte()
        val broken = JsonObject(sealed.toMutableMap().apply {
            put("ek", kotlinx.serialization.json.JsonPrimitive(Base64.getEncoder().encodeToString(raw)))
        })

        // Distinguishing "bad key wrap" from "bad tag" is a decryption oracle.
        val error = assertFailsWith<PayloadEnvelope.EnvelopeException> {
            PayloadEnvelope.open(broken, fixtureKey())
        }
        assertContains(error.message!!, "could not open envelope")
    }

    @Test
    fun `every seal uses a fresh iv`() {
        val payload = buildJsonObject { put("a", "b") }
        val ivs = (1..8).map {
            PayloadEnvelope.seal(payload, resource("agent-cert.pem"))["iv"]!!.jsonPrimitive.content
        }.toSet()
        assertEquals(8, ivs.size)
    }
}
