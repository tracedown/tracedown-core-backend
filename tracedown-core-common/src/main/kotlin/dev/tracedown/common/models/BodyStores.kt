package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * A place, other than the default store, where an organization's agents write
 * the response bodies they capture. See `storage/BodyStoreService` for the
 * modes and rules.
 *
 * A store belongs to one organization: only that organization's settings
 * writers see it, only its agents may be assigned it, and only its results may
 * record a body in it. The default store (configured by environment) is never a
 * row: a null `body_store_id` anywhere means "the default store".
 */
object BodyStores : Table("body_stores") {
    val id = javaUUID("id")

    /** The owning organization. A store of another organization does not exist to a caller. */
    val organizationId = javaUUID("organization_id").references(Organizations.id)
    val name = varchar("name", 64)

    /** `s3` or `filesystem`. */
    val kind = varchar("kind", 16)

    /** `import` or `in_place`. */
    val mode = varchar("mode", 16)
    val endpoint = text("endpoint").nullable()
    val region = varchar("region", 64).nullable()
    val bucket = varchar("bucket", 255).nullable()
    val prefix = varchar("prefix", 255).nullable()

    /** Filesystem stores: the directory, mounted into the gateway and the ingestor. */
    val rootPath = text("root_path").nullable()
    val accessKeyId = varchar("access_key_id", 255).nullable()

    /** Secret access key, AES-GCM under `BODY_STORE_AES_KEY` and bound to [id]. Never returned. */
    val secretEnc = text("secret_enc").nullable()
    val secretIv = varchar("secret_iv", 64).nullable()

    /** Why the store last refused or failed a call, and when — cleared on the next success. */
    val lastFailureCode = varchar("last_failure_code", 64).nullable()
    val lastFailureAt = timestamp("last_failure_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)

    /** Names are unique within an organization — matches the SQL constraint of the same name. */
    val orgNameUnique = uniqueIndex("body_stores_org_name_key", organizationId, name)
}
