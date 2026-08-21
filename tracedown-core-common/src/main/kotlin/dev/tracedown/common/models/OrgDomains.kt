package dev.tracedown.common.models

import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.json.jsonb

object OrgDomains : Table("org_domains") {
    val id = uuid("id")
    val organizationId = uuid("organization_id").references(Organizations.id)
    val domain = varchar("domain", 256)
    val challenge = text("challenge")
    val verificationType = varchar("verification_type", 16)
    val status = varchar("status", 16)
    val verifiedAt = timestamp("verified_at").nullable()
    val exceptions = jsonb<List<String>>("exceptions", Json.Default).nullable()
    val wildcardEnabled = bool("wildcard_enabled").default(true)
    val lastCheckedAt = timestamp("last_checked_at").nullable()
    /** How the challenge record was placed: a DNS provider id, or a host's own method. */
    val dnsSetupMethod = varchar("dns_setup_method", 32).nullable()
    val dnsSetupAt = timestamp("dns_setup_at").nullable()
    val lapsed = bool("lapsed").default(false)
    val deleted = bool("deleted").default(false)
    val deletedAt = timestamp("deleted_at").nullable()
    val purgeAfter = timestamp("purge_after").nullable()

    override val primaryKey = PrimaryKey(id)
}
