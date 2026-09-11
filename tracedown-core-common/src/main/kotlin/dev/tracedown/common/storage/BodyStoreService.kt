package dev.tracedown.common.storage

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.util.VariableCrypto
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.net.URI
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A refused body-store operation. The API maps [kind] to 400 / 404 / 409 and [code] to the error. */
class BodyStoreException(
    val code: String,
    val kind: Kind,
    /** The request field at fault, returned as `details.field`. */
    val field: String? = null,
    val details: JsonObject? = null,
) : RuntimeException(code) {
    enum class Kind { INVALID, NOT_FOUND, CONFLICT }
}

/** Create / update body. On update an omitted or blank [secretAccessKey] keeps the stored one. */
@Serializable
data class BodyStoreInput(
    val name: String? = null,
    val kind: String? = null,
    val mode: String? = null,
    val endpoint: String? = null,
    val region: String? = null,
    val bucket: String? = null,
    val prefix: String? = null,
    val rootPath: String? = null,
    val accessKeyId: String? = null,
    val secretAccessKey: String? = null,
)

/** A store as the API shows it: never the secret, only whether one is set. */
@Serializable
data class BodyStoreView(
    val id: String,
    val name: String,
    val kind: String,
    val mode: String,
    val endpoint: String?,
    val region: String?,
    val bucket: String?,
    val prefix: String?,
    val rootPath: String?,
    val accessKeyId: String?,
    val hasSecret: Boolean,
    /** Agents (not decommissioned) assigned to the store. */
    val agents: Int,
    val createdAt: String,
    val updatedAt: String,
)

/** What an agent needs to be configured for a store — printed with a bootstrap token. */
@Serializable
data class BodyStoreSummary(
    val id: String,
    val name: String,
    val kind: String,
    val mode: String,
    val endpoint: String?,
    val region: String?,
    val bucket: String?,
    val prefix: String?,
    val rootPath: String?,
)

/**
 * The default store, described read-only: the environment-configured one every
 * agent without a store writes to. [endpoint] and [filesystemRoot] are kept for
 * the overlap check and not serialized.
 */
@Serializable
data class DefaultBodyStore(
    val kind: String,
    val bucket: String? = null,
    val prefix: String? = null,
    val rootPath: String? = null,
    @Transient val endpoint: String? = null,
    @Transient val filesystemRoot: String? = null,
)

@Serializable
data class BodyStoreTestResult(val ok: Boolean, val error: String? = null)

/**
 * Body stores: where agents keep the response bodies they capture, when not in
 * the default store.
 *
 * - `import` — the agent writes to the store; at ingest each body is copied into
 *   the default store (and removed from the store), so retention is the platform's.
 * - `in_place` — the body stays where the agent wrote it and is read on demand
 *   with the store's credentials; the platform never deletes from it.
 *
 * All store logic lives here, not in the API routes, so a host application can
 * drive it from routes of its own. Store writes run inside the injectable
 * operation [OP_WRITE] (`extra.action` = `create` / `update` / `delete`,
 * `extra.storeId`); assigning a store to an agent or a bootstrap token runs
 * inside [OP_ASSIGN] (`extra.slug`, `extra.storeId`, `extra.target` = `agent` /
 * `token`). A before-hook that throws refuses the operation, atomically with it.
 *
 * Every method opens a transaction or joins the caller's.
 */
object BodyStoreService {

    const val OP_WRITE = "bodyStore.write"
    const val OP_ASSIGN = "agent.bodyStore.assign"

    @Volatile
    var defaultStore: DefaultBodyStore = DefaultBodyStore(kind = BodyStore.KIND_FILESYSTEM, rootPath = "/data/bodies", filesystemRoot = "/data/bodies")
        private set

    /** Describes the environment-configured default store (gateway startup). */
    fun configureDefault(store: DefaultBodyStore) {
        defaultStore = store
    }

    fun list(): List<BodyStoreView> = transaction {
        val counts = agentCounts()
        BodyStores.selectAll().orderBy(BodyStores.name).map { view(BodyStore.fromRow(it), counts) }
    }

    fun get(id: UUID): BodyStoreView = transaction { view(require(id), agentCounts()) }

    /** The agent-facing description of a store, or null for the default store. */
    fun summary(id: UUID?): BodyStoreSummary? {
        if (id == null) return null
        val s = BodyStoreRegistry.load(id) ?: return null
        return BodyStoreSummary(s.id.toString(), s.name, s.kind, s.mode, s.endpoint, s.region, s.bucket, s.prefix, s.rootPath)
    }

    @Injectable(OP_WRITE)
    fun create(input: BodyStoreInput, ctx: InterceptorContext = InterceptorContext()): BodyStoreView {
        val id = UUID.randomUUID()
        ctx.extra["action"] = "create"
        ctx.extra["storeId"] = id
        return transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                val v = validate(input, existing = null)
                requireNameFree(v.name, exceptId = null)
                val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val secret = v.secret?.let { VariableCrypto.encryptBound(it, BodyStoreRegistry.secretContext(id)) }
                BodyStores.insert {
                    it[BodyStores.id] = id
                    write(it, v)
                    it[secretEnc] = secret?.first
                    it[secretIv] = secret?.second
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                view(require(id), agentCounts())
            }
        }
    }

    @Injectable(OP_WRITE)
    fun update(id: UUID, input: BodyStoreInput, ctx: InterceptorContext = InterceptorContext()): BodyStoreView {
        ctx.extra["action"] = "update"
        ctx.extra["storeId"] = id
        return transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                val existing = require(id)
                val v = validate(input, existing)
                requireNameFree(v.name, exceptId = id)
                val newSecret = v.secret?.let { VariableCrypto.encryptBound(it, BodyStoreRegistry.secretContext(id)) }
                // Strictly later than the last write, so every cached client of
                // the old row is replaced on its next read.
                val now = Instant.now().truncatedTo(ChronoUnit.SECONDS).let {
                    if (it > existing.updatedAt) it else existing.updatedAt.plusSeconds(1)
                }
                BodyStores.update({ BodyStores.id eq id }) {
                    write(it, v)
                    when {
                        v.kind != BodyStore.KIND_S3 -> {
                            it[secretEnc] = null
                            it[secretIv] = null
                        }
                        newSecret != null -> {
                            it[secretEnc] = newSecret.first
                            it[secretIv] = newSecret.second
                        }
                    }
                    it[updatedAt] = now
                }
                BodyStoreRegistry.invalidate(id)
                view(require(id), agentCounts())
            }
        }
    }

    /**
     * Deletes a store — refused with `body_store_in_use` while an active agent, an
     * outstanding bootstrap token or a stored body names it. History does not hold
     * a store: a used or expired token and a decommissioned agent let go of it here.
     */
    @Injectable(OP_WRITE)
    fun delete(id: UUID, ctx: InterceptorContext = InterceptorContext()) {
        ctx.extra["action"] = "delete"
        ctx.extra["storeId"] = id
        transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                require(id)
                val now = Instant.now()
                AgentBootstrapTokens.update({
                    (AgentBootstrapTokens.bodyStoreId eq id) and
                        ((AgentBootstrapTokens.used eq true) or (AgentBootstrapTokens.expiresAt less now))
                }) { it[bodyStoreId] = null }
                ProbeAgents.update({ (ProbeAgents.bodyStoreId eq id) and (ProbeAgents.deleted eq true) }) {
                    it[bodyStoreId] = null
                }
                val agents = ProbeAgents.selectAll().where { ProbeAgents.bodyStoreId eq id }.count()
                val tokens = AgentBootstrapTokens.selectAll().where { AgentBootstrapTokens.bodyStoreId eq id }.count()
                val bodies = !ProbeSteps.selectAll().where { ProbeSteps.bodyStoreId eq id }.limit(1).empty()
                if (agents > 0 || tokens > 0 || bodies) {
                    throw BodyStoreException(
                        ErrorCodes.BODY_STORE_IN_USE, BodyStoreException.Kind.CONFLICT,
                        details = buildJsonObject {
                            put("agents", agents)
                            put("tokens", tokens)
                            put("bodies", bodies)
                        },
                    )
                }
                BodyStores.deleteWhere { BodyStores.id eq id }
                BodyStoreRegistry.invalidate(id)
            }
        }
    }

    /** Read-only probe of a store with its own credentials. Never throws for a store that exists. */
    fun test(id: UUID): BodyStoreTestResult {
        val store = BodyStoreRegistry.load(id)
            ?: throw BodyStoreException(ErrorCodes.BODY_STORE_NOT_FOUND, BodyStoreException.Kind.NOT_FOUND)
        return try {
            val error = BodyStoreRegistry.clientFor(store).probe()
            BodyStoreTestResult(ok = error == null, error = error)
        } catch (e: StorageConfinementException) {
            BodyStoreTestResult(ok = false, error = "root_not_permitted")
        } catch (e: Exception) {
            BodyStoreTestResult(ok = false, error = failureReason(e))
        }
    }

    /**
     * Points the agent [slug] at [storeId] (null = the default store). Bodies
     * already stored stay where they are; only the agent's next results follow.
     */
    @Injectable(OP_ASSIGN)
    fun assignAgent(slug: String, storeId: UUID?, ctx: InterceptorContext = InterceptorContext()) {
        ctx.extra["target"] = "agent"
        ctx.extra["slug"] = slug
        storeId?.let { ctx.extra["storeId"] = it }
        transaction {
            Interceptors.injectable(OP_ASSIGN, ctx) {
                if (storeId != null) require(storeId)
                val updated = ProbeAgents.update({ (ProbeAgents.slug eq slug) and (ProbeAgents.deleted eq false) }) {
                    it[bodyStoreId] = storeId
                }
                if (updated == 0) throw BodyStoreException(ErrorCodes.NOT_FOUND, BodyStoreException.Kind.NOT_FOUND)
            }
        }
    }

    /**
     * Checks that a bootstrap token for [slug] may carry [storeId] — the store
     * exists and no hook on [OP_ASSIGN] refuses it. Call it in the transaction
     * that inserts the token. A token for the default store is not an assignment
     * and is not checked.
     */
    @Injectable(OP_ASSIGN)
    fun checkTokenAssignment(slug: String, storeId: UUID?, ctx: InterceptorContext = InterceptorContext()) {
        if (storeId == null) return
        ctx.extra["target"] = "token"
        ctx.extra["slug"] = slug
        ctx.extra["storeId"] = storeId
        transaction { Interceptors.injectable(OP_ASSIGN, ctx) { require(storeId) } }
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private class Valid(
        val name: String,
        val kind: String,
        val mode: String,
        val endpoint: String?,
        val region: String?,
        val bucket: String?,
        val prefix: String?,
        val rootPath: String?,
        val accessKeyId: String?,
        /** A new secret to store; null keeps the existing one. */
        val secret: String?,
    )

    private fun invalid(code: String, field: String? = null): Nothing =
        throw BodyStoreException(code, BodyStoreException.Kind.INVALID, field)

    private fun required(field: String): Nothing = invalid(ErrorCodes.STORE_FIELD_REQUIRED, field)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun maxLen(field: String, value: String?, max: Int) {
        if (value != null && value.length > max) invalid(ErrorCodes.FIELD_TOO_LONG, field)
    }

    private fun validate(input: BodyStoreInput, existing: BodyStore?): Valid {
        val name = input.name.clean() ?: required("name")
        maxLen("name", name, 64)
        val kind = input.kind.clean()?.lowercase() ?: required("kind")
        if (kind !in BodyStore.KINDS) invalid(ErrorCodes.INVALID_STORE_KIND, "kind")
        val mode = input.mode.clean()?.lowercase() ?: required("mode")
        if (mode !in BodyStore.MODES) invalid(ErrorCodes.INVALID_STORE_MODE, "mode")

        if (kind == BodyStore.KIND_FILESYSTEM) {
            val raw = input.rootPath.clean() ?: required("rootPath")
            maxLen("rootPath", raw, 1024)
            val root = try {
                Path.of(raw)
            } catch (_: Exception) {
                invalid(ErrorCodes.FIELD_INVALID, "rootPath")
            }
            if (!root.isAbsolute) invalid(ErrorCodes.FIELD_INVALID, "rootPath")
            val normalized = root.normalize()
            // A store's root is read with the service's own file permissions, so
            // it must sit inside a directory the operator set aside for bodies.
            if (!BodyStoreRegistry.isPermittedRoot(normalized)) invalid(ErrorCodes.FIELD_INVALID, "rootPath")
            defaultStore.filesystemRoot?.let { Path.of(it).toAbsolutePath().normalize() }?.let { def ->
                if (normalized.startsWith(def) || def.startsWith(normalized)) invalid(ErrorCodes.FIELD_INVALID, "rootPath")
            }
            return Valid(name, kind, mode, null, null, null, null, normalized.toString(), null, null)
        }

        val endpoint = input.endpoint.clean()?.trimEnd('/') ?: required("endpoint")
        maxLen("endpoint", endpoint, 1024)
        if (StoreEndpointGuard.validate(endpoint, BodyStoreRegistry.settings.allowLoopbackHttp) != null) {
            invalid(ErrorCodes.FIELD_INVALID, "endpoint")
        }
        val bucket = input.bucket.clean() ?: required("bucket")
        if (!BUCKET_RE.matches(bucket)) invalid(ErrorCodes.FIELD_INVALID, "bucket")
        val region = input.region.clean()
        maxLen("region", region, 64)
        if (region != null && !REGION_RE.matches(region)) invalid(ErrorCodes.FIELD_INVALID, "region")
        val prefix = input.prefix.clean()?.trim('/')?.takeIf { it.isNotEmpty() }
        maxLen("prefix", prefix, 255)
        if (prefix != null && (prefix.contains('\\') || prefix.split('/').any { it.isEmpty() || it == "." || it == ".." })) {
            invalid(ErrorCodes.FIELD_INVALID, "prefix")
        }
        val accessKeyId = input.accessKeyId.clean() ?: required("accessKeyId")
        maxLen("accessKeyId", accessKeyId, 255)
        val secret = input.secretAccessKey.clean()
        maxLen("secretAccessKey", secret, 1024)
        if (secret == null && !(existing != null && existing.kind == BodyStore.KIND_S3 && existing.hasSecret)) {
            required("secretAccessKey")
        }
        val def = defaultStore
        if (def.kind == BodyStore.KIND_S3 && def.bucket == bucket && sameEndpoint(def.endpoint, endpoint) &&
            prefixesOverlap(def.prefix?.trim('/') ?: "", prefix ?: "")
        ) {
            // The default store's bodies belong to the platform; a store over
            // them would let an agent claim them as its own.
            invalid(ErrorCodes.FIELD_INVALID, "prefix")
        }
        return Valid(name, kind, mode, endpoint, region, bucket, prefix, null, accessKeyId, secret)
    }

    private fun sameEndpoint(a: String?, b: String): Boolean {
        if (a == null) return false
        fun norm(e: String) = try {
            URI(e.trim()).let { "${it.host?.lowercase()}:${it.port}" }
        } catch (_: Exception) {
            e.trim().lowercase()
        }
        return norm(a) == norm(b)
    }

    private fun prefixesOverlap(a: String, b: String): Boolean =
        a.isEmpty() || b.isEmpty() || a == b || a.startsWith("$b/") || b.startsWith("$a/")

    private fun requireNameFree(name: String, exceptId: UUID?) {
        val taken = BodyStores.selectAll().where {
            if (exceptId == null) BodyStores.name eq name else (BodyStores.name eq name) and (BodyStores.id neq exceptId)
        }.limit(1).any()
        if (taken) throw BodyStoreException(ErrorCodes.BODY_STORE_NAME_TAKEN, BodyStoreException.Kind.CONFLICT, "name")
    }

    private fun write(it: org.jetbrains.exposed.v1.core.statements.UpdateBuilder<*>, v: Valid) {
        it[BodyStores.name] = v.name
        it[BodyStores.kind] = v.kind
        it[BodyStores.mode] = v.mode
        it[BodyStores.endpoint] = v.endpoint
        it[BodyStores.region] = v.region
        it[BodyStores.bucket] = v.bucket
        it[BodyStores.prefix] = v.prefix
        it[BodyStores.rootPath] = v.rootPath
        it[BodyStores.accessKeyId] = v.accessKeyId
    }

    private fun require(id: UUID): BodyStore =
        BodyStores.selectAll().where { BodyStores.id eq id }.firstOrNull()?.let(BodyStore::fromRow)
            ?: throw BodyStoreException(ErrorCodes.BODY_STORE_NOT_FOUND, BodyStoreException.Kind.NOT_FOUND)

    private fun agentCounts(): Map<UUID, Int> =
        ProbeAgents.selectAll()
            .where { ProbeAgents.deleted eq false }
            .mapNotNull { it[ProbeAgents.bodyStoreId] }
            .groupingBy { it }.eachCount()

    private fun view(s: BodyStore, counts: Map<UUID, Int>) = BodyStoreView(
        id = s.id.toString(),
        name = s.name,
        kind = s.kind,
        mode = s.mode,
        endpoint = s.endpoint,
        region = s.region,
        bucket = s.bucket,
        prefix = s.prefix,
        rootPath = s.rootPath,
        accessKeyId = s.accessKeyId,
        hasSecret = s.hasSecret,
        agents = counts[s.id] ?: 0,
        createdAt = s.createdAt.toString(),
        updatedAt = s.updatedAt.toString(),
    )

    /** S3 bucket naming: 3–63 lowercase letters, digits, dots and hyphens. */
    private val BUCKET_RE = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    private val REGION_RE = Regex("^[A-Za-z0-9_-]+$")
}
