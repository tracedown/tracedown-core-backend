package dev.tracedown.gateway.controllers.results

import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.results.ProbeResultDetail
import dev.tracedown.gateway.data.results.ProbeResultSummary
import dev.tracedown.gateway.data.results.ProbeStepSummary
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.requireCachedPermissions
import org.jetbrains.exposed.sql.JoinType
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID

/**
 * Queries probe results for a service.
 * Resolves the service's parent chain and checks resource-level read access.
 */
object ProbeResultController {

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

    /** Retrieves the stored response body for a probe step. Returns null if no body stored. */
    fun getStepBody(orgId: UUID, serviceId: UUID, resultId: UUID, stepId: UUID, userId: UUID): BodyStorageClient.BodyContent {
        val storageUrl = transaction {
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
                .select(ProbeSteps.responseBodyStorageUrl)
                .where {
                    (ProbeSteps.id eq stepId) and
                        (ProbeSteps.probeResultId eq resultId) and
                        (ProbeResults.serviceId eq serviceId) and
                        (ProbeResults.organizationId eq orgId)
                }
                .firstOrNull() ?: throw NotFoundException()

            step[ProbeSteps.responseBodyStorageUrl]
        } ?: return BodyStorageClient.BodyContent.NotFound

        val client = storageClient ?: return BodyStorageClient.BodyContent.NotFound
        return client.readBody(storageUrl)
    }
}
