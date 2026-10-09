package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.controllers.events.EventFeedController
import dev.tracedown.gateway.controllers.events.EventTypes
import dev.tracedown.gateway.data.events.EventPage
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.EventPollSlots
import dev.tracedown.gateway.context.ApiKeyAuth
import dev.tracedown.gateway.routes.publicapi.PublicApi
import dev.tracedown.gateway.util.ApiException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tracedown.gateway.util.fieldError
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.JsonPrimitive

/** The event feed's path under [dev.tracedown.gateway.routes.publicapi.PublicApi.V1]. */
const val EVENTS_PATH = "/events"

/** The event feed. */
fun Route.eventRoutes() {
    /**
     * The events after the cursor `after` that the caller may see, waiting up
     * to `wait` seconds (0–30, default 0) for the first when there are none;
     * `types` narrows them (comma-separated or repeated), `limit` caps a page
     * (1–100, default 100). Without `after`, reads from now on.
     */
    get(EVENTS_PATH) {
        val caller = call.apiCaller
        val params = call.request.queryParameters
        val after = params["after"]?.also { if (it.isEmpty()) throw fieldError("after") }
        val wait = call.intQuery("wait", 0)
        if (wait < 0 || wait > EventFeedController.MAX_WAIT_SECONDS) {
            throw fieldError("wait") { put("max", JsonPrimitive(EventFeedController.MAX_WAIT_SECONDS)) }
        }
        val limit = call.intQuery("limit", EventFeedController.MAX_LIMIT)
        if (limit < 1 || limit > EventFeedController.MAX_LIMIT) {
            throw fieldError("limit") { put("max", JsonPrimitive(EventFeedController.MAX_LIMIT)) }
        }
        val types = params.getAll("types")
            ?.flatMap { it.split(',') }
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?.also { asked -> if (asked.isEmpty() || !EventTypes.ALL.containsAll(asked)) throw fieldError("types") }

        // Someone who can see nothing at all is answered at once — there is
        // nothing to wait for — and holds no slot.
        val anything = EventFeedController.hasAnythingToSee(ApiKeyAuth.permissionsOf(call))
        val read: suspend (Int) -> EventPage = { effectiveWait ->
            EventFeedController.read(
                caller.orgId, caller.userId, caller.keyId, after, effectiveWait, types, limit,
                spend = { ApiKeyAuth.spendAgain(call) },
                beforeLook = { PublicApi.recheckGuards(caller, call) },
            )
        }
        val page = try {
            if (!anything) {
                read(0)
            } else {
                // A slot for as long as the read may take, and a little more.
                val slot = when (val outcome = EventPollSlots.tryAcquire(caller.keyId, caller.userId, caller.orgId, (wait + 10) * 1000L)) {
                    is EventPollSlots.Outcome.Held -> outcome.slot
                    is EventPollSlots.Outcome.Refused -> throw ApiException(
                        HttpStatusCode.TooManyRequests, ErrorCodes.TOO_MANY_EVENT_POLLS,
                        details = buildJsonObject { put("bound", outcome.bound.wire) },
                    )
                }
                slot.use { read(wait) }
            }
        } catch (_: PublicApi.AnsweredByGuard) {
            return@get
        }
        call.respond(page)
    }
}
