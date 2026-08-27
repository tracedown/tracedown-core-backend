package dev.tracedown.gateway.controllers.auth

import com.eatthepath.otp.TimeBasedOneTimePasswordGenerator
import java.time.Instant
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object TotpUtil {

    private val totp = TimeBasedOneTimePasswordGenerator()

    fun decryptSecret(encryptedBase64: String, ivBase64: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.copyOf(32), "AES")
        val ivSpec = IvParameterSpec(Base64.getDecoder().decode(ivBase64))
        cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
        return cipher.doFinal(Base64.getDecoder().decode(encryptedBase64))
    }

    fun encryptSecret(secret: ByteArray, key: ByteArray): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val keySpec = SecretKeySpec(key.copyOf(32), "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret)
        return Base64.getEncoder().encodeToString(encrypted) to Base64.getEncoder().encodeToString(iv)
    }

    /**
     * The time-step index [code] matches, or null if it matches neither the
     * current step nor the one behind it (clock-drift tolerance).
     *
     * Callers need the step, not just a yes/no: a TOTP code is single-use, and
     * the only way to enforce that is to record which step was consumed and
     * refuse anything that is not strictly newer (see [TotpPolicy.consumes]).
     * A bare boolean cannot express which of the two accepted windows matched,
     * so a replayed code was indistinguishable from a fresh one.
     */
    fun matchingStep(secret: ByteArray, code: String, now: Instant = Instant.now()): Long? {
        val key = SecretKeySpec(secret, totp.algorithm)
        val stepSeconds = totp.timeStep.seconds
        val currentStep = Math.floorDiv(now.epochSecond, stepSeconds)
        // Accept current window and one step back (clock drift tolerance)
        if (code == totp.generateOneTimePasswordString(key, now)) return currentStep
        if (code == totp.generateOneTimePasswordString(key, now.minus(totp.timeStep))) return currentStep - 1
        return null
    }

    /**
     * Whether [code] matches a currently valid step at all, ignoring whether it
     * has already been used. Only for flows with no account state to consume
     * against — enrollment confirmation, where the secret is not stored yet.
     * Every flow that authenticates an existing account must go through
     * [matchingStep] and [TotpPolicy.consumes] instead.
     */
    fun verifyCode(secret: ByteArray, code: String): Boolean =
        matchingStep(secret, code) != null

    fun generateCode(secret: ByteArray): String {
        val key = SecretKeySpec(secret, totp.algorithm)
        return totp.generateOneTimePasswordString(key, Instant.now())
    }
}
