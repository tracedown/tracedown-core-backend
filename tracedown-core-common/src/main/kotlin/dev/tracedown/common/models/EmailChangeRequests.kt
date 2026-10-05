package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import java.util.UUID

/**
 * An address change waiting for the new address to confirm it. The change is
 * written to [Users.email] only when the link mailed to [newEmail] is followed;
 * until then the account keeps the address it had. One live request per
 * account — a new one supersedes the old.
 *
 * A request is only as good as the credentials that made it. Whatever
 * replaces or removes them — a password change or reset, the account being
 * closed, revived or handed to a new signup — calls [voidFor], so a link
 * minted under the old ones cannot move the account afterwards.
 */
object EmailChangeRequests : Table("email_change_requests") {
    val id = javaUUID("id")
    val userId = javaUUID("user_id").references(Users.id)
    val newEmail = varchar("new_email", 256)

    /** SHA-256 of the token in the mailed link; the raw token is never stored. */
    val tokenHash = varchar("token_hash", 64)
    val expiresAt = timestamp("expires_at")
    val used = bool("used").default(false)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)

    /** Drops every request of [userId]; runs in the caller's transaction. */
    fun voidFor(userId: UUID): Int = deleteWhere { EmailChangeRequests.userId eq userId }
}
