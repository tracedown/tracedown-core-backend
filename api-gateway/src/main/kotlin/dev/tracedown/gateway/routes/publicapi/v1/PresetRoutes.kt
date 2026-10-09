package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.presets.RulePresetController
import dev.tracedown.gateway.data.presets.CreateRulePresetRequest
import dev.tracedown.gateway.data.presets.UpdateRulePresetRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post

/**
 * Script presets: Lace scripts a service can start from, organization-wide
 * or kept to one workspace.
 */
fun Route.presetRoutes() {
    /**
     * Lists the organization-wide presets, and — with `workspaceId` — that
     * workspace's too when the caller may see it, by name. Paged with `page`
     * and `pageSize`.
     */
    get("/presets") {
        val caller = call.apiCaller
        val workspaceId = call.request.queryParameters["workspaceId"]?.let { call.requiredUuidQuery("workspaceId") }
        call.respond(RulePresetController.listPaged(caller.orgId, caller.userId, workspaceId, publicPaging(call)))
    }

    /** Saves a preset, organization-wide or (with `workspaceId`) in one workspace. */
    post("/presets") {
        val caller = call.apiCaller
        val body = tryReceive<CreateRulePresetRequest>(call)
        call.respond(RulePresetController.create(caller.orgId, caller.userId, body))
    }

    /** Returns one preset. */
    get("/presets/{id}") {
        val caller = call.apiCaller
        val presetId = call.pathUuid("id")
        call.respond(RulePresetController.get(caller.orgId, caller.userId, presetId))
    }

    /** Renames a preset or replaces its script. */
    patch("/presets/{id}") {
        val caller = call.apiCaller
        val presetId = call.pathUuid("id")
        val body = tryReceive<UpdateRulePresetRequest>(call)
        call.respond(RulePresetController.update(caller.orgId, caller.userId, presetId, body))
    }

    /** Deletes a preset. Services made from it keep their script. */
    delete("/presets/{id}") {
        val caller = call.apiCaller
        val presetId = call.pathUuid("id")
        RulePresetController.delete(caller.orgId, caller.userId, presetId)
        call.respond(mapOf("ok" to true))
    }
}
