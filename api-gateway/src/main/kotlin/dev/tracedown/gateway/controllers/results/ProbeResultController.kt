package dev.tracedown.gateway.controllers.results

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreSecretException
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.common.storage.BodyTooLargeException
import dev.tracedown.common.storage.StorageConfinementException
import dev.tracedown.common.storage.StoreEndpointGuard
import dev.tracedown.gateway.util.ApiException
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.results.ProbeResultDetail
import dev.tracedown.gateway.data.results.ProbeResultSummary
import dev.tracedown.gateway.data.results.ProbeStepSummary
import dev.tracedown.gateway.util.GoneException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import org.jetbrains.exposed.v1.core.greater
import dev.tracedown.gateway.data.results.ResultPageAt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.UUID

/**
 * Queries probe results for a service.
 * Resolves the service's parent chain and checks resource-level read access.
 */
object ProbeResultController {

    private val log = LoggerFactory.getLogger(ProbeResultController::class.java)

    private var storageClient: BodyStorageClient? = null

    /** Injects the body storage client for response body retrieval. */
    fun init(storage: BodyStorageClient) {
        this.storageClient = storage
    }

    /** Lists probe results for a service, ordered by most recent first. */
    fun list(orgId: UUID, serviceId: UUID, userId: UUID, pfs: PfsParams): Page<ProbeResultSummary> {
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
                .orderBy(ProbeResults.startedAt, SortOrder.DESC)

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

            val row = ProbeResults.selectAll()
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
                        assertionResults = step[ProbeSteps.assertionResults],
                        headers = step[ProbeSteps.headers],
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
                rawResult = row[ProbeResults.rawResult],
                steps = steps,
            )
        }
    }

    /**
     * Retrieves the stored response body for a probe step; [BodyStorageClient.BodyContent.NotFound]
     * when none is stored.
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
     * A missing object or a key the store refuses is 410 `body_gone` — the body
     * is not coming back. A store that cannot be reached, whose credentials do
     * not decrypt, or that fails any other way is 503 `body_store_unavailable`:
     * the body is most likely still there and the call is worth repeating.
     */
    suspend fun getStepBody(orgId: UUID, serviceId: UUID, resultId: UUID, stepId: UUID, userId: UUID): BodyStorageClient.BodyContent {
        val (storageUrl, bodyStoreId) = transaction {
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
        if (storageUrl == null) return BodyStorageClient.BodyContent.NotFound
        if (bodyStoreId != null) return readFromStore(bodyStoreId, storageUrl)

        val client = storageClient ?: return BodyStorageClient.BodyContent.NotFound
        // The default filesystem store reads the file here too, so it gets the
        // same treatment: off the request thread, behind the same gate, and
        // base64 rather than mangled when the bytes are not text.
        return offRequestThread {
            try {
                client.readBody(storageUrl)
            } catch (e: BodyTooLargeException) {
                throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.BODY_TOO_LARGE)
            }
        }
    }

    /**
     * Reads a body kept in an in_place body store.
     *
     * Every failure is also recorded on the store row, so the dashboard can say
     * "this store stopped answering at …" instead of leaving a person to click
     * through bodies one at a time to find out.
     */
    private suspend fun readFromStore(storeId: UUID, uri: String): BodyStorageClient.BodyContent {
        val store = BodyStoreRegistry.load(storeId) ?: throw GoneException(ErrorCodes.BODY_GONE)
        val read = try {
            offRequestThread { BodyStoreRegistry.clientFor(store).readBytes(uri, BodyStoreRegistry.MAX_BODY_BYTES) }
        } catch (e: StorageConfinementException) {
            // The recorded URL is not inside the store any more — the store was
            // repointed under it, or the row predates a change. Nothing to fetch.
            log.warn("stored body {} is outside body store {}", uri, storeId)
            BodyStoreService.recordFailure(storeId, "outside_store")
            throw GoneException(ErrorCodes.BODY_GONE)
        } catch (e: Exception) {
            val reason = failureCodeOf(e)
            log.warn("stored body in store {} could not be read ({}): {}", storeId, reason, e.message)
            BodyStoreService.recordFailure(storeId, reason)
            throw ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.BODY_STORE_UNAVAILABLE)
        }
        return when (read) {
            is BodyStorageClient.StoredBody.Found -> {
                BodyStoreService.clearFailure(storeId)
                inline(read.bytes, read.contentType)
            }
            BodyStorageClient.StoredBody.Missing -> {
                BodyStoreService.clearFailure(storeId)
                throw GoneException(ErrorCodes.BODY_GONE)
            }
            is BodyStorageClient.StoredBody.TooLarge -> {
                BodyStoreService.clearFailure(storeId)
                throw ApiException(HttpStatusCode.PayloadTooLarge, ErrorCodes.BODY_TOO_LARGE)
            }
        }
    }

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
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
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
     * Runs a blocking store read off the request thread, and never more than
     * [CONCURRENT_BODY_READS] at once.
     *
     * A body is up to 32 MiB fetched over someone else's network. On the request
     * thread that parks an event-loop thread for as long as the store takes; a
     * handful of people opening large bodies at once would stop the API
     * answering anything at all. The gate also bounds how much of the heap this
     * can hold: 32 MiB times the permits, not times the number of open requests.
     */
    private suspend fun <T> offRequestThread(block: () -> T): T =
        bodyReads.withPermit { withContext(Dispatchers.IO) { block() } }

    private const val CONCURRENT_BODY_READS = 8
    private val bodyReads = Semaphore(CONCURRENT_BODY_READS)
}
