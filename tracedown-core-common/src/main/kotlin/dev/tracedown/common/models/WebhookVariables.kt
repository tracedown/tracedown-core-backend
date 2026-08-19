package dev.tracedown.common.models

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

/**
 * Per-webhook variables (`$h.<key>`), resolved only at webhook delivery —
 * never visible to probe scripts. Secrets are envelope-encrypted with the
 * AAD scope `webhook:<webhookId>`, binding the ciphertext to its webhook.
 */
object WebhookVariables : Table("webhook_variables") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val webhookId = uuid("webhook_id").references(WebhookDeliveries.id)
    val createdBy = uuid("created_by").references(Users.id).nullable()
    val key = varchar("key", 64)
    val value = text("value")
    val secret = bool("secret")
    val encrypted = bool("encrypted").default(true)
    val valueIv = varchar("value_iv", 64).nullable()
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
