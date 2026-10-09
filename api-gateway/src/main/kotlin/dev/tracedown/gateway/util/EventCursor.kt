package dev.tracedown.gateway.util

import dev.tracedown.common.util.VariableCryptoEngine
import java.nio.ByteBuffer
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The event feed's cursor: a position in the outbox — the writing
 * transaction and the row, `(xid, seq)` — sealed for one organization.
 *
 * Sealed so it says nothing and works nowhere else: the position is a count of
 * everything every organization has done, which is not one organization's to
 * read, and a cursor carried to another organization's key must not be taken
 * for a position there. AES-256-GCM under a key derived from the platform
 * key, the organization as associated data, and the nonce derived from the
 * organization and the position — so the same position gives the same cursor
 * every time (it is deterministic on purpose, and the only thing a repeat
 * reveals is the equality of two positions of one organization).
 *
 * `ev2.` marks this form. A cursor of that form that does not open — sealed
 * for another organization, or under a platform key since changed — is told
 * apart from one that is not a cursor at all ([isSealed]).
 */
object EventCursor {

    /** A position the cursor names: read on from the row after `(xid, seq)`. */
    sealed interface Position {
        data class At(val xid: Long, val seq: Long) : Position, Comparable<At> {
            override fun compareTo(other: At): Int = compareValuesBy(this, other, At::xid, At::seq)
        }
    }

    private const val PREFIX = "ev2."
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128

    /** The two keys, each derived from the platform key under a label of its own. */
    private class Keys(val nonce: ByteArray, val seal: ByteArray)

    @Volatile
    private var keys: Keys? = null

    /** Derives the cursor keys from the platform key. Call once at startup. */
    fun init(platformKeyHex: String) {
        val platform = VariableCryptoEngine.parseKeyHex(platformKeyHex)
        keys = Keys(nonce = derive(platform, "tracedown event cursor nonce"), seal = derive(platform, "tracedown event cursor seal"))
    }

    private fun derive(platform: ByteArray, label: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(platform, "HmacSHA256"))
        return mac.doFinal(label.toByteArray())
    }

    private fun keys(): Keys = keys ?: error("EventCursor.init was not called")

    /** Nonce, 16 bytes of position, 16 of tag. */
    private const val SEALED_BYTES = NONCE_BYTES + 16 + TAG_BITS / 8

    /** The cursor for [position] in [orgId]. */
    fun encode(orgId: UUID, position: Position.At): String {
        val plain = ByteBuffer.allocate(16).putLong(position.xid).putLong(position.seq).array()
        val aad = uuidBytes(orgId)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keys().nonce, "HmacSHA256"))
        mac.update(aad)
        val nonce = mac.doFinal(plain).copyOf(NONCE_BYTES)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys().seal, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(aad)
        val sealed = cipher.doFinal(plain)
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(nonce + sealed)
    }

    /** The position [cursor] names in [orgId], or null when it does not open there. */
    fun decode(orgId: UUID, cursor: String): Position.At? {
        val bytes = sealedBytes(cursor) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE, SecretKeySpec(keys().seal, "AES"),
                GCMParameterSpec(TAG_BITS, bytes.copyOf(NONCE_BYTES)),
            )
            cipher.updateAAD(uuidBytes(orgId))
            val plain = ByteBuffer.wrap(cipher.doFinal(bytes.copyOfRange(NONCE_BYTES, bytes.size)))
            Position.At(plain.long, plain.long)
        }.getOrNull()
    }

    /**
     * Whether [cursor] has the form of a sealed cursor — whatever it opens to.
     * One that does and does not open was sealed elsewhere, or before the
     * platform key changed: a cursor that has stopped working, not a typo.
     */
    fun isSealed(cursor: String): Boolean = sealedBytes(cursor) != null

    private fun sealedBytes(cursor: String): ByteArray? {
        if (!cursor.startsWith(PREFIX)) return null
        return runCatching { Base64.getUrlDecoder().decode(cursor.removePrefix(PREFIX)) }.getOrNull()
            ?.takeIf { it.size == SEALED_BYTES }
    }

    private fun uuidBytes(id: UUID): ByteArray =
        ByteBuffer.allocate(16).putLong(id.mostSignificantBits).putLong(id.leastSignificantBits).array()
}
