package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.publicPaging
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * A service's runs: what each request returned and how each assertion went.
 */
fun Route.resultRoutes() {
    /**
     * Lists a service's runs, most recent first. `since` (an ISO-8601 instant)
     * keeps only runs started at or after it — the way to wait for the run a
     * `POST …/run` asked for. Paged with `page` and `pageSize`.
     */
    get("/services/{id}/results") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val since = call.instantQuery("since")
        call.respond(ProbeResultController.list(caller.orgId, serviceId, caller.userId, publicPaging(call), since))
    }

    /**
     * Returns one run with all of its steps. `rawResult` is the run's
     * ProbeResult as the Lace specification defines it, less the storage
     * locations of its bodies (`calls[].response.bodyPath` and the like) —
     * those name where the platform keeps a body, which a caller cannot use.
     * A stored body is reached through `steps[].hasBody` and the body route.
     */
    get("/services/{id}/results/{resultId}") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val resultId = call.pathUuid("resultId")
        val detail = ProbeResultController.get(caller.orgId, serviceId, resultId, caller.userId)
        call.respond(ProbeResultController.withoutStorageLocators(detail))
    }

    /**
     * Returns the response body a step stored, as text in the response —
     * `content`, with `contentType` (null when the store reported none the
     * gateway repeats) and `encoding: "base64"` when the bytes are not text.
     * Never a link to the storage the body lives in. Bodies over 4 MiB are
     * refused; a raw download endpoint is planned for those.
     *
     * 204 when the step stored no body (`hasBody: false`).
     *
     * Errors: `body_gone` (410) — the step recorded a body that is not at its
     * location any more, in the default store or a body store alike.
     * `body_too_large` (413) — over 4 MiB, `details.maxBytes` says the limit.
     * `body_store_unavailable` (503) — the store holding it did not answer, or
     * the gateway is reading as many bodies as it will at once; the body is
     * probably still there and the call is worth repeating.
     */
    get("/services/{id}/results/{resultId}/steps/{stepId}/body") {
        val caller = call.apiCaller
        val serviceId = call.pathUuid("id")
        val resultId = call.pathUuid("resultId")
        val stepId = call.pathUuid("stepId")
        // Answered from inside the read: the body is held, encoded and sent
        // under the same bound on memory (see readStepBody).
        ProbeResultController.readStepBody(caller.orgId, serviceId, resultId, stepId, caller.userId) { body ->
            if (body == null) call.respond(HttpStatusCode.NoContent, "") else call.respond(body)
        }
    }
}
