package dev.tracedown.common.storage

import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.ProbeAgents
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import software.amazon.awssdk.http.SdkHttpClient
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Raised when a store's secret access key cannot be decrypted — a missing or a rotated key. */
class BodyStoreSecretException(message: String) : RuntimeException(message)

/**
 * One `body_stores` row. [secretEnc]/[secretIv] are the encrypted secret access
 * key; nothing outside this package decrypts them, and [toString] leaves them out.
 */
data class BodyStore(
    val id: UUID,
    val organizationId: UUID,
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
    val lastFailureCode: String?,
    val lastFailureAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    val inPlace: Boolean get() = mode == MODE_IN_PLACE
    val hasSecret: Boolean get() = secretEnc != null

    /** The S3 key prefix the agent [slug] writes under — its own corner of the store. */
    fun agentPrefix(slug: String): String = listOf(prefix?.trim('/').orEmpty(), slug)
        .filter { it.isNotEmpty() }
        .joinToString("/")

    override fun toString(): String = "BodyStore(id=$id, org=$organizationId, name=$name, kind=$kind, mode=$mode)"

    companion object {
        const val KIND_S3 = "s3"
        const val KIND_FILESYSTEM = "filesystem"
        const val MODE_IMPORT = "import"
        const val MODE_IN_PLACE = "in_place"
        val KINDS = setOf(KIND_S3, KIND_FILESYSTEM)
        val MODES = setOf(MODE_IMPORT, MODE_IN_PLACE)

        fun fromRow(row: ResultRow) = BodyStore(
            id = row[BodyStores.id],
            organizationId = row[BodyStores.organizationId],
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
            lastFailureCode = row[BodyStores.lastFailureCode],
            lastFailureAt = row[BodyStores.lastFailureAt],
            createdAt = row[BodyStores.createdAt],
            updatedAt = row[BodyStores.updatedAt],
        )
    }
}

/** A store row together with the slug of the agent that wrote into it. */
data class AgentBodyStore(val store: BodyStore, val slug: String)

/**
 * Builds a confined [BodyStorageClient] per body store, from its row.
 *
 * The client is confined to the store's bucket + prefix (or root), so a URI
 * outside the store is refused exactly as the default store refuses one outside
 * itself. S3 clients dial through [StoreEndpointGuard] (no private addresses, no
 * redirects, unless the operator allowed private endpoints). Clients are cached
 * by id and rebuilt when the row's `updated_at` moves, so a change made by the
 * gateway reaches the ingestor on its next read of the row without any signal
 * between them; [invalidate] drops one at once, and a client whose store no
 * longer exists is evicted on the next sweep of the cache.
 *
 * Every service that touches stores calls [configure] at startup with the
 * filesystem bases it permits and whether private endpoints are allowed.
 */
object BodyStoreRegistry {

    /** The most any one body is read through a store — larger ones are refused, not buffered. */
    const val MAX_BODY_BYTES: Long = 32L * 1024 * 1024

    data class Settings(
        /**
         * `http://` endpoints and private, CGNAT, loopback or internal-suffix
         * hosts are allowed (`BODY_STORE_PRIVATE_ENDPOINTS`). Off by default.
         */
        val allowPrivateEndpoints: Boolean,
        /**
         * Directories a filesystem store's root must lie inside
         * (`BODY_STORE_FILESYSTEM_BASES`). Empty means filesystem stores are not
         * available at all — there is no default: a directory the process may read
         * bodies from is a decision for the operator, never for this code.
         */
        val filesystemBases: List<Path>,
        val timeoutSeconds: Long = 30,
    )

    @Volatile
    var settings: Settings = Settings(allowPrivateEndpoints = false, filesystemBases = emptyList())
        private set

    private class Entry(val updatedAt: Instant, val client: BodyStorageClient)

    private val cache = ConcurrentHashMap<UUID, Entry>()

    /** Guarded HTTP clients, one per (allowPrivateEndpoints, timeoutSeconds). */
    private val httpClients = ConcurrentHashMap<Pair<Boolean, Long>, SdkHttpClient>()

    /**
     * [filesystemBases] is a comma-separated list of directories (unset = no
     * filesystem stores); [allowPrivateEndpoints] is `BODY_STORE_PRIVATE_ENDPOINTS`.
     */
    fun configure(
        filesystemBases: String? = null,
        allowPrivateEndpoints: Boolean = false,
        timeoutSeconds: Long = 30,
    ) {
        val bases = filesystemBases?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        settings = Settings(
            allowPrivateEndpoints = allowPrivateEndpoints,
            // A base may itself be a symlink (a mount laid out that way); resolve
            // it once here so a store root under it compares equal.
            filesystemBases = bases.map { realPath(Path.of(it)) },
            timeoutSeconds = timeoutSeconds,
        )
        cache.clear()
    }

    /** The store row, or null. Opens its own transaction (or joins the caller's). */
    fun load(id: UUID): BodyStore? = transaction {
        BodyStores.selectAll().where { BodyStores.id eq id }.firstOrNull()?.let(BodyStore::fromRow)
    }

    /** The store the agent [agentId] writes to with that agent's slug, or null for the default store. */
    fun storeOfAgent(agentId: Long): AgentBodyStore? = transaction {
        ProbeAgents.join(BodyStores, JoinType.INNER, ProbeAgents.bodyStoreId, BodyStores.id)
            .select(BodyStores.columns + ProbeAgents.slug)
            .where { ProbeAgents.id eq agentId }
            .firstOrNull()
            ?.let { AgentBodyStore(BodyStore.fromRow(it), it[ProbeAgents.slug]) }
    }

    /**
     * Where [store] confines its bodies. With [agentSlug] the confinement narrows
     * to that agent's own sub-location (`<prefix>/<slug>`, `<root>/<slug>`) —
     * which is what an agent is configured to write to, and the only place ingest
     * accepts a body of that agent from.
     *
     * A filesystem root outside the permitted bases throws
     * [StorageConfinementException] — the store is then unusable, rather than
     * readable anywhere the process can read.
     */
    fun confinementOf(store: BodyStore, agentSlug: String? = null): BodyConfinement = when (store.kind) {
        BodyStore.KIND_S3 -> BodyConfinement(
            s3Bucket = store.bucket,
            s3KeyPrefix = if (agentSlug == null) store.prefix ?: "" else store.agentPrefix(agentSlug),
        )
        else -> {
            val raw = Path.of(store.rootPath ?: throw StorageConfinementException("store ${store.id} has no root"))
            val root = realPath(raw)
            if (!isPermittedRoot(root)) {
                throw StorageConfinementException("store root $root is outside the permitted filesystem bases")
            }
            BodyConfinement(filesystemRoot = if (agentSlug == null) root else root.resolve(agentSlug))
        }
    }

    /**
     * True when [root] lies inside a permitted base and is not the base itself.
     * With no bases configured nothing is permitted: filesystem stores are off.
     */
    fun isPermittedRoot(root: Path): Boolean {
        val resolved = realPath(root)
        return settings.filesystemBases.any { base -> resolved.startsWith(base) && resolved != base }
    }

    /**
     * A client for [store], confined to it. Throws when the store cannot be used:
     * its endpoint is refused by [StoreEndpointGuard], its root is outside the
     * permitted bases, or its secret cannot be decrypted ([BodyStoreSecretException]).
     */
    fun clientFor(store: BodyStore, agentSlug: String? = null): BodyStorageClient {
        // A per-agent client is narrower than the cached store-wide one and is
        // only used for the length of one result; it is built fresh.
        if (agentSlug != null) return build(store, agentSlug)
        cache[store.id]?.takeIf { it.updatedAt == store.updatedAt }?.let { return it.client }
        val client = build(store, null)
        cache[store.id] = Entry(store.updatedAt, client)
        return client
    }

    /** Drops a cached client — after the store changed or was deleted. */
    fun invalidate(id: UUID) {
        cache.remove(id)
    }

    /**
     * Drops cached clients for stores that no longer exist. A deleted store is
     * invalidated by whoever deleted it, but that happens in the gateway's
     * process; every other service holds its own cache and learns here. Cheap:
     * one query over the ids it is actually holding.
     */
    fun evictDeleted() {
        val held = cache.keys.toList()
        if (held.isEmpty()) return
        val alive = try {
            transaction { BodyStores.selectAll().map { it[BodyStores.id] }.toSet() }
        } catch (e: Exception) {
            return
        }
        for (id in held) if (id !in alive) cache.remove(id)
    }

    /**
     * The one guarded HTTP client every S3 store shares, rebuilt only when the
     * settings it is made from change. Clients are built per store — and, for an
     * agent's own corner of one, per result — so a connection pool each would be
     * a pool per body written.
     */
    private fun guardedHttpClient(): SdkHttpClient {
        val current = settings.allowPrivateEndpoints to settings.timeoutSeconds
        httpClients[current]?.let { return it }
        // Never closed on replacement: a client already handed out is held by
        // cached store clients, and the key only changes when an operator
        // reconfigures the process, which happens once at startup.
        return httpClients.computeIfAbsent(current) { (allowPrivate, timeout) ->
            StoreEndpointGuard.httpClient(timeout, allowPrivate)
        }
    }

    private fun realPath(path: Path): Path {
        val absolute = path.toAbsolutePath().normalize()
        return try {
            absolute.toRealPath()
        } catch (_: Exception) {
            absolute
        }
    }

    private fun build(store: BodyStore, agentSlug: String?): BodyStorageClient {
        val confinement = confinementOf(store, agentSlug)
        if (store.kind != BodyStore.KIND_S3) return BodyStorageClient(confinement = confinement)

        val endpoint = store.endpoint ?: throw StoreEndpointBlockedException("store ${store.id} has no endpoint")
        StoreEndpointGuard.validate(endpoint, settings.allowPrivateEndpoints)?.let { throw StoreEndpointBlockedException(it) }
        if (!BodyStoreCrypto.isInitialized()) {
            throw BodyStoreSecretException("BODY_STORE_AES_KEY is not configured — store ${store.id} cannot be opened")
        }
        val secret = try {
            BodyStoreCrypto.decryptBound(
                store.secretEnc ?: throw BodyStoreSecretException("store ${store.id} has no secret"),
                store.secretIv ?: throw BodyStoreSecretException("store ${store.id} has no secret IV"),
                BodyStoreCrypto.context(store.id),
            )
        } catch (e: BodyStoreSecretException) {
            throw e
        } catch (e: Exception) {
            throw BodyStoreSecretException("store ${store.id} secret does not decrypt under BODY_STORE_AES_KEY")
        }
        return BodyStorageClient(
            s3Config = S3Config(
                endpoint = endpoint,
                accessKey = store.accessKeyId ?: throw BodyStoreSecretException("store ${store.id} has no access key"),
                secretKey = secret,
                region = store.region?.takeIf { it.isNotBlank() } ?: "auto",
                timeoutSeconds = settings.timeoutSeconds,
            ),
            confinement = confinement,
            httpClient = guardedHttpClient(),
        )
    }
}
