package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * A place, other than the default store, where agents write the response
 * bodies they capture. See `storage/BodyStoreService` for the modes and rules.
 *
 * The default store (configured by environment) is never a row: a null
 * `body_store_id` anywhere means "the default store".
 */
object BodyStores : Table("body_stores") {
    val id = javaUUID("id")
    val name = varchar("name", 64).uniqueIndex()

    /** `s3` or `filesystem`. */
    val kind = varchar("kind", 16)

    /** `import` or `in_place`. */
    val mode = varchar("mode", 16)
    val endpoint = text("endpoint").nullable()
    val region = varchar("region", 64).nullable()
    val bucket = varchar("bucket", 255).nullable()
    val prefix = varchar("prefix", 255).nullable()

    /** Filesystem stores: the directory, mounted into the gateway, the ingestor and the worker. */
    val rootPath = text("root_path").nullable()
    val accessKeyId = varchar("access_key_id", 255).nullable()

    /** Secret access key, AES-GCM under the platform key and bound to [id]. Never returned. */
    val secretEnc = text("secret_enc").nullable()
    val secretIv = varchar("secret_iv", 64).nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(id)
}
