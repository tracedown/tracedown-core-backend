package dev.tracedown.common.util

import dev.tracedown.common.models.OrgEncryptionKeys
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encryption engine for variable values.
 *
 * Two on-disk formats coexist:
 *
 * **Envelope format ("v2")** — secret variables (`secret = true`). Each
 * organization owns a random AES-256 data-encryption key (DEK), stored in
 * `org_encryption_keys` wrapped (AES-GCM) with the platform key acting as the
 * key-encryption key (KEK). Secret values are encrypted AES-256-GCM under the
 * org DEK with additional authenticated data binding the ciphertext to its
 * `orgId:scope:key` context, and stored as `v2:` + base64(iv || ciphertext ||
 * tag) with a NULL `value_iv` column. Deleting the org's DEK row
 * crypto-shreds every secret of that org.
 *
 * **Legacy format** — everything written before the envelope existed, plus
 * non-secret encrypted variables ("Variable" type): AES-CBC under the
 * platform key, base64 ciphertext in `value` and base64 IV in `value_iv`, no
 * prefix. Legacy ciphertexts remain decryptable read-only; secrets are
 * migrated to the envelope by the gateway's startup re-encryption pass.
 *
 * DEKs are minted at org creation and lazily on the first secret write for
 * orgs that predate the feature, then cached in memory with a short TTL.
 *
 * Open for testability — tests override the `org_encryption_keys` access
 * points to run without a database.
 */
open class VariableCryptoEngine(
    kekBytes: ByteArray,
    private val dekCacheTtlMs: Long = DEFAULT_DEK_CACHE_TTL_MS,
) {

    private val kek = SecretKeySpec(kekBytes.copyOf(32), "AES")
    private val kekRaw = kekBytes.copyOf(32)

    private class CachedDek(val key: SecretKeySpec, val expiresAt: Long)

    /**
     * DEK cache. Entries are only ever added after the wrapped DEK was read
     * from (or written to) the `org_encryption_keys` table; a mint that is
     * later rolled back leaves a phantom entry, but its org row rolled back in
     * the same transaction, so no caller can ever encrypt for that id again —
     * the entry is harmless and expires with the TTL.
     */
    private val dekCache = ConcurrentHashMap<UUID, CachedDek>()

    // ── Envelope API (secret variables) ──

    /**
     * Encrypts a secret value under the owning org's DEK (minting the DEK if
     * the org does not have one yet). Must be called inside a transaction.
     * Returns the versioned envelope (`v2:...`); the `value_iv` column must be
     * stored as NULL for envelope values.
     */
    fun encryptSecret(orgId: UUID, plaintext: String, scope: String, key: String): String {
        val dek = fetchOrMintDek(orgId)
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(aad(orgId, scope, key))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return ENVELOPE_PREFIX + Base64.getEncoder().encodeToString(iv + ct)
    }

    /**
     * Decrypts a stored variable value of either format. Envelope values
     * (`v2:` prefix) are decrypted with the org DEK and verified against the
     * `orgId:scope:key` context; unprefixed values fall back to the legacy
     * AES-CBC platform-key path, which requires [ivBase64].
     * Must be called inside a transaction (the DEK may need a DB fetch).
     */
    fun decryptValue(orgId: UUID, stored: String, ivBase64: String?, scope: String, key: String): String {
        if (!stored.startsWith(ENVELOPE_PREFIX)) {
            checkNotNull(ivBase64) { "legacy-encrypted value has no IV" }
            return decryptLegacy(stored, ivBase64)
        }
        val dek = fetchDek(orgId)
            ?: throw IllegalStateException(
                "no data-encryption key for org $orgId — the ciphertext is unrecoverable (crypto-shredded?)"
            )
        val blob = Base64.getDecoder().decode(stored.substring(ENVELOPE_PREFIX.length))
        require(blob.size > GCM_IV_BYTES) { "envelope ciphertext too short" }
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        cipher.updateAAD(aad(orgId, scope, key))
        return String(cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES), Charsets.UTF_8)
    }

    /** True if [stored] is in the versioned envelope format. */
    fun isEnvelope(stored: String): Boolean = stored.startsWith(ENVELOPE_PREFIX)

    /**
     * Ensures the org has a DEK, minting one if absent. Called at org
     * creation (inside the creating transaction) so secrets written later
     * encrypt under it immediately; safe to call repeatedly.
     */
    fun mintOrgKey(orgId: UUID) {
        fetchOrMintDek(orgId)
    }

    // ── Legacy API (platform-key AES-CBC) ──
    // Still written for non-secret encrypted variables ("Variable" type) and
    // read for every pre-envelope ciphertext.

    /** Encrypts a value under the platform key. Returns (encryptedBase64, ivBase64). */
    fun encryptLegacy(plaintext: String): Pair<String, String> {
        val cipher = Cipher.getInstance(CBC_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(encrypted) to Base64.getEncoder().encodeToString(iv)
    }

    /** Decrypts a legacy platform-key value from its encrypted + IV base64 strings. */
    fun decryptLegacy(encryptedBase64: String, ivBase64: String): String {
        val cipher = Cipher.getInstance(CBC_TRANSFORM)
        val ivSpec = IvParameterSpec(Base64.getDecoder().decode(ivBase64))
        cipher.init(Cipher.DECRYPT_MODE, kek, ivSpec)
        return String(cipher.doFinal(Base64.getDecoder().decode(encryptedBase64)), Charsets.UTF_8)
    }

    // ── KEK rotation groundwork ──

    /** Outcome of a KEK re-wrap pass over all org DEKs. */
    data class RewrapResult(val rewrapped: Int, val alreadyCurrent: Int, val failed: List<UUID>)

    /**
     * Re-wraps every org DEK from [oldKekBytes] to the engine's current KEK.
     * Rows already wrapped with the current KEK are skipped, so the operation
     * is idempotent and safe to re-run after a partial failure. Per-org DEKs
     * themselves never change — only their wrapping.
     */
    fun rewrapOrgKeys(oldKekBytes: ByteArray): RewrapResult {
        val oldKek = SecretKeySpec(oldKekBytes.copyOf(32), "AES")
        var rewrapped = 0
        var alreadyCurrent = 0
        val failed = mutableListOf<UUID>()
        transaction {
            OrgEncryptionKeys.selectAll().forEach { row ->
                val orgId = row[OrgEncryptionKeys.orgId]
                val wrapped = row[OrgEncryptionKeys.wrappedDek]
                try {
                    rewrapWrappedDek(orgId, wrapped, oldKek)?.let { newWrapped ->
                        OrgEncryptionKeys.update({ OrgEncryptionKeys.orgId eq orgId }) {
                            it[wrappedDek] = newWrapped
                        }
                        rewrapped++
                    } ?: alreadyCurrent++
                } catch (e: Exception) {
                    failed.add(orgId)
                    log.error("could not re-wrap DEK for org {}: {}", orgId, e.message)
                }
            }
        }
        dekCache.clear()
        return RewrapResult(rewrapped, alreadyCurrent, failed)
    }

    /**
     * Re-wraps a single wrapped DEK from [oldKek] to the current KEK. Returns
     * null if the value is already wrapped with the current KEK; throws if it
     * unwraps with neither key.
     */
    internal fun rewrapWrappedDek(orgId: UUID, wrapped: String, oldKek: SecretKeySpec): String? {
        // Already current (re-run after a partial rotation)?
        try {
            unwrapDek(orgId, wrapped, kek)
            return null
        } catch (_: Exception) {
            // fall through to the old KEK
        }
        val dekBytes = unwrapDek(orgId, wrapped, oldKek)
        return wrapDek(orgId, dekBytes)
    }

    // ── DEK management ──

    private fun fetchOrMintDek(orgId: UUID): SecretKeySpec {
        cachedDek(orgId)?.let { return it }
        val wrapped = loadWrappedDek(orgId) ?: mintWrappedDek(orgId)
        return SecretKeySpec(unwrapDek(orgId, wrapped, kek), "AES").also { cacheDek(orgId, it) }
    }

    private fun fetchDek(orgId: UUID): SecretKeySpec? {
        cachedDek(orgId)?.let { return it }
        val wrapped = loadWrappedDek(orgId) ?: return null
        return SecretKeySpec(unwrapDek(orgId, wrapped, kek), "AES").also { cacheDek(orgId, it) }
    }

    private fun cachedDek(orgId: UUID): SecretKeySpec? {
        val entry = dekCache[orgId] ?: return null
        if (entry.expiresAt < System.currentTimeMillis()) {
            dekCache.remove(orgId)
            return null
        }
        return entry.key
    }

    private fun cacheDek(orgId: UUID, key: SecretKeySpec) {
        dekCache[orgId] = CachedDek(key, System.currentTimeMillis() + dekCacheTtlMs)
    }

    /** Reads the org's wrapped DEK, or null if the org has none yet. Open for tests. */
    protected open fun loadWrappedDek(orgId: UUID): String? = transaction {
        OrgEncryptionKeys.selectAll()
            .where { OrgEncryptionKeys.orgId eq orgId }
            .firstOrNull()
            ?.get(OrgEncryptionKeys.wrappedDek)
    }

    /**
     * Mints and persists a wrapped DEK for the org. A concurrent minter is
     * survived via INSERT .. ON CONFLICT DO NOTHING + re-select, so exactly
     * one DEK ever wins. Open for tests.
     */
    protected open fun mintWrappedDek(orgId: UUID): String = transaction {
        OrgEncryptionKeys.insertIgnore {
            it[OrgEncryptionKeys.orgId] = orgId
            it[wrappedDek] = wrapNewDek(orgId)
            it[keyVersion] = 1
            it[createdAt] = Instant.now()
        }
        OrgEncryptionKeys.selectAll()
            .where { OrgEncryptionKeys.orgId eq orgId }
            .first()[OrgEncryptionKeys.wrappedDek]
    }

    /** Generates a fresh random 256-bit DEK and wraps it for storage. */
    protected fun wrapNewDek(orgId: UUID): String = wrapDek(orgId, ByteArray(DEK_BYTES).also(random::nextBytes))

    /** Wraps DEK bytes with the current KEK (AES-GCM, AAD = org id). */
    private fun wrapDek(orgId: UUID, dekBytes: ByteArray): String {
        val iv = ByteArray(GCM_IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, kek, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.updateAAD(orgId.toString().toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(dekBytes))
    }

    /** Unwraps a stored DEK with the given KEK (AAD = org id, so a wrapped DEK cannot be moved between orgs). */
    private fun unwrapDek(orgId: UUID, wrapped: String, withKek: SecretKeySpec): ByteArray {
        val blob = Base64.getDecoder().decode(wrapped)
        val cipher = Cipher.getInstance(GCM_TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, withKek, GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_IV_BYTES))
        cipher.updateAAD(orgId.toString().toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, GCM_IV_BYTES, blob.size - GCM_IV_BYTES)
    }

    private fun aad(orgId: UUID, scope: String, key: String): ByteArray =
        "$orgId:$scope:$key".toByteArray(Charsets.UTF_8)

    companion object {
        const val ENVELOPE_PREFIX = "v2:"
        private const val GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val CBC_TRANSFORM = "AES/CBC/PKCS5Padding"
        private const val GCM_IV_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val DEK_BYTES = 32
        private const val DEFAULT_DEK_CACHE_TTL_MS = 5 * 60 * 1000L

        private val random = SecureRandom()
        private val log = LoggerFactory.getLogger(VariableCryptoEngine::class.java)

        /**
         * Parses the platform key from its 64-hex-char (32-byte, AES-256)
         * representation. Rejects anything else with a clear error rather than
         * silently zero-padding a short or malformed key into a weak AES key — a
         * genuinely dangerous footgun. This runs unconditionally (no environment
         * gate): the dev all-zero default is a valid 64-hex string and still
         * passes; only truly malformed keys are rejected.
         */
        fun parseKeyHex(aesKeyHex: String): ByteArray {
            val hex = aesKeyHex.trim()
            require(hex.length == 64 && hex.all { it in "0123456789abcdefABCDEF" }) {
                "platform AES key must be exactly 64 hex characters (32 bytes / AES-256); " +
                    "got ${hex.length} character(s)"
            }
            return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
    }
}

/**
 * Static facade over [VariableCryptoEngine], initialized once at service
 * startup with the platform AES key.
 *
 * Variable types determined by (secret, encrypted):
 * - secret=true,  encrypted=true  → Secret: envelope-encrypted with the org DEK, value never shown
 * - secret=false, encrypted=true  → Variable: platform-key encrypted at rest, decrypted on explicit request
 * - secret=false, encrypted=false → Metric: stored as plaintext, always visible
 * - secret=true,  encrypted=false → INVALID: rejected before reaching DB
 */
object VariableCrypto {

    @Volatile
    private var engine: VariableCryptoEngine? = null

    fun init(aesKeyHex: String) {
        engine = VariableCryptoEngine(VariableCryptoEngine.parseKeyHex(aesKeyHex))
    }

    /** Installs a custom engine (tests). */
    fun init(customEngine: VariableCryptoEngine) {
        engine = customEngine
    }

    private fun engine(): VariableCryptoEngine =
        checkNotNull(engine) { "VariableCrypto not initialized — call init(aesKeyHex) at startup" }

    // ── Secret variables (envelope, org DEK) ──

    /** Encrypts a secret value for its org. See [VariableCryptoEngine.encryptSecret]. */
    fun encrypt(orgId: UUID, plaintext: String, scope: String, key: String): String =
        engine().encryptSecret(orgId, plaintext, scope, key)

    /** Decrypts a stored value of either format. See [VariableCryptoEngine.decryptValue]. */
    fun decrypt(orgId: UUID, stored: String, ivBase64: String?, scope: String, key: String): String =
        engine().decryptValue(orgId, stored, ivBase64, scope, key)

    /** True if [stored] is in the versioned envelope format. */
    fun isEnvelope(stored: String): Boolean = engine().isEnvelope(stored)

    /**
     * Mints the org's DEK if the engine is initialized; a no-op otherwise.
     * Org creation calls this so DEKs exist up front — but a caller without
     * the platform key configured must still be able to create orgs, relying
     * on the lazy mint at the first secret write instead.
     */
    fun mintOrgKeyIfInitialized(orgId: UUID) {
        engine?.mintOrgKey(orgId)
    }

    /** Re-wraps all org DEKs from an old platform key to the current one. */
    fun rewrapOrgKeys(oldKekHex: String): VariableCryptoEngine.RewrapResult =
        engine().rewrapOrgKeys(VariableCryptoEngine.parseKeyHex(oldKekHex))

    // ── Non-secret encrypted variables + pre-envelope ciphertexts (platform key) ──

    /** Encrypts a non-secret value under the platform key. Returns (encryptedBase64, ivBase64). */
    fun encrypt(plaintext: String): Pair<String, String> = engine().encryptLegacy(plaintext)

    /** Decrypts a platform-key value from its encrypted + IV base64 strings. */
    fun decrypt(encryptedBase64: String, ivBase64: String): String =
        engine().decryptLegacy(encryptedBase64, ivBase64)
}
