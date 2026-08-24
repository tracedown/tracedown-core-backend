package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Addresses no further mail may be sent to.
 *
 * Written by the provider bounce webhook, read by the email-service before every
 * send. Only permanent failures belong here — a soft bounce resolves on its own,
 * and suppressing on one would silently drop a working recipient.
 *
 * [email] is stored lowercased and carries the unique index, so the send-time
 * lookup is a single indexed hit and a repeat webhook for the same address
 * updates one row instead of accumulating duplicates.
 */
object EmailSuppressions : Table("email_suppressions") {
    val id = uuid("id")
    val email = varchar("email", 320).uniqueIndex("idx_email_suppressions_email")
    val reason = varchar("reason", 16)
    /** Which provider reported it; null for a manual entry. */
    val provider = varchar("provider", 16).nullable()
    /** The provider's own description, kept verbatim for diagnosis. */
    val detail = text("detail").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}

/** Values stored in [EmailSuppressions.reason]. */
object SuppressionReasons {
    /** Permanent delivery failure — the address does not accept mail. */
    const val BOUNCE = "bounce"

    /** The recipient marked a message as spam. */
    const val COMPLAINT = "complaint"

    /** An operator suppressed the address by hand. */
    const val MANUAL = "manual"
}
