package dev.tracedown.common.email

import dev.tracedown.common.models.EmailSuppressions
import dev.tracedown.common.models.SuppressionReasons
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * The addresses mail must not be sent to, and the one place that decides.
 *
 * A provider reports a permanent failure once; every later send to that address
 * would be delivered to a mailbox known not to exist. Providers score that as
 * sender abuse, so the cost of ignoring it is not the wasted message — it is the
 * deliverability of everything else sent from the same domain.
 *
 * Addresses are normalised to lowercase on the way in and on the way out, so the
 * caller never has to think about the casing a webhook happened to report.
 */
object SuppressionList {

    private val log = LoggerFactory.getLogger(SuppressionList::class.java)

    /**
     * Records [email] as undeliverable. Idempotent: a repeat report for the same
     * address refreshes the reason and detail rather than inserting again, so a
     * provider retrying a webhook cannot grow the table.
     *
     * Assumes a transaction is NOT already open — it opens its own.
     */
    fun suppress(email: String, reason: String, provider: String? = null, detail: String? = null) {
        val normalised = normalise(email)
        if (normalised.isEmpty()) return
        transaction {
            val updated = EmailSuppressions.update({ EmailSuppressions.email eq normalised }) {
                it[EmailSuppressions.reason] = reason
                it[EmailSuppressions.provider] = provider
                it[EmailSuppressions.detail] = detail
            }
            if (updated == 0) {
                EmailSuppressions.insert {
                    it[id] = UUID.randomUUID()
                    it[EmailSuppressions.email] = normalised
                    it[EmailSuppressions.reason] = reason
                    it[EmailSuppressions.provider] = provider
                    it[EmailSuppressions.detail] = detail
                    it[createdAt] = Instant.now()
                }
            }
        }
        log.info("suppressed {} reason={} provider={}", normalised, reason, provider)
    }

    /** Whether mail to [email] must be withheld. Assumes a transaction is open. */
    fun isSuppressed(email: String): Boolean {
        val normalised = normalise(email)
        if (normalised.isEmpty()) return false
        return EmailSuppressions
            .selectAll()
            .where { EmailSuppressions.email eq normalised }
            .limit(1)
            .any()
    }

    /**
     * Lifts the suppression on [email]. The route back for an address that
     * bounced for a reason since fixed — a mailbox recreated, a typo corrected
     * upstream — without which the only remedy is editing the table by hand.
     *
     * Returns true if a row was removed.
     */
    fun release(email: String): Boolean {
        val normalised = normalise(email)
        if (normalised.isEmpty()) return false
        val removed = transaction {
            EmailSuppressions.deleteWhere { EmailSuppressions.email eq normalised }
        }
        if (removed > 0) log.info("released suppression on {}", normalised)
        return removed > 0
    }

    /** Whether [reason] is one this list recognises. */
    fun isKnownReason(reason: String): Boolean = reason in setOf(
        SuppressionReasons.BOUNCE,
        SuppressionReasons.COMPLAINT,
        SuppressionReasons.MANUAL,
    )

    private fun normalise(email: String): String = email.trim().lowercase()
}
