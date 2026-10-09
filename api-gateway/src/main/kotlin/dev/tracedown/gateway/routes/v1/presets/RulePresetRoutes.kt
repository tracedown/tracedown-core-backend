package dev.tracedown.gateway.routes.v1.presets

import dev.tracedown.gateway.controllers.presets.RulePresetController
import dev.tracedown.gateway.data.presets.CreateRulePresetRequest
import dev.tracedown.gateway.data.presets.UpdateRulePresetRequest
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable

/**
 * @OpenAPITag RulePresets
 * Preset Library — Lace script starters for the service editor.
 */
@Serializable
@Resource("/api/v1/rule-presets")
class RulePresets(val workspaceId: String? = null) {
    @Serializable
    @Resource("{presetId}")
    class ById(val parent: RulePresets = RulePresets(), val presetId: String)
}

fun Route.rulePresetRoutes() {
    /** Lists presets visible in the workspace context (org + workspace). */
    get<RulePresets> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val workspaceId = resource.workspaceId?.let { parseUuid(it, "workspace ID") }
        call.respond(RulePresetController.list(orgId, principal.userId, workspaceId))
    }

    /** Saves an org preset (org-wide, or workspace-scoped via workspaceId). */
    post<RulePresets> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateRulePresetRequest>(call)
        call.respond(RulePresetController.create(orgId, principal.userId, body))
    }

    /** Returns one preset the caller may read. */
    get<RulePresets.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val presetId = parseUuid(resource.presetId, "preset ID")
        call.respond(RulePresetController.get(orgId, principal.userId, presetId))
    }

    /** Renames a preset or replaces its script. */
    patch<RulePresets.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val presetId = parseUuid(resource.presetId, "preset ID")
        val body = tryReceive<UpdateRulePresetRequest>(call)
        call.respond(RulePresetController.update(orgId, principal.userId, presetId, body))
    }

    /** Deletes an org preset. */
    delete<RulePresets.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val presetId = parseUuid(resource.presetId, "preset ID")
        RulePresetController.delete(orgId, principal.userId, presetId)
        call.respond(mapOf("ok" to true))
    }
}
