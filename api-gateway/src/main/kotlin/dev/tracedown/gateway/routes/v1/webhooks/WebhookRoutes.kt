package dev.tracedown.gateway.routes.v1.webhooks

import dev.tracedown.gateway.controllers.webhooks.WebhookController
import dev.tracedown.gateway.controllers.webhooks.WebhookVariableController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.data.webhooks.CreateWebhookRequest
import dev.tracedown.gateway.data.webhooks.UpdateWebhookRequest
import dev.tracedown.gateway.data.webhooks.WebhookBindingRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post

/**
 * @OpenAPITag Webhooks
 * Webhook delivery channels: CRUD, resource bindings.
 */
@Resource("/api/v1/webhooks")
class Webhooks {
    @Resource("{webhookId}")
    class ById(val parent: Webhooks = Webhooks(), val webhookId: String) {
        @Resource("variables")
        class Variables(val parent: ById) {
            @Resource("{varId}")
            class ByVarId(val parent: Variables, val varId: String)
        }
    }

    @Resource("bindings/{resourceType}/{resourceId}")
    class Bindings(val parent: Webhooks = Webhooks(), val resourceType: String, val resourceId: String)

    @Resource("bindings/{bindingId}")
    class BindingById(val parent: Webhooks = Webhooks(), val bindingId: String)
}

fun Route.webhookRoutes() {
    /** Creates a new webhook delivery channel. */
    post<Webhooks> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateWebhookRequest>(call)
        call.respond(WebhookController.create(orgId, body, principal.userId))
    }

    /** Lists all webhooks in the organization. */
    get<Webhooks> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(WebhookController.list(orgId, principal.userId, pfs))
    }

    /** Returns a single webhook. */
    get<Webhooks.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.webhookId, "webhook ID")
        call.respond(WebhookController.get(orgId, webhookId, principal.userId))
    }

    /** Updates a webhook's configuration. */
    patch<Webhooks.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.webhookId, "webhook ID")
        val body = tryReceive<UpdateWebhookRequest>(call)
        call.respond(WebhookController.update(orgId, webhookId, body, principal.userId))
    }

    /** Soft-deletes a webhook. */
    delete<Webhooks.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.webhookId, "webhook ID")
        WebhookController.delete(orgId, webhookId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    // ── Per-webhook variables ($h.key, resolved at delivery only) ──

    /** Lists a webhook's variables (encrypted values masked). */
    get<Webhooks.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.parent.webhookId, "webhook ID")
        call.respond(WebhookVariableController.list(orgId, webhookId, principal.userId))
    }

    /** Creates a variable on a webhook. */
    post<Webhooks.ById.Variables> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.parent.webhookId, "webhook ID")
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(WebhookVariableController.create(orgId, webhookId, body, principal.userId))
    }

    /** Reveals a variable's decrypted value (secrets cannot be revealed). */
    get<Webhooks.ById.Variables.ByVarId> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.parent.parent.webhookId, "webhook ID")
        val varId = parseUuid(resource.varId, "variable ID")
        call.respond(WebhookVariableController.reveal(orgId, webhookId, varId, principal.userId))
    }

    /** Updates a variable's value. */
    patch<Webhooks.ById.Variables.ByVarId> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.parent.parent.webhookId, "webhook ID")
        val varId = parseUuid(resource.varId, "variable ID")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(WebhookVariableController.update(orgId, webhookId, varId, body, principal.userId))
    }

    /** Soft-deletes a variable. */
    delete<Webhooks.ById.Variables.ByVarId> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val webhookId = parseUuid(resource.parent.parent.webhookId, "webhook ID")
        val varId = parseUuid(resource.varId, "variable ID")
        WebhookVariableController.delete(orgId, webhookId, varId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    // ── Resource Bindings ──

    /** Lists webhook bindings for a resource. */
    get<Webhooks.Bindings> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val resourceId = parseUuid(resource.resourceId, "resource ID")
        val pfs = parsePfsParams(call)
        call.respond(WebhookController.listBindings(orgId, resource.resourceType, resourceId, principal.userId, pfs))
    }

    /** Binds a webhook to a resource. */
    post<Webhooks.Bindings> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val resourceId = parseUuid(resource.resourceId, "resource ID")
        val body = tryReceive<WebhookBindingRequest>(call)
        call.respond(WebhookController.createBinding(orgId, resource.resourceType, resourceId, body, principal.userId))
    }

    /** Updates a binding's enabled state. */
    patch<Webhooks.BindingById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val bindingId = parseUuid(resource.bindingId, "binding ID")
        val body = tryReceive<Map<String, Boolean>>(call)
        val enabled = body["enabled"] ?: throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        call.respond(WebhookController.updateBinding(orgId, bindingId, enabled, principal.userId))
    }

    /** Removes a webhook binding. */
    delete<Webhooks.BindingById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val bindingId = parseUuid(resource.bindingId, "binding ID")
        WebhookController.deleteBinding(orgId, bindingId, principal.userId)
        call.respond(mapOf("ok" to true))
    }
}
