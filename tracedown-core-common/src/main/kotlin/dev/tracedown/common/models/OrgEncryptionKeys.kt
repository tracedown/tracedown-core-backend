package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Per-organization data-encryption keys (DEKs) for envelope encryption of
 * secret variable values.
 *
 * [wrappedDek] is the org's random AES-256 DEK, wrapped (AES-GCM) with the
 * platform key acting as the key-encryption key. Deleting a row is a
 * crypto-shredding operation: every secret-variable ciphertext of the
 * organization becomes permanently undecryptable.
 */
object OrgEncryptionKeys : Table("org_encryption_keys") {
    val orgId = javaUUID("org_id").references(Organizations.id)
    val wrappedDek = text("wrapped_dek")
    val keyVersion = integer("key_version").default(1)
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(orgId)
}
