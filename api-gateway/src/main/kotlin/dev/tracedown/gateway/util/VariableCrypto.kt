package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes

/**
 * Gateway-specific variable crypto utilities.
 * The shared encrypt/decrypt logic lives in tracedown-core-common.
 */
object VariableCrypto {

    /**
     * What an encrypted value is shown as wherever it is listed, at every
     * scope. One value, so a client can tell a masked value from a real one
     * by more than its looks — and, better, by `VariableSummary.masked`.
     */
    const val MASK = "••••••"

    fun init(aesKeyHex: String) {
        dev.tracedown.common.util.VariableCrypto.init(aesKeyHex)
    }

    /** Envelope-encrypts a secret value with the owning org's DEK. Store with a NULL value_iv. */
    fun encrypt(orgId: java.util.UUID, plaintext: String, scope: String, key: String): String =
        dev.tracedown.common.util.VariableCrypto.encrypt(orgId, plaintext, scope, key)

    /** Decrypts a stored value of either format (envelope or legacy platform-key CBC). */
    fun decrypt(orgId: java.util.UUID, stored: String, ivBase64: String?, scope: String, key: String): String =
        dev.tracedown.common.util.VariableCrypto.decrypt(orgId, stored, ivBase64, scope, key)

    /** Platform-key encryption for non-secret encrypted variables ("Variable" type). */
    fun encrypt(plaintext: String): Pair<String, String> =
        dev.tracedown.common.util.VariableCrypto.encrypt(plaintext)

    fun decrypt(encryptedBase64: String, ivBase64: String): String =
        dev.tracedown.common.util.VariableCrypto.decrypt(encryptedBase64, ivBase64)

    /** Validates the secret/encrypted combination. Throws on invalid combo. */
    fun validateType(secret: Boolean, encrypted: Boolean) {
        if (secret && !encrypted) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
    }

    /**
     * Returns the display value for a variable based on its type and the show
     * flag. Secrets are always masked, so the only decrypt here is the
     * non-secret "Variable" type — which is platform-key encrypted (never
     * envelope), so no org context is needed.
     */
    fun displayValue(rawValue: String, iv: String?, secret: Boolean, encrypted: Boolean, show: Boolean): String {
        return when {
            secret -> MASK
            encrypted && show -> decrypt(rawValue, iv!!)
            encrypted -> MASK
            else -> rawValue
        }
    }

    /** Whether [displayValue] masks a value of this type under this show flag. */
    fun isMasked(secret: Boolean, encrypted: Boolean, show: Boolean): Boolean = secret || (encrypted && !show)
}
