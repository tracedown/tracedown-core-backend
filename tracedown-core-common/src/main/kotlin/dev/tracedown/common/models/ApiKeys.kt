package dev.tracedown.common.models

import dev.tracedown.common.auth.AccessLevel
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

object ApiKeys : Table("api_keys") {
    val id = javaUUID("id")
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    /**
     * The user this key acts as: every request it authenticates is checked
     * against that user's permissions in [organizationId]. Cleared (ON DELETE
     * SET NULL) when the account is erased, and a key with nobody to act as
     * authenticates nothing.
     */
    val createdBy = javaUUID("created_by").references(Users.id).nullable()
    val name = varchar("name", 128)
    /** SHA-256 hex of the key (see `TokenHasher`). The key itself is never stored. */
    val keyHash = varchar("key_hash", 255)
    /** Leading characters of the key, for telling keys apart. Null on rows that predate key authentication. */
    val keyPrefix = varchar("key_prefix", 16).nullable()
    /** Ceiling on what the key may do, on top of its user's permissions — an `AccessLevel`: read or write. */
    val access = short("access").default(AccessLevel.READ)
    val lastUsedAt = timestamp("last_used_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val revoked = bool("revoked").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
