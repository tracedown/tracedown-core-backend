package dev.tracedown.common.storage

import dev.tracedown.common.util.VariableCryptoEngine

/**
 * The key a body store's secret access key is encrypted with: `BODY_STORE_AES_KEY`,
 * the same 64-hex-character format as the platform key but a key of its own.
 *
 * It is separate on purpose. Only the gateway (which saves stores and reads
 * `in_place` bodies) and the result-ingestor (which imports from them) ever need
 * these credentials, and the ingestor has no other use for the platform key — with
 * one shared key it would hold TOTP secrets, the CA root and every organization's
 * data-encryption key wrapping as well, for no reason.
 *
 * Ciphertexts are AES-256-GCM, bound to `body_store:<id>` as additional
 * authenticated data, so a ciphertext copied onto another row does not decrypt.
 *
 * A service without the key configured cannot decrypt any store: stores still
 * list and save (a save needs the key too, so it fails loudly), reads report
 * `secret_undecryptable`, and both services WARN at startup when stores exist.
 */
object BodyStoreCrypto {

    @Volatile
    private var engine: VariableCryptoEngine? = null

    /** Installs the key. Called at startup by every service that touches store credentials. */
    fun init(aesKeyHex: String) {
        engine = VariableCryptoEngine(VariableCryptoEngine.parseKeyHex(aesKeyHex))
    }

    /** Forgets the key (tests). */
    fun reset() {
        engine = null
    }

    /** True once [init] has run. */
    fun isInitialized(): Boolean = engine != null

    /** The AAD a store's secret is bound to. */
    fun context(id: java.util.UUID): String = "body_store:$id"

    /** Encrypts [plaintext] for [context]. Returns (ciphertextBase64, ivBase64). */
    fun encryptBound(plaintext: String, context: String): Pair<String, String> =
        engine().encryptBound(plaintext, context)

    /** Decrypts a value written by [encryptBound] for the same [context]. */
    fun decryptBound(ciphertextBase64: String, ivBase64: String, context: String): String =
        engine().decryptBound(ciphertextBase64, ivBase64, context)

    /** Decrypts with an explicit key — the rotation CLI reading rows under the previous one. */
    fun decryptBoundWith(
        aesKeyHex: String,
        ciphertextBase64: String,
        ivBase64: String,
        context: String,
    ): String = VariableCryptoEngine(VariableCryptoEngine.parseKeyHex(aesKeyHex))
        .decryptBound(ciphertextBase64, ivBase64, context)

    private fun engine(): VariableCryptoEngine =
        checkNotNull(engine) { "BODY_STORE_AES_KEY is not configured — body store credentials cannot be read" }
}
