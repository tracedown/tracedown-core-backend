package dev.tracedown.gateway.routes.v1.results

import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.get
import kotlinx.serialization.Serializable

/**
 * @OpenAPITag Probe Results
 * Query probe results for a service.
 */
@Resource("/api/v1/services/{serviceId}/results")
class Results(val serviceId: String) {
    /** `at` is an ISO-8601 instant; `pageSize` must match the list's, or the page number means nothing. */
    @Resource("page-at")
    class PageAt(val parent: Results, val at: String, val pageSize: Int = 50)

    @Resource("{resultId}")
    class ById(val parent: Results, val resultId: String) {
        @Resource("steps/{stepId}/body")
        class StepBody(val parent: ById, val stepId: String)
    }
}

/**
 * A stored response body: exactly one of [content] (small/filesystem bodies,
 * inlined) or [url] (object storage — a short-lived presigned URL the client
 * fetches directly). Deliberately NOT an HTTP redirect: a fetch that follows a
 * cross-origin redirect is sent with `Origin: null`, which no origin-scoped
 * bucket CORS policy can match — fetching the URL directly preserves the
 * page's origin, so the bucket policy can stay restricted to the dashboard.
 * [contentType] accompanies inlined content when its reader knows it.
 */
@Serializable
data class StepBodyResponse(val content: String? = null, val url: String? = null, val contentType: String? = null)

/** Registers routes for querying probe results. */
fun Route.resultRoutes() {
    /** Lists probe results for a service (paginated, most recent first). */
    get<Results> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val svcId = parseUuid(resource.serviceId, "service ID")
        val pfs = parsePfsParams(call)
        val result = ProbeResultController.list(orgId, svcId, principal.userId, pfs)
        call.respond(result)
    }

    /** Returns the page on which results at or before `at` begin (see [ProbeResultController.pageAt]). */
    get<Results.PageAt> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val svcId = parseUuid(resource.parent.serviceId, "service ID")
        val at = try {
            java.time.Instant.parse(resource.at)
        } catch (e: java.time.format.DateTimeParseException) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        if (resource.pageSize < 1 || resource.pageSize > 1000) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        call.respond(ProbeResultController.pageAt(orgId, svcId, principal.userId, at, resource.pageSize))
    }

    /** Returns a single probe result with all steps. */
    get<Results.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val svcId = parseUuid(resource.parent.serviceId, "service ID")
        val resultId = parseUuid(resource.resultId, "result ID")
        val result = ProbeResultController.get(orgId, svcId, resultId, principal.userId)
        call.respond(result)
    }

    /** Returns the stored response body for a probe step. */
    get<Results.ById.StepBody> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val svcId = parseUuid(resource.parent.parent.serviceId, "service ID")
        val resultId = parseUuid(resource.parent.resultId, "result ID")
        val stepId = parseUuid(resource.stepId, "step ID")
        when (val body = ProbeResultController.getStepBody(orgId, svcId, resultId, stepId, principal.userId)) {
            is BodyStorageClient.BodyContent.Inline -> call.respond(StepBodyResponse(content = body.content, contentType = body.contentType))
            is BodyStorageClient.BodyContent.Redirect -> call.respond(StepBodyResponse(url = body.url))
            is BodyStorageClient.BodyContent.NotFound -> call.respond(HttpStatusCode.NoContent, "")
        }
    }
}
