package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.controllers.webhooks.WebhookController
import dev.tracedown.gateway.data.webhooks.WebhookBindingRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.fieldError
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import java.util.UUID

/**
 * Attaching the organization's existing webhooks to a workspace, project or
 * service. Webhooks themselves are read-only here.
 */
fun Route.webhookRoutes() {
    /**
     * Lists the organization's webhooks, oldest first: id, name, label and
     * method — never where one sends or what, which may carry a token. Paged
     * with `page` and `pageSize`.
     */
    get("/webhooks") {
        val caller = call.apiCaller
        call.respond(WebhookController.listRedacted(caller.orgId, caller.userId, publicPaging(call)))
    }

    /**
     * Lists the webhooks bound to the resource named by the `resourceType`
     * (`workspace`, `project` or `service`) and `resourceId` query parameters,
     * both required. Paged with `page` and `pageSize`.
     */
    get("/webhooks/bindings") {
        val caller = call.apiCaller
        val (resourceType, resourceId) = boundResource(call)
        call.respond(WebhookController.listBindings(caller.orgId, resourceType, resourceId, caller.userId, publicPaging(call)))
    }

    /**
     * Binds a webhook to the resource named by the `resourceType` and
     * `resourceId` query parameters, both required. 409 `binding_exists` when
     * it is bound there already.
     */
    post("/webhooks/bindings") {
        val caller = call.apiCaller
        val (resourceType, resourceId) = boundResource(call)
        val body = tryReceive<WebhookBindingRequest>(call)
        call.respond(WebhookController.createBinding(caller.orgId, resourceType, resourceId, body, caller.userId))
    }

    /** Pauses or resumes a binding: `{"enabled": false}` or `{"enabled": true}`. */
    patch("/webhooks/bindings/{id}") {
        val caller = call.apiCaller
        val bindingId = call.pathUuid("id")
        val body = tryReceive<Map<String, Boolean>>(call)
        val enabled = body["enabled"] ?: throw fieldError("enabled", ErrorCodes.FIELD_REQUIRED)
        call.respond(WebhookController.updateBinding(caller.orgId, bindingId, enabled, caller.userId))
    }

    /** Removes a binding. The webhook itself stays. */
    delete("/webhooks/bindings/{id}") {
        val caller = call.apiCaller
        val bindingId = call.pathUuid("id")
        WebhookController.deleteBinding(caller.orgId, bindingId, caller.userId)
        call.respond(mapOf("ok" to true))
    }
}

/** The resource a binding read or write is about, from its query parameters. */
private fun boundResource(call: ApplicationCall): Pair<String, UUID> =
    call.requiredQuery("resourceType") to call.requiredUuidQuery("resourceId")
