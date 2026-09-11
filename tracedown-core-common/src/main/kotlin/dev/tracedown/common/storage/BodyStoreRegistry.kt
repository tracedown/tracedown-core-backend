package dev.tracedown.common.storage

import dev.tracedown.common.config.SecretGuard
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.util.VariableCrypto
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * One `body_stores` row. [secretEnc]/[secretIv] are the encrypted secret access
 * key; nothing outside this package decrypts them, and [toString] leaves them out.
 */
data class BodyStore(
    val id: UUID,
    val name: String,
    val kind: String,
    val mode: String,
    val endpoint: String?,
    val region: String?,
    val bucket: String?,
    val prefix: String?,
    val rootPath: String?,
    val accessKeyId: String?,
    internal val secretEnc: String?,
    internal val secretIv: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val inPlace: Boolean get() = mode == MODE_IN_PLACE
    val hasSecret: Boolean get() = secretEnc != null

    override fun toString(): String = "BodyStore(id=$id, name=$name, kind=$kind, mode=$mode)"

    companion object {
        const val KIND_S3 = "s3"
        const val KIND_FILESYSTEM = "filesystem"
        const val MODE_IMPORT = "import"
        const val MODE_IN_PLACE = "in_place"
        val KINDS = setOf(KIND_S3, KIND_FILESYSTEM)
        val MODES = setOf(MODE_IMPORT, MODE_IN_PLACE)

        fun fromRow(row: ResultRow) = BodyStore(
            id = row[BodyStores.id],
            name = row[BodyStores.name],
            kind = row[BodyStores.kind],
            mode = row[BodyStores.mode],
            endpoint = row[BodyStores.endpoint],
            region = row[BodyStores.region],
            bucket = row[BodyStores.bucket],
            prefix = row[BodyStores.prefix],
            rootPath = row[BodyStores.rootPath],
            accessKeyId = row[BodyStores.accessKeyId],
            secretEnc = row[BodyStores.secretEnc],
            secretIv = row[BodyStores.secretIv],
            createdAt = row[BodyStores.createdAt],
            updatedAt = row[BodyStores.updatedAt],
        )
    }
}

/**
 * Builds a confined [BodyStorageClient] per body store, from its row.
 *
 * The client is confined to the store's bucket + prefix (or root), so a URI
 * outside the store is refused exactly as the default store refuses one outside
 * itself. S3 clients dial through [StoreEndpointGuard] (no private addresses, no
 * redirects). Clients are cached by id and rebuilt when the row's `updated_at`
 * moves, so a change made by the gateway reaches the ingestor on its next read
 * of the row without any signal between them; [invalidate] drops one at once.
 *
 * Every service that touches stores calls [configure] at startup with its
 * deployment environment and the filesystem bases it permits.
 */
object BodyStoreRegistry {

    /** The most any one body is read through a store — larger ones are refused, not buffered. */
    const val MAX_BODY_BYTES: Long = 32L * 1024 * 1024

    data class Settings(
        /** Loopback / `localhost` endpoints (also over http) are allowed — anything but production. */
        val allowLoopbackHttp: Boolean,
        /** Directories a filesystem store's root must lie inside. */
        val filesystemBases: List<Path>,
        val timeoutSeconds: Long = 30,
    )

    @Volatile
    var settings: Settings = Settings(
        allowLoopbackHttp = !SecretGuard.isProduction(),
        filesystemBases = listOf(Path.of(DEFAULT_FILESYSTEM_BASE)),
    )
        private set

    private class Entry(val updatedAt: Instant, val client: BodyStorageClient)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    /**
     * [deploymentEnvironment] is the service's `deployment.environment`;
     * [filesystemBases] a comma-separated list of directories (default `/data`).
     */
    fun configure(deploymentEnvironment: String?, filesystemBases: String? = null, timeoutSeconds: Long = 30) {
        val bases = filesystemBases?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: listOf(DEFAULT_FILESYSTEM_BASE)
        settings = Settings(
            allowLoopbackHttp = !SecretGuard.isProduction(deploymentEnvironment),
            filesystemBases = bases.map { Path.of(it).toAbsolutePath().normalize() },
            timeoutSeconds = timeoutSeconds,
        )
        cache.clear()
    }

    /** The store row, or null. Opens its own transaction (or joins the caller's). */
    fun load(id: UUID): BodyStore? = transaction {
        BodyStores.selectAll().where { BodyStores.id eq id }.firstOrNull()?.let(BodyStore::fromRow)
    }

    /** The store the agent [agentId] writes to, or null for the default store. */
    fun storeOfAgent(agentId: Long): BodyStore? = transaction {
        ProbeAgents.join(BodyStores, JoinType.INNER, ProbeAgents.bodyStoreId, BodyStores.id)
            .select(BodyStores.columns)
            .where { ProbeAgents.id eq agentId }
            .firstOrNull()
            ?.let(BodyStore::fromRow)
    }

    /**
     * Where [store] confines its bodies. A filesystem root outside the permitted
     * bases throws [StorageConfinementException] — the store is then unusable,
     * rather than readable anywhere the process can read.
     */
    fun confinementOf(store: BodyStore): BodyConfinement = when (store.kind) {
        BodyStore.KIND_S3 -> BodyConfinement(s3Bucket = store.bucket, s3KeyPrefix = store.prefix ?: "")
        else -> {
            val root = Path.of(store.rootPath ?: throw StorageConfinementException("store ${store.id} has no root"))
                .toAbsolutePath().normalize()
            if (!isPermittedRoot(root)) {
                throw StorageConfinementException("store root $root is outside the permitted filesystem bases")
            }
            BodyConfinement(filesystemRoot = root)
        }
    }

    /** True when [root] lies inside a permitted base and is not the base itself. */
    fun isPermittedRoot(root: Path): Boolean =
        settings.filesystemBases.any { base -> root.startsWith(base) && root != base }

    /**
     * A client for [store], confined to it. Throws when the store cannot be used:
     * its endpoint is refused by [StoreEndpointGuard], its root is outside the
     * permitted bases, or its secret cannot be decrypted (no platform key).
     */
    fun clientFor(store: BodyStore): BodyStorageClient {
        cache[store.id]?.takeIf { it.updatedAt == store.updatedAt }?.let { return it.client }
        val client = build(store)
        cache[store.id] = Entry(store.updatedAt, client)
        return client
    }

    /** Drops a cached client — after the store changed or was deleted. */
    fun invalidate(id: UUID) {
        cache.remove(id)
    }

    /** The AAD a store's secret is bound to. */
    internal fun secretContext(id: UUID) = "body_store:$id"

    private fun build(store: BodyStore): BodyStorageClient {
        val confinement = confinementOf(store)
        if (store.kind != BodyStore.KIND_S3) return BodyStorageClient(confinement = confinement)

        val endpoint = store.endpoint ?: throw StoreEndpointBlockedException("store ${store.id} has no endpoint")
        StoreEndpointGuard.validate(endpoint, settings.allowLoopbackHttp)?.let { throw StoreEndpointBlockedException(it) }
        check(VariableCrypto.isInitialized()) { "the platform key is not configured — store credentials cannot be decrypted" }
        val secret = VariableCrypto.decryptBound(
            store.secretEnc ?: error("store ${store.id} has no secret"),
            store.secretIv ?: error("store ${store.id} has no secret IV"),
            secretContext(store.id),
        )
        return BodyStorageClient(
            s3Config = S3Config(
                endpoint = endpoint,
                accessKey = store.accessKeyId ?: error("store ${store.id} has no access key"),
                secretKey = secret,
                region = store.region?.takeIf { it.isNotBlank() } ?: "auto",
                timeoutSeconds = settings.timeoutSeconds,
            ),
            confinement = confinement,
            httpClient = StoreEndpointGuard.httpClient(settings.timeoutSeconds, settings.allowLoopbackHttp),
        )
    }

    private const val DEFAULT_FILESYSTEM_BASE = "/data"
}
