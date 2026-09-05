package dev.tracedown.scheduler.crypto

import dev.tracedown.common.models.AgentCertificates
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference

/**
 * Consulted during the scheduler's TLS trust decision to refuse a certificate
 * that has been revoked (superseded on renewal, or belonging to a
 * decommissioned agent). `agent_certificates.revoked` was being written but
 * never enforced anywhere; this closes that gap.
 *
 * Revoked fingerprints are cached for [ttlMillis] so the check adds no
 * per-handshake database round-trip, while still picking up new revocations
 * within the TTL window.
 *
 * @param loader returns the current set of revoked certificate fingerprints
 *   (lowercase hex SHA-256, matching [CaService.fingerprint]'s format).
 */
class RevocationChecker(
    private val ttlMillis: Long = 60_000L,
    private val loader: () -> Set<String>,
) {

    private data class Snapshot(val fingerprints: Set<String>, val loadedAt: Long)

    private val cache = AtomicReference<Snapshot?>(null)

    /** True if [cert]'s SHA-256 fingerprint is currently revoked. */
    fun isRevoked(cert: X509Certificate): Boolean =
        fingerprint(cert) in current()

    private fun current(): Set<String> {
        val now = System.currentTimeMillis()
        val snap = cache.get()
        if (snap != null && now - snap.loadedAt < ttlMillis) return snap.fingerprints
        val fresh = try {
            loader()
        } catch (e: Exception) {
            // Fail closed on the last known set rather than trusting everything
            // when the store is briefly unreachable.
            snap?.fingerprints ?: emptySet()
        }
        cache.set(Snapshot(fresh, now))
        return fresh
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded)
            .joinToString("") { "%02x".format(it) }

    companion object {
        /** Production loader: revoked fingerprints straight from the shared table. */
        fun fromDatabase(ttlMillis: Long = 60_000L): RevocationChecker =
            RevocationChecker(ttlMillis) {
                transaction {
                    AgentCertificates.selectAll()
                        .where { AgentCertificates.revoked eq true }
                        .map { it[AgentCertificates.fingerprint].lowercase() }
                        .toSet()
                }
            }
    }
}
