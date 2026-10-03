package dev.tracedown.gateway.controllers.results

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStore
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreSecretException
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.common.storage.BodyTooLargeException
import dev.tracedown.common.storage.StorageConfinementException
import dev.tracedown.common.storage.StorageUnconfiguredException
import dev.tracedown.common.storage.StorageUri
import dev.tracedown.common.storage.StorageUriException
import dev.tracedown.common.storage.StoreEndpointGuard
import dev.tracedown.gateway.data.results.ProbeResultDetail
import dev.tracedown.gateway.data.results.ProbeResultSummary
import dev.tracedown.gateway.data.results.ProbeStepSummary
import dev.tracedown.gateway.data.results.ResultPageAt
import dev.tracedown.gateway.data.results.StepBodyContent
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.GoneException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.requireCachedPermissions
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.jdbc.andWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory

/**
 * Queries probe results for a service.
 * Resolves the service's parent chain and checks resource-level read access.
 */
object ProbeResultController {

    private val log = LoggerFactory.getLogger(ProbeResultController::class.java)

    private var storageClient: BodyStorageClient? = null

    /** The default store's client, for tests that swap it and put it back. */
    internal val defaultStorage: BodyStorageClient? get() = storageClient

    /** Injects the body storage client for response body retrieval. */
    fun init(storage: BodyStorageClient) {
        this.storageClient = storage
    }

    /**
     * Lists probe results for a service, ordered by most recent first (ties by
     * id). [since], when given, keeps only results started at or after it,
     * floored to the second the start times are kept to.
     */
    fun list(orgId: UUID, serviceId: UUID, userId: UUID, pfs: PfsParams, since: Instant? = null): Page<ProbeResultSummary> {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            val parentChain = listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
            if (!canAccessResource(cached, "service", ctx.serviceId, parentChain)) {
                throw NotFoundException()
            }

            val query = ProbeResults
                .join(ProbeAgents, JoinType.LEFT, ProbeResults.probeAgentId, ProbeAgents.id)
                .select(ProbeResults.columns + ProbeAgents.slug)
                .where {
                    (ProbeResults.serviceId eq serviceId) and
                        (ProbeResults.organizationId eq orgId)
                }
                .orderBy(ProbeResults.startedAt to SortOrder.DESC, ProbeResults.id to SortOrder.DESC)
            // Run times are stored to the second; a `since` with a fraction would
            // miss a run started in its own second.
            if (since != null) query.andWhere { ProbeResults.startedAt greaterEq since.truncatedTo(ChronoUnit.SECONDS) }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { row ->
                ProbeResultSummary(
                    id = row[ProbeResults.id].toString(),
                    status = row[ProbeResults.status],
                    runDurationMs = row[ProbeResults.runDurationMs],
                    totalResponseMs = row[ProbeResults.totalResponseMs],
                    startedAt = row[ProbeResults.startedAt].toString(),
                    agentSlug = row[ProbeAgents.slug],
                )
            }
            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /**
     * The page of the (most-recent-first) history on which results started at
     * or before [at] begin: the count of newer results divided by the page
     * size, plus one, clamped to the last page. The dashboard uses it to jump
     * the pager to a date without turning the list into a filter, so the
     * neighbours of that moment stay one click away.
     */
    fun pageAt(orgId: UUID, serviceId: UUID, userId: UUID, at: Instant, pageSize: Int): ResultPageAt {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            val parentChain = listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
            if (!canAccessResource(cached, "service", ctx.serviceId, parentChain)) {
                throw NotFoundException()
            }
            val scope = (ProbeResults.serviceId eq serviceId) and (ProbeResults.organizationId eq orgId)
            val total = ProbeResults.selectAll().where { scope }.count()
            val newer = ProbeResults.selectAll().where { scope and (ProbeResults.startedAt greater at) }.count()
            val limit = PfsParams(pageSize = pageSize).limit
            val lastPage = ((total + limit - 1) / limit).toInt().coerceAtLeast(1)
            val page = (newer / limit).toInt() + 1
            ResultPageAt(page = page.coerceAtMost(lastPage), total = total)
        }
    }

    /** Returns a single probe result with all steps. */
    fun get(orgId: UUID, serviceId: UUID, resultId: UUID, userId: UUID): ProbeResultDetail {
        return transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            val parentChain = listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
            if (!canAccessResource(cached, "service", ctx.serviceId, parentChain)) {
                throw NotFoundException()
            }

            val row = ProbeResults
                .join(ProbeAgents, JoinType.LEFT, ProbeResults.probeAgentId, ProbeAgents.id)
                .select(ProbeResults.columns + ProbeAgents.slug)
                .where {
                    (ProbeResults.id eq resultId) and
                        (ProbeResults.serviceId eq serviceId) and
                        (ProbeResults.organizationId eq orgId)
                }
                .firstOrNull() ?: throw NotFoundException()

            val steps = ProbeSteps.selectAll()
                .where { ProbeSteps.probeResultId eq resultId }
                .orderBy(ProbeSteps.stepNum, SortOrder.ASC)
                .map { step ->
                    ProbeStepSummary(
                        id = step[ProbeSteps.id].toString(),
                        stepNum = step[ProbeSteps.stepNum],
                        requestUrl = step[ProbeSteps.requestUrl],
                        statusCode = step[ProbeSteps.statusCode],
                        responseTimeMs = step[ProbeSteps.responseTimeMs],
                        dnsMs = step[ProbeSteps.dnsMs],
                        connectMs = step[ProbeSteps.connectMs],
                        tlsMs = step[ProbeSteps.tlsMs],
                        ttfbMs = step[ProbeSteps.ttfbMs],
                        transferMs = step[ProbeSteps.transferMs],
                        responseSizeBytes = step[ProbeSteps.responseSizeBytes],
                        error = step[ProbeSteps.error],
                        assertionResults = step[ProbeSteps.assertionResults] as? JsonArray,
                        headers = step[ProbeSteps.headers] as? JsonObject,
                        hasBody = step[ProbeSteps.responseBodyStorageUrl] != null,
                        bodyNotStoredReason = step[ProbeSteps.bodyNotStoredReason],
                    )
                }

            ProbeResultDetail(
                id = row[ProbeResults.id].toString(),
                serviceId = row[ProbeResults.serviceId].toString(),
                status = row[ProbeResults.status],
                runDurationMs = row[ProbeResults.runDurationMs],
                startedAt = row[ProbeResults.startedAt].toString(),
                probeAgentId = row[ProbeResults.probeAgentId],
                agentSlug = row[ProbeAgents.slug],
                rawResult = row[ProbeResults.rawResult],
                steps = steps,
            )
        }
    }

    /**
     * [detail] with every storage locator in its `rawResult` set to null:
     * under each `calls[].response`, any key ending in `Path` or `Uri`
     * (`bodyPath` today). `rawResult` is the agent's ProbeResult as the Lace
     * spec defines it — whose `result.json` schema requires `bodyPath`, so the
     * key stays and only its value goes — and those keys say where the agent
     * or the platform keeps a body: an internal location, not something a
     * caller can use. A caller reaches a body through `steps[].hasBody` and
     * the step-body read.
     */
    fun withoutStorageLocators(detail: ProbeResultDetail): ProbeResultDetail {
        val raw = detail.rawResult as? JsonObject ?: return detail
        val calls = raw["calls"] as? JsonArray ?: return detail
        val cleaned = JsonArray(calls.map { call ->
            val callObject = call as? JsonObject ?: return@map call
            val response = callObject["response"] as? JsonObject ?: return@map call
            val blanked = response.mapValues { (key, value) ->
                if (key.endsWith("Path") || key.endsWith("Uri")) JsonNull else value
            }
            JsonObject(callObject + ("response" to JsonObject(blanked)))
        })
        return detail.copy(rawResult = JsonObject(raw + ("calls" to cleaned)))
    }

    /**
     * Retrieves the stored response body for a probe step; [BodyStorageClient.BodyContent.NotFound]
     * when none is stored. The dashboard's read.
     *
     * A body in the default store is served as before (a presigned URL for S3,
     * content for the filesystem). A body kept in an `in_place` body store
     * (`probe_steps.body_store_id`) is read through that store's confined client
     * and always served as **content**, never a URL — a store owner must not have
     * to open their bucket's CORS to the dashboard, and a presigned URL would
     * hand the browser their credentials' reach. Content that is not valid UTF-8
     * comes back base64 with `encoding = "base64"` rather than mangled into
     * replacement characters.
     *
     * Capped at [BodyStoreRegistry.MAX_BODY_BYTES] (413 `body_too_large`).
     * A recorded body that is not there, or a key the store refuses, is 410
     * `body_gone` — the body is not coming back. A store that cannot be
     * reached, whose credentials do not decrypt, that takes longer than
     * [BODY_READ_DEADLINE], or that fails any other way is 503
     * `body_store_unavailable`: the body is most likely still there and the
     * call is worth repeating. So is a read that waited [BODY_READ_WAIT] for
     * the gate and did not get in. Reads go through the same gate as the
     * key-authenticated API's (see [gated]); the body is returned, and
     * encoded by the caller, after the gate is left.
     */
    suspend fun getStepBody(orgId: UUID, serviceId: UUID, resultId: UUID, stepId: UUID, userId: UUID): BodyStorageClient.BodyContent {
        val (storageUrl, bodyStoreId) = locateStepBody(orgId, serviceId, resultId, stepId, userId)
        if (storageUrl == null) return BodyStorageClient.BodyContent.NotFound
        if (bodyStoreId != null) return readFromStore(orgId, bodyStoreId, storageUrl)

        val client = storageClient ?: return BodyStorageClient.BodyContent.NotFound
        // An object in the default S3 store is not read at all — it is
        // answered with a link — so it takes no place in the gate.
        if (StorageUri.parse(storageUrl) !is StorageUri.File) return onIo { client.readBody(storageUrl) }
        return gated(orgId, null) { hold ->
            val read = readingFrom(null, storageUrl) { readSized(client, storageUrl, BodyStoreRegistry.MAX_BODY_BYTES, hold) }
            when (read) {
                is BodyStorageClient.StoredBody.Found -> BodyStorageClient.BodyContent.Inline(read.bytes.toString(Charsets.UTF_8))
                BodyStorageClient.StoredBody.Missing -> throw GoneException(ErrorCodes.BODY_GONE)
                is BodyStorageClient.StoredBody.TooLarge ->
                    throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.BODY_TOO_LARGE)
            }
        }
    }

    /**
     * The key-authenticated API's read of a step's body: always read by the
     * gateway and handed to [use] as text — never a link, whatever store it
     * lives in. A presigned URL is a credential of its own, outlives the
     * request that produced it, and reaches a bucket the caller was never
     * given.
     *
     * [use] is called **inside** the read gate with the body (null when the
     * step stored none) and must encode and hand it on before it returns: the
     * gate bounds the read and the encoding, when the body, its decoded text,
     * its JSON escaping and the encoded response all exist at once. Once the
     * encoded response is handed to the engine it has left the gate, and the
     * engine's write timeout bounds the rest. That is why the body is capped
     * well below the dashboard's, at [PUBLIC_BODY_INLINE_MAX] stored bytes, why
     * each read reserves [PUBLIC_READ_RESERVATION] of the byte budget up front,
     * and why text that carries C0 control characters is sent as base64 (at
     * most 4/3 of the stored size) instead of as six-byte JSON escapes.
     *
     * Same access check and confinement as [getStepBody]. A step that recorded
     * a body which is no longer at its location — in the default store or a
     * body store — is 410 `body_gone`, as is a recorded location that cannot
     * be read at all (not a storage URI, or a scheme the gateway has no store
     * for). Over the cap is 413 `body_too_large` with `details.maxBytes`. A
     * store that does not answer within [BODY_READ_DEADLINE], or a gate that
     * stays full for [BODY_READ_WAIT], is 503 `body_store_unavailable`.
     */
    suspend fun <T> readStepBody(
        orgId: UUID,
        serviceId: UUID,
        resultId: UUID,
        stepId: UUID,
        userId: UUID,
        use: suspend (StepBodyContent?) -> T,
    ): T {
        val (storageUrl, bodyStoreId) = locateStepBody(orgId, serviceId, resultId, stepId, userId)
        if (storageUrl == null) return use(null)
        val store = bodyStoreId?.let { BodyStoreRegistry.load(it) ?: throw GoneException(ErrorCodes.BODY_GONE) }
        if (store == null && storageClient == null) throw GoneException(ErrorCodes.BODY_GONE)

        return gated(store?.organizationId ?: orgId, bodyStoreId) { hold ->
            // The whole footprint, every time: the size a store reports is not
            // asked for (one round trip fewer) and would not be the bound anyway.
            hold.reserve(PUBLIC_READ_RESERVATION)
            val read = readingFrom(bodyStoreId, storageUrl) {
                val client = if (store != null) storeClient(store) else storageClient!!
                hold.onIo { client.readBytes(storageUrl, PUBLIC_BODY_INLINE_MAX) }
            }
            bodyStoreId?.let(BodyStoreService::clearFailure)
            when (read) {
                is BodyStorageClient.StoredBody.Found -> use(publicContent(read.bytes, read.contentType))
                BodyStorageClient.StoredBody.Missing -> throw GoneException(ErrorCodes.BODY_GONE)
                is BodyStorageClient.StoredBody.TooLarge -> throw ApiException(
                    HttpStatusCode.PayloadTooLarge, ErrorCodes.BODY_TOO_LARGE,
                    details = buildJsonObject { put("maxBytes", PUBLIC_BODY_INLINE_MAX) },
                )
            }
        }
    }

    /**
     * Answers [call] with a step's body for the key-authenticated API — the
     * body as [StepBodyContent], or 204 when none was stored — from inside the
     * read gate ([readStepBody]). Taking the call is the point: the answer
     * cannot be sent from anywhere but inside the gate.
     */
    suspend fun respondStepBody(call: ApplicationCall, orgId: UUID, serviceId: UUID, resultId: UUID, stepId: UUID, userId: UUID) {
        readStepBody(orgId, serviceId, resultId, stepId, userId) { body ->
            insideGateProbe?.invoke()
            if (body == null) call.respond(HttpStatusCode.NoContent, "") else call.respond(body)
        }
    }

    /** Called inside the gate just before a key-authenticated body is answered. For tests. */
    @Volatile
    internal var insideGateProbe: (() -> Unit)? = null

    /**
     * Authorizes the read of a step's body and returns where it is kept: the
     * recorded URI (null when none was stored) and the body store holding it
     * (null for the default store).
     */
    private fun locateStepBody(orgId: UUID, serviceId: UUID, resultId: UUID, stepId: UUID, userId: UUID): Pair<String?, UUID?> =
        transaction {
            val ctx = ResourceResolver.resolveService(serviceId, orgId)
            val cached = requireCachedPermissions(orgId, userId)
            val parentChain = listOf("project::${ctx.projectId}", "workspace::${ctx.workspaceId}")
            if (!canAccessResource(cached, "service", ctx.serviceId, parentChain)) {
                throw NotFoundException()
            }

            // probe_steps carries neither service_id nor organization_id, so the
            // step id alone constrains nothing: authorizing the service above
            // admits the caller, and an unscoped lookup then hands back any
            // step in the installation. Bodies routinely hold tokens and PII.
            // The owning result is where the scope lives — join through it and
            // apply the same three terms the sibling `get` puts on the result.
            val step = ProbeSteps
                .join(ProbeResults, JoinType.INNER, ProbeSteps.probeResultId, ProbeResults.id)
                .select(ProbeSteps.responseBodyStorageUrl, ProbeSteps.bodyStoreId)
                .where {
                    (ProbeSteps.id eq stepId) and
                        (ProbeSteps.probeResultId eq resultId) and
                        (ProbeResults.serviceId eq serviceId) and
                        (ProbeResults.organizationId eq orgId)
                }
                .firstOrNull() ?: throw NotFoundException()

            step[ProbeSteps.responseBodyStorageUrl] to step[ProbeSteps.bodyStoreId]
        }

    /**
     * Reads a body kept in an in_place body store, for the dashboard.
     *
     * Every failure is also recorded on the store row, so the dashboard can say
     * "this store stopped answering at …" instead of leaving a person to click
     * through bodies one at a time to find out.
     */
    private suspend fun readFromStore(orgId: UUID, storeId: UUID, uri: String): BodyStorageClient.BodyContent {
        val store = BodyStoreRegistry.load(storeId) ?: throw GoneException(ErrorCodes.BODY_GONE)
        return gated(store.organizationId, storeId) { hold ->
            val read = readingFrom(storeId, uri) {
                readSized(storeClient(store), uri, BodyStoreRegistry.MAX_BODY_BYTES, hold)
            }
            BodyStoreService.clearFailure(storeId)
            when (read) {
                is BodyStorageClient.StoredBody.Found -> inline(read.bytes, read.contentType)
                BodyStorageClient.StoredBody.Missing -> throw GoneException(ErrorCodes.BODY_GONE)
                is BodyStorageClient.StoredBody.TooLarge ->
                    throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.BODY_TOO_LARGE)
            }
        }
    }

    /**
     * A read up to [cap] whose reservation follows the size the store reports
     * — without trusting it: [min(size, cap)][BodyStorageClient.sizeOf] is
     * reserved and passed to the read as its limit, so a body that grew, or a
     * store that reported less than it holds, cannot read more than was
     * reserved. When the read finds more, the full [cap] is reserved and the
     * read made once more. No size means nothing there: Missing.
     */
    private suspend fun readSized(client: BodyStorageClient, uri: String, cap: Long, hold: GateHold): BodyStorageClient.StoredBody {
        val size = hold.onIo { client.sizeOf(uri) } ?: return BodyStorageClient.StoredBody.Missing
        if (size > cap) return BodyStorageClient.StoredBody.TooLarge(size)
        val reserved = size.coerceAtLeast(1)
        hold.reserve(reserved)
        val first = hold.onIo { client.readBytes(uri, reserved) }
        if (first !is BodyStorageClient.StoredBody.TooLarge || reserved >= cap) return first
        hold.reserve(cap)
        return hold.onIo { client.readBytes(uri, cap) }
    }

    /**
     * Runs a read against the store holding a body ([storeId], null for the
     * default store) and turns what can go wrong into the answer for it.
     *
     *  - A cancelled call is passed on as it is: the caller went away, the
     *    store did nothing wrong, and nothing is recorded against it.
     *  - A location outside the store's confinement, one that is not a storage
     *    URI ([StorageUriException]), or one of a kind this deployment has no
     *    store for ([StorageUnconfiguredException]) is 410 `body_gone`. Asking
     *    again will not change it.
     *  - Anything else — a store that refuses, does not answer, or overran
     *    [BODY_READ_DEADLINE] — is 503 `body_store_unavailable`, and for a body
     *    store recorded on its row.
     */
    private suspend fun <T> readingFrom(storeId: UUID?, uri: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw e
        } catch (e: StorageConfinementException) {
            // The recorded URL is not inside the store any more — the store was
            // repointed under it, or the row predates a change. Nothing to fetch.
            log.warn("stored body {} is outside {}", uri, storeId?.let { "body store $it" } ?: "the default store")
            storeId?.let { BodyStoreService.recordFailure(it, "outside_store") }
            throw GoneException(ErrorCodes.BODY_GONE)
        } catch (e: StorageUriException) {
            log.warn("stored body location {} is not a storage URI: {}", uri, e.message)
            throw GoneException(ErrorCodes.BODY_GONE)
        } catch (e: StorageUnconfiguredException) {
            log.warn("stored body location {} names no store this gateway has: {}", uri, e.message)
            throw GoneException(ErrorCodes.BODY_GONE)
        } catch (e: Exception) {
            val reason = if (e is ReadDeadlineException) "timeout" else failureCodeOf(e)
            log.warn("stored body in {} could not be read ({}): {}", storeId?.let { "store $it" } ?: "the default store", reason, e.message)
            storeId?.let { BodyStoreService.recordFailure(it, reason) }
            throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.BODY_STORE_UNAVAILABLE)
        }

    /**
     * The key-authenticated API's form of a body: UTF-8 text sent as text,
     * anything else base64 — including text that carries a C0 control
     * character other than tab, newline and carriage return, which JSON would
     * otherwise write as a six-byte escape per byte.
     */
    private fun publicContent(bytes: ByteArray, reportedType: String?): StepBodyContent {
        val contentType = safeContentType(reportedType)
        val text = decodeUtf8(bytes)?.takeIf { decoded -> decoded.none { it < ' ' && it != '\t' && it != '\n' && it != '\r' } }
        return if (text != null) {
            StepBodyContent(content = text, contentType = contentType)
        } else {
            StepBodyContent(content = Base64.getEncoder().encodeToString(bytes), contentType = contentType, encoding = "base64")
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrNull()

    /**
     * Bodies are text far more often than not, but nothing guarantees it — an
     * image, a protobuf or a gzip response decoded as UTF-8 comes out as a wall
     * of replacement characters, which is both useless and not what was stored.
     * Text is sent as text; anything else is base64 and says so.
     */
    /**
     * Content types the API will repeat back. The value comes from the agent's
     * own store — the agent chose it when it uploaded the body — so it is not
     * the platform's to hand on unexamined to a browser. Anything not on the
     * list is reported as nothing at all, and the dashboard shows the bytes.
     */
    private val ECHOED_CONTENT_TYPES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-ndjson",
        "application/octet-stream", "application/pdf", "application/zip", "application/gzip",
        "text/plain", "text/html", "text/css", "text/csv", "text/xml", "text/markdown",
        "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp",
    )

    /** The reported type, or null when it is not one of [ECHOED_CONTENT_TYPES]. */
    private fun safeContentType(reported: String?): String? {
        val bare = reported?.substringBefore(';')?.trim()?.lowercase() ?: return null
        return bare.takeIf { it in ECHOED_CONTENT_TYPES }
    }

    private fun inline(bytes: ByteArray, reportedType: String?): BodyStorageClient.BodyContent {
        val contentType = safeContentType(reportedType)
        val text = decodeUtf8(bytes)
        return if (text != null) {
            BodyStorageClient.BodyContent.Inline(text, contentType)
        } else {
            BodyStorageClient.BodyContent.Inline(
                Base64.getEncoder().encodeToString(bytes),
                contentType,
                encoding = "base64",
            )
        }
    }

    /** A short, machine-readable code for the store's health row. */
    private fun failureCodeOf(e: Throwable): String = when {
        e is BodyStoreSecretException -> "secret_undecryptable"
        StoreEndpointGuard.isBlocked(e) -> "blocked_endpoint"
        else -> "unreachable"
    }

    /**
     * The largest stored body the key-authenticated API returns inline. Its
     * response holds the body several times over at once (bytes, text, JSON),
     * so the cap is set by memory, not by what is useful; a raw download
     * endpoint is planned for anything larger.
     */
    const val PUBLIC_BODY_INLINE_MAX: Long = 4L * 1024 * 1024

    /**
     * How many times its stored size one inline body can occupy at its peak:
     * the bytes read (1), the text decoded from them (UTF-16, up to 2), the
     * JSON-escaped text (escaping at most doubles what survives as text, since
     * control characters go base64 instead — 2), and the encoded response
     * (1). Six is a ceiling, not an estimate.
     */
    private const val JSON_AMPLIFICATION = 6

    /** What a key-authenticated read reserves of the byte budget, whatever the body's size. */
    internal const val PUBLIC_READ_RESERVATION: Long = PUBLIC_BODY_INLINE_MAX * JSON_AMPLIFICATION

    /** At most this many body reads at once, across every caller. */
    private const val CONCURRENT_BODY_READS = 8

    /**
     * Of [CONCURRENT_BODY_READS], at most this many against body stores: the
     * rest are kept for the default store, which the platform runs, so slow
     * stores an organization runs cannot hold every place.
     */
    private const val CONCURRENT_STORE_READS = 6

    /** At most this many reads against any one body store. */
    private const val CONCURRENT_READS_PER_STORE = 2

    /** At most this many reads for any one organization, whatever store they go to. */
    private const val CONCURRENT_READS_PER_ORG = 2

    /**
     * At most this many bytes reserved by body reads at once: room for five
     * key-authenticated reads ([PUBLIC_READ_RESERVATION]) or four dashboard
     * reads of a 32 MiB body.
     */
    internal const val CONCURRENT_BODY_BYTES: Long = 128L * 1024 * 1024

    /** The byte budget is kept in units of this size. */
    internal const val BODY_BYTE_UNIT: Long = 1024L * 1024

    /** How long a read waits to get into the gate before it answers 503. */
    val BODY_READ_WAIT = 10.seconds

    /**
     * How long a read may take once inside the gate, store round trips
     * included, before it is cut off and answered 503. A watchdog interrupts
     * the reading thread: a coroutine timeout cannot stop a blocking read.
     */
    val BODY_READ_DEADLINE = 15.seconds

    /** [BODY_READ_WAIT], and [BODY_READ_DEADLINE]: settable so tests need not wait the real times. */
    internal var readWait = BODY_READ_WAIT
    internal var readDeadline = BODY_READ_DEADLINE

    /** The confined client for a body store. A seam for tests that need a store to misbehave. */
    internal var storeClient: (BodyStore) -> BodyStorageClient = { BodyStoreRegistry.clientFor(it) }

    /** What the gate has free, for tests: global places, body-store places, byte units, and [orgId]'s places. */
    internal data class GateState(val reads: Int, val storeReads: Int, val byteUnits: Int, val orgReads: Int)

    internal fun gateState(orgId: UUID): GateState = GateState(
        reads = bodyReads.availablePermits,
        storeReads = storeReadsTotal.availablePermits,
        byteUnits = bodyBytes.availablePermits,
        orgReads = orgReads[orgId]?.availablePermits ?: CONCURRENT_READS_PER_ORG,
    )

    /** The gate when nothing holds it, for tests. */
    internal val idleGate = GateState(
        CONCURRENT_BODY_READS, CONCURRENT_STORE_READS, (CONCURRENT_BODY_BYTES / BODY_BYTE_UNIT).toInt(), CONCURRENT_READS_PER_ORG,
    )

    private val bodyReads = Semaphore(CONCURRENT_BODY_READS)
    private val storeReadsTotal = Semaphore(CONCURRENT_STORE_READS)
    private val bodyBytes = Semaphore((CONCURRENT_BODY_BYTES / BODY_BYTE_UNIT).toInt())
    private val storeReads = ConcurrentHashMap<UUID, Semaphore>()
    private val orgReads = ConcurrentHashMap<UUID, Semaphore>()

    private val watchdog = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "body-read-watchdog").apply { isDaemon = true }
    }

    /** A read cut off by [BODY_READ_DEADLINE]. */
    private class ReadDeadlineException(cause: Throwable) : RuntimeException("body read overran its deadline", cause)

    /**
     * A read's place in the gate: the permits it took, the bytes it has
     * reserved, and when it must be done by.
     */
    private class GateHold(private val held: List<Semaphore>, private val deadlineNanos: Long) {
        private var units = 0

        /**
         * Reserves [bytes] of the byte budget for this read, waiting at most
         * [BODY_READ_WAIT]. Reserving again only adds what is missing. Each
         * unit is taken on its own, without a lock, so a large reservation
         * waiting for units does not hold up smaller ones behind it. Two
         * reservations that each hold part of what they need can wait on each
         * other only until the deadline: the one that times out answers 503
         * and gives everything back as it leaves the gate.
         */
        suspend fun reserve(bytes: Long) {
            val wanted = ((bytes + BODY_BYTE_UNIT - 1) / BODY_BYTE_UNIT).toInt().coerceAtLeast(1)
            if (wanted <= units) return
            withTimeoutOrNull(readWait) {
                while (units < wanted) {
                    bodyBytes.acquire()
                    units++
                }
            } ?: throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.BODY_STORE_UNAVAILABLE)
        }

        /**
         * Runs a blocking store call off the request thread, cut off at the
         * read's deadline by interrupting the thread it runs on — which aborts
         * an interruptible channel or an SDK call mid-read.
         */
        suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) {
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) throw ReadDeadlineException(IllegalStateException("no time left"))
            val thread = Thread.currentThread()
            val state = AtomicInteger(0) // 0 running, 1 done, 2 interrupted
            val alarm = watchdog.schedule({ if (state.compareAndSet(0, 2)) thread.interrupt() }, remaining, TimeUnit.NANOSECONDS)
            try {
                block()
            } catch (e: Exception) {
                if (state.get() == 2) throw ReadDeadlineException(e)
                throw e
            } finally {
                alarm.cancel(false)
                // Clears an interrupt the alarm may have raised as the call ended,
                // so the pooled thread goes back clean.
                if (!state.compareAndSet(0, 1)) Thread.interrupted()
            }
        }

        fun release() {
            repeat(units) { bodyBytes.release() }
            units = 0
            held.asReversed().forEach { it.release() }
        }
    }

    /**
     * Runs [block] inside the body-read gate. Getting in takes, in this order
     * everywhere — so that no two reads can each hold what the other waits for
     * — a place among [CONCURRENT_READS_PER_ORG] for the organization
     * ([orgId]), among [CONCURRENT_READS_PER_STORE] and
     * [CONCURRENT_STORE_READS] for a body store ([storeId]), and among
     * [CONCURRENT_BODY_READS]; then the block reserves the share of
     * [CONCURRENT_BODY_BYTES] it needs. Getting in waits at most
     * [BODY_READ_WAIT], and the block's store calls are cut off at
     * [BODY_READ_DEADLINE] from then; either is 503 `body_store_unavailable`.
     *
     * The gate bounds what body reads hold in memory — the bytes reserved, not
     * the number of open requests — and keeps a store, or an organization,
     * that takes its time from parking more than its share of reads.
     */
    private suspend fun <T> gated(orgId: UUID, storeId: UUID?, block: suspend (GateHold) -> T): T {
        val order = buildList {
            add(orgReads.computeIfAbsent(orgId) { Semaphore(CONCURRENT_READS_PER_ORG) })
            if (storeId != null) {
                add(storeReads.computeIfAbsent(storeId) { Semaphore(CONCURRENT_READS_PER_STORE) })
                add(storeReadsTotal)
            }
            add(bodyReads)
        }
        val held = mutableListOf<Semaphore>()
        try {
            withTimeoutOrNull(readWait) {
                for (semaphore in order) {
                    semaphore.acquire()
                    held += semaphore
                }
            }
        } catch (e: Throwable) {
            held.asReversed().forEach { it.release() }
            throw e
        }
        if (held.size < order.size) {
            held.asReversed().forEach { it.release() }
            throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.BODY_STORE_UNAVAILABLE)
        }
        val hold = GateHold(held, System.nanoTime() + readDeadline.inWholeNanoseconds)
        try {
            return block(hold)
        } finally {
            hold.release()
        }
    }

    /** Runs a blocking call off the request thread, outside the gate. */
    private suspend fun <T> onIo(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
