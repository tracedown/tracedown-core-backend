package dev.tracedown.common.storage

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeSteps
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
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
    /** Why the field is at fault, returned as `details.reason` (see the error contract). */
    val reason: String? = null,
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
) {
    /** Never prints the secret — this type is logged whole by error handlers. */
    override fun toString(): String =
        "BodyStoreInput(name=$name, kind=$kind, mode=$mode, endpoint=$endpoint, region=$region, " +
            "bucket=$bucket, prefix=$prefix, rootPath=$rootPath, accessKeyId=$accessKeyId, " +
            "secretAccessKey=${if (secretAccessKey.isNullOrBlank()) "unset" else "***"})"
}

/** When a store last refused or failed a call. Cleared on the next success. */
@Serializable
data class BodyStoreFailure(val code: String, val at: String)

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
    /** The store's last failure, or null when its last call succeeded. */
    val lastFailure: BodyStoreFailure?,
    val createdAt: String,
    val updatedAt: String,
)

/**
 * What an agent needs to be configured for a store — printed with a bootstrap
 * token. [prefix] and [rootPath] already carry the agent's own sub-location
 * (`<prefix>/<slug>`, `<root>/<slug>`): an agent writes only there, and ingest
 * accepts a body only from there, so two agents on one store can never read or
 * overwrite each other's bodies. Print them verbatim.
 */
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
 * Body stores: where an organization's agents keep the response bodies they
 * capture, when not in the default store.
 *
 * - `import` — the agent writes to the store; at ingest each body is copied into
 *   the default store (and removed from the store), so retention is the platform's.
 * - `in_place` — the body stays where the agent wrote it and is read on demand
 *   with the store's credentials; the platform never deletes from it.
 *
 * **Ownership.** Every store belongs to one organization. A store of another
 * organization does not exist to a caller: it is not listed, not readable, not
 * assignable, and naming it is `body_store_not_found`. Ingest uses a store only
 * when the result's organization is the store's own.
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

    private val log = LoggerFactory.getLogger(BodyStoreService::class.java)

    @Volatile
    var defaultStore: DefaultBodyStore = DefaultBodyStore(kind = BodyStore.KIND_FILESYSTEM, rootPath = "/data/bodies", filesystemRoot = "/data/bodies")
        private set

    /** Describes the environment-configured default store (gateway startup). */
    fun configureDefault(store: DefaultBodyStore) {
        defaultStore = store
    }

    fun list(organizationId: UUID): List<BodyStoreView> = transaction {
        val counts = agentCounts()
        BodyStores.selectAll()
            .where { BodyStores.organizationId eq organizationId }
            .orderBy(BodyStores.name)
            .map { view(BodyStore.fromRow(it), counts) }
    }

    fun get(organizationId: UUID, id: UUID): BodyStoreView =
        transaction { view(require(organizationId, id), agentCounts()) }

    /**
     * The agent-facing description of [id], or null for the default store. The
     * prefix / root are the store's own; the caller appends the agent's slug
     * (`<prefix>/<slug>`, `<root>/<slug>`) when it prints the agent's settings,
     * exactly as the dashboard does from the store list.
     */
    fun summary(organizationId: UUID, id: UUID?): BodyStoreSummary? {
        if (id == null) return null
        val s = transaction {
            BodyStores.selectAll()
                .where { (BodyStores.id eq id) and (BodyStores.organizationId eq organizationId) }
                .firstOrNull()?.let(BodyStore::fromRow)
        } ?: return null
        return BodyStoreSummary(
            id = s.id.toString(),
            name = s.name,
            kind = s.kind,
            mode = s.mode,
            endpoint = s.endpoint,
            region = s.region,
            bucket = s.bucket,
            prefix = s.prefix,
            rootPath = s.rootPath,
        )
    }

    @Injectable(OP_WRITE)
    fun create(organizationId: UUID, input: BodyStoreInput, ctx: InterceptorContext = InterceptorContext()): BodyStoreView {
        val id = UUID.randomUUID()
        ctx.extra["action"] = "create"
        ctx.extra["storeId"] = id
        return transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                val v = validate(organizationId, input, existing = null)
                requireNameFree(organizationId, v.name, exceptId = null)
                val now = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                val secret = v.secret?.let { BodyStoreCrypto.encryptBound(it, BodyStoreCrypto.context(id)) }
                BodyStores.insert {
                    it[BodyStores.id] = id
                    it[BodyStores.organizationId] = organizationId
                    write(it, v)
                    it[secretEnc] = secret?.first
                    it[secretIv] = secret?.second
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                view(require(organizationId, id), agentCounts())
            }
        }
    }

    @Injectable(OP_WRITE)
    fun update(
        organizationId: UUID,
        id: UUID,
        input: BodyStoreInput,
        ctx: InterceptorContext = InterceptorContext(),
    ): BodyStoreView {
        ctx.extra["action"] = "update"
        ctx.extra["storeId"] = id
        return transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                // Locked for the rest of the transaction: a concurrent delete (or
                // another update) waits, so the in-use and location-lock checks
                // below still hold when the write lands.
                val existing = requireLocked(organizationId, id)
                val v = validate(organizationId, input, existing)
                requireNameFree(organizationId, v.name, exceptId = id)
                requireLocationUnlocked(existing, v)
                val newSecret = v.secret?.let { BodyStoreCrypto.encryptBound(it, BodyStoreCrypto.context(id)) }
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
                    // Whatever the store last failed at was about the old
                    // location or the old credentials.
                    it[lastFailureCode] = null
                    it[lastFailureAt] = null
                    it[updatedAt] = now
                }
                ctx.extra["changed"] = changedFields(existing, v)
                BodyStoreRegistry.invalidate(id)
                view(require(organizationId, id), agentCounts())
            }
        }
    }

    /** What [delete] let go of, for the audit entry. */
    data class DeleteOutcome(val agents: Int, val tokens: Int, val bodies: Int)

    /**
     * Deletes a store — refused with `body_store_in_use` while an active agent, an
     * outstanding bootstrap token or a stored body names it. History does not hold
     * a store: a used or expired token and a decommissioned agent let go of it here.
     *
     * With [forgetBodies] the refusal becomes a release: in the same transaction
     * the store's agents move back to the default store, its outstanding tokens
     * lose their stamp, and every step whose body lives in it forgets that body
     * (`response_body_storage_url` and `body_store_id` cleared,
     * `body_not_stored_reason = storeRemoved`). The objects themselves are never
     * touched — they are the store owner's, and after this the platform simply no
     * longer knows about them.
     */
    @Injectable(OP_WRITE)
    fun delete(
        organizationId: UUID,
        id: UUID,
        forgetBodies: Boolean = false,
        ctx: InterceptorContext = InterceptorContext(),
    ): DeleteOutcome {
        ctx.extra["action"] = "delete"
        ctx.extra["storeId"] = id
        return transaction {
            Interceptors.injectable(OP_WRITE, ctx) {
                requireLocked(organizationId, id)
                val now = Instant.now()
                AgentBootstrapTokens.update({
                    (AgentBootstrapTokens.bodyStoreId eq id) and
                        ((AgentBootstrapTokens.used eq true) or (AgentBootstrapTokens.expiresAt less now))
                }) { it[bodyStoreId] = null }
                ProbeAgents.update({ (ProbeAgents.bodyStoreId eq id) and (ProbeAgents.deleted eq true) }) {
                    it[bodyStoreId] = null
                }
                val agents = ProbeAgents.selectAll().where { ProbeAgents.bodyStoreId eq id }.count().toInt()
                val tokens = AgentBootstrapTokens.selectAll().where { AgentBootstrapTokens.bodyStoreId eq id }.count().toInt()
                if (!forgetBodies) {
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
                    return@injectable DeleteOutcome(0, 0, 0)
                }
                ProbeAgents.update({ ProbeAgents.bodyStoreId eq id }) { it[bodyStoreId] = null }
                AgentBootstrapTokens.update({ AgentBootstrapTokens.bodyStoreId eq id }) { it[bodyStoreId] = null }
                val bodies = ProbeSteps.update({ ProbeSteps.bodyStoreId eq id }) {
                    it[responseBodyStorageUrl] = null
                    it[bodyStoreId] = null
                    it[bodyNotStoredReason] = REASON_STORE_REMOVED
                }
                BodyStores.deleteWhere { BodyStores.id eq id }
                BodyStoreRegistry.invalidate(id)
                ctx.extra["forgotten"] = bodies
                DeleteOutcome(agents, tokens, bodies)
            }
        }
    }

    /**
     * Read-only probe of a store with its own credentials. Never throws for a
     * store that exists.
     *
     * `blocked_endpoint` is reported only when the endpoint as written is one
     * the guard refuses — something the caller can see for themselves, and the
     * only case where saying so helps. A *name* that resolved to an address the
     * guard refuses comes back as `unreachable` instead: telling a
     * settings-writer which of the names they type resolve inside the platform's
     * network would make this endpoint a DNS oracle, and "it did not answer" is
     * the truthful answer in both cases.
     */
    fun test(organizationId: UUID, id: UUID): BodyStoreTestResult {
        val store = transaction { require(organizationId, id) }
        val error = try {
            BodyStoreRegistry.clientFor(store).probe()
        } catch (e: StorageConfinementException) {
            "root_not_permitted"
        } catch (e: BodyStoreSecretException) {
            "secret_undecryptable"
        } catch (e: Exception) {
            failureReason(e)
        }?.let { reason ->
            if (reason == "blocked_endpoint" && !endpointVisiblyBlocked(store)) "unreachable" else reason
        }
        if (error == null) clearFailure(id) else recordFailure(id, error)
        return BodyStoreTestResult(ok = error == null, error = error)
    }

    /** Whether the store's endpoint is refused on its own text, with no lookup. */
    private fun endpointVisiblyBlocked(store: BodyStore): Boolean {
        val endpoint = store.endpoint ?: return true
        return StoreEndpointGuard.validate(endpoint, BodyStoreRegistry.settings.allowPrivateEndpoints) != null
    }

    /**
     * Points the agent [slug] at [storeId] (null = the default store). Bodies
     * already stored stay where they are; only the agent's next results follow —
     * and only once the agent itself is redeployed with the store's settings,
     * which this call cannot do for it.
     */
    @Injectable(OP_ASSIGN)
    fun assignAgent(
        organizationId: UUID,
        slug: String,
        storeId: UUID?,
        ctx: InterceptorContext = InterceptorContext(),
    ) {
        ctx.extra["target"] = "agent"
        ctx.extra["slug"] = slug
        storeId?.let { ctx.extra["storeId"] = it }
        transaction {
            Interceptors.injectable(OP_ASSIGN, ctx) {
                if (storeId != null) require(organizationId, storeId)
                val updated = ProbeAgents.update({ (ProbeAgents.slug eq slug) and (ProbeAgents.deleted eq false) }) {
                    it[bodyStoreId] = storeId
                }
                if (updated == 0) throw BodyStoreException(ErrorCodes.AGENT_NOT_FOUND, BodyStoreException.Kind.NOT_FOUND)
            }
        }
    }

    /**
     * Checks that a bootstrap token for [slug] may carry [storeId] — the store
     * belongs to [organizationId] and no hook on [OP_ASSIGN] refuses it. Call it
     * in the transaction that inserts the token. A token for the default store is
     * not an assignment and is not checked.
     */
    @Injectable(OP_ASSIGN)
    fun checkTokenAssignment(
        organizationId: UUID,
        slug: String,
        storeId: UUID?,
        ctx: InterceptorContext = InterceptorContext(),
    ) {
        if (storeId == null) return
        ctx.extra["target"] = "token"
        ctx.extra["slug"] = slug
        ctx.extra["storeId"] = storeId
        transaction { Interceptors.injectable(OP_ASSIGN, ctx) { require(organizationId, storeId) } }
    }

    // ── Health ──────────────────────────────────────────────────────────────

    /**
     * Records why a store last failed. Its own transaction and never throws: it
     * is called from read and ingest paths that must report their own outcome,
     * not this one's.
     */
    fun recordFailure(id: UUID, code: String) {
        try {
            transaction {
                BodyStores.update({ BodyStores.id eq id }) {
                    it[lastFailureCode] = code.take(64)
                    it[lastFailureAt] = Instant.now().truncatedTo(ChronoUnit.SECONDS)
                }
            }
        } catch (e: Exception) {
            log.debug("could not record the failure of body store {}: {}", id, e.message)
        }
    }

    /** Clears a store's last failure after a call it answered. Never throws. */
    fun clearFailure(id: UUID) {
        try {
            transaction {
                BodyStores.update({ (BodyStores.id eq id) and BodyStores.lastFailureCode.isNotNull() }) {
                    it[lastFailureCode] = null
                    it[lastFailureAt] = null
                }
            }
        } catch (e: Exception) {
            log.debug("could not clear the failure of body store {}: {}", id, e.message)
        }
    }

    /**
     * Logs a WARN when stores exist but this service has no `BODY_STORE_AES_KEY`
     * — every S3 store is then unusable and every body in one unreadable, which
     * otherwise only shows as a failure per call. Called at startup.
     */
    fun warnIfSecretsUnreadable(service: String) {
        if (BodyStoreCrypto.isInitialized()) return
        val any = try {
            transaction { !BodyStores.selectAll().where { BodyStores.kind eq BodyStore.KIND_S3 }.limit(1).empty() }
        } catch (e: Exception) {
            return
        }
        if (any) {
            log.warn(
                "{}: body stores with credentials exist but BODY_STORE_AES_KEY is not set — " +
                    "their bodies cannot be read or imported. Set it to the same value as the gateway's.",
                service,
            )
        }
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

    private fun invalid(code: String, field: String? = null, reason: String? = null): Nothing =
        throw BodyStoreException(code, BodyStoreException.Kind.INVALID, field, reason)

    private fun required(field: String): Nothing = invalid(ErrorCodes.STORE_FIELD_REQUIRED, field)

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private fun maxLen(field: String, value: String?, max: Int) {
        if (value != null && value.length > max) invalid(ErrorCodes.FIELD_TOO_LONG, field)
    }

    private fun validate(organizationId: UUID, input: BodyStoreInput, existing: BodyStore?): Valid {
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
                invalid(ErrorCodes.FIELD_INVALID, "rootPath", REASON_ROOT_NOT_ABSOLUTE)
            }
            if (!root.isAbsolute) invalid(ErrorCodes.FIELD_INVALID, "rootPath", REASON_ROOT_NOT_ABSOLUTE)
            val normalized = root.normalize()
            // A store's root is read with the service's own file permissions, so
            // it must sit inside a directory the operator set aside for bodies.
            if (!BodyStoreRegistry.isPermittedRoot(normalized)) {
                invalid(ErrorCodes.FIELD_INVALID, "rootPath", REASON_ROOT_OUTSIDE_BASES)
            }
            defaultStore.filesystemRoot?.let { Path.of(it).toAbsolutePath().normalize() }?.let { def ->
                if (normalized.startsWith(def) || def.startsWith(normalized)) {
                    invalid(ErrorCodes.FIELD_INVALID, "rootPath", REASON_OVERLAPS_DEFAULT)
                }
            }
            requireNoStoreOverlap(existing?.id, kind = kind, bucket = null, prefix = null, root = normalized)
            return Valid(name, kind, mode, null, null, null, null, normalized.toString(), null, null)
        }

        val endpoint = input.endpoint.clean()?.trimEnd('/') ?: required("endpoint")
        maxLen("endpoint", endpoint, 1024)
        StoreEndpointGuard.validate(endpoint, BodyStoreRegistry.settings.allowPrivateEndpoints)?.let {
            invalid(ErrorCodes.FIELD_INVALID, "endpoint", it)
        }
        val bucket = input.bucket.clean() ?: required("bucket")
        if (!BUCKET_RE.matches(bucket)) invalid(ErrorCodes.FIELD_INVALID, "bucket", REASON_INVALID_BUCKET)
        val region = input.region.clean()
        maxLen("region", region, 64)
        if (region != null && !REGION_RE.matches(region)) invalid(ErrorCodes.FIELD_INVALID, "region", REASON_INVALID_REGION)
        val prefix = input.prefix.clean()?.trim('/')?.takeIf { it.isNotEmpty() }
        maxLen("prefix", prefix, 255)
        if (prefix != null && (prefix.contains('\\') || prefix.split('/').any { it.isEmpty() || it == "." || it == ".." })) {
            invalid(ErrorCodes.FIELD_INVALID, "prefix", REASON_INVALID_PREFIX)
        }
        // An import store is emptied by the platform as bodies land in it. Rooted
        // at the top of a bucket that would be the whole bucket, so it must name
        // a prefix of its own.
        if (mode == BodyStore.MODE_IMPORT && prefix == null) required("prefix")
        val accessKeyId = input.accessKeyId.clean() ?: required("accessKeyId")
        maxLen("accessKeyId", accessKeyId, 255)
        val secret = input.secretAccessKey.clean()
        maxLen("secretAccessKey", secret, 1024)
        if (secret == null && !(existing != null && existing.kind == BodyStore.KIND_S3 && existing.hasSecret)) {
            required("secretAccessKey")
        }
        // Where the credentials would newly reach, they must be credentials the
        // caller actually holds — otherwise repointing a store is a way to spend
        // a secret someone else typed in on a location of one's own choosing.
        if (secret == null && existing != null && (
                existing.endpoint != endpoint || existing.bucket != bucket || existing.accessKeyId != accessKeyId
                )
        ) {
            required("secretAccessKey")
        }
        val def = defaultStore
        if (def.kind == BodyStore.KIND_S3 && def.bucket == bucket &&
            prefixesOverlap(def.prefix?.trim('/') ?: "", prefix ?: "")
        ) {
            // The default store's bodies belong to the platform; a store over
            // them would let an agent claim them as its own. The endpoint is not
            // part of the test: one bucket is reachable under many spellings of
            // its host, and a store is not made safe by naming a different one.
            invalid(ErrorCodes.FIELD_INVALID, "prefix", REASON_OVERLAPS_DEFAULT)
        }
        requireNoStoreOverlap(existing?.id, kind = kind, bucket = bucket, prefix = prefix ?: "", root = null)
        return Valid(name, kind, mode, endpoint, region, bucket, prefix, null, accessKeyId, secret)
    }

    /**
     * Two stores may not overlap. One store's client would otherwise read, and in
     * import mode delete, the other's bodies — including another organization's,
     * which is the whole point of the check being platform-wide.
     */
    private fun requireNoStoreOverlap(exceptId: UUID?, kind: String, bucket: String?, prefix: String?, root: Path?) {
        val rows = BodyStores.selectAll().where {
            if (exceptId == null) BodyStores.kind eq kind else (BodyStores.kind eq kind) and (BodyStores.id neq exceptId)
        }
        for (row in rows) {
            val other = BodyStore.fromRow(row)
            val clash = if (kind == BodyStore.KIND_S3) {
                other.bucket == bucket && prefixesOverlap(other.prefix?.trim('/') ?: "", prefix ?: "")
            } else {
                val otherRoot = other.rootPath?.let { Path.of(it).toAbsolutePath().normalize() } ?: continue
                root != null && (root.startsWith(otherRoot) || otherRoot.startsWith(root))
            }
            if (clash) {
                invalid(
                    ErrorCodes.FIELD_INVALID,
                    if (kind == BodyStore.KIND_S3) "prefix" else "rootPath",
                    REASON_OVERLAPS_STORE,
                )
            }
        }
    }

    /**
     * A store's location is frozen while any step's body lives in it: the URL
     * recorded then is only meaningful against the bucket, prefix or root it was
     * written under. The endpoint, the region, the name and the credentials may
     * still change — the same objects reached a different way.
     */
    private fun requireLocationUnlocked(existing: BodyStore, v: Valid) {
        val moved = existing.kind != v.kind ||
            existing.bucket != v.bucket ||
            (existing.prefix ?: "") != (v.prefix ?: "") ||
            existing.rootPath != v.rootPath
        if (!moved) return
        if (ProbeSteps.selectAll().where { ProbeSteps.bodyStoreId eq existing.id }.limit(1).empty()) return
        throw BodyStoreException(ErrorCodes.BODY_STORE_LOCATION_LOCKED, BodyStoreException.Kind.CONFLICT)
    }

    /** The field names an update changed, for the audit entry. Never a credential value. */
    private fun changedFields(existing: BodyStore, v: Valid): String {
        val changed = buildList {
            if (existing.name != v.name) add("name")
            if (existing.kind != v.kind) add("kind")
            if (existing.mode != v.mode) add("mode")
            if (existing.endpoint != v.endpoint) add("endpoint")
            if (existing.region != v.region) add("region")
            if (existing.bucket != v.bucket) add("bucket")
            if ((existing.prefix ?: "") != (v.prefix ?: "")) add("prefix")
            if (existing.rootPath != v.rootPath) add("rootPath")
            if (existing.accessKeyId != v.accessKeyId) add("accessKeyId")
            if (v.secret != null) add("secretAccessKey")
        }
        return changed.joinToString(",")
    }

    private fun prefixesOverlap(a: String, b: String): Boolean =
        a.isEmpty() || b.isEmpty() || a == b || a.startsWith("$b/") || b.startsWith("$a/")

    private fun requireNameFree(organizationId: UUID, name: String, exceptId: UUID?) {
        val taken = BodyStores.selectAll().where {
            val sameName = (BodyStores.organizationId eq organizationId) and (BodyStores.name eq name)
            if (exceptId == null) sameName else sameName and (BodyStores.id neq exceptId)
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

    private fun require(organizationId: UUID, id: UUID): BodyStore =
        BodyStores.selectAll()
            .where { (BodyStores.id eq id) and (BodyStores.organizationId eq organizationId) }
            .firstOrNull()?.let(BodyStore::fromRow)
            ?: throw BodyStoreException(ErrorCodes.BODY_STORE_NOT_FOUND, BodyStoreException.Kind.NOT_FOUND)

    /** [require] with the row locked until the transaction ends. */
    private fun requireLocked(organizationId: UUID, id: UUID): BodyStore =
        BodyStores.selectAll()
            .where { (BodyStores.id eq id) and (BodyStores.organizationId eq organizationId) }
            .forUpdate()
            .firstOrNull()?.let(BodyStore::fromRow)
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
        lastFailure = s.lastFailureCode?.let { BodyStoreFailure(it, (s.lastFailureAt ?: Instant.EPOCH).toString()) },
        createdAt = s.createdAt.toString(),
        updatedAt = s.updatedAt.toString(),
    )

    /** `body_not_stored_reason` when a store was removed with its bodies still recorded. */
    const val REASON_STORE_REMOVED = "storeRemoved"

    // `details.reason` values on `field_invalid`.
    const val REASON_ROOT_NOT_ABSOLUTE = "root_not_absolute"
    const val REASON_ROOT_OUTSIDE_BASES = "root_outside_bases"
    const val REASON_OVERLAPS_DEFAULT = "overlaps_default"
    const val REASON_OVERLAPS_STORE = "overlaps_store"
    const val REASON_INVALID_BUCKET = "invalid_bucket"
    const val REASON_INVALID_PREFIX = "invalid_prefix"
    const val REASON_INVALID_REGION = "invalid_region"

    /** S3 bucket naming: 3–63 lowercase letters, digits, dots and hyphens. */
    private val BUCKET_RE = Regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$")
    private val REGION_RE = Regex("^[A-Za-z0-9_-]+$")
}
