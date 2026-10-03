package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.orgs.GroupController
import dev.tracedown.gateway.controllers.orgs.PermissionController
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.publicPaging
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The organization's members and groups, read-only: where a client finds the
 * ids it grants access to — who they are, not what they may do. Both need
 * read on the organization's users.
 */
fun Route.directoryRoutes() {
    /**
     * Lists the organization's members — everyone but those still invited,
     * disabled members included (`isActive: false`) — by name. Paged with
     * `page` and `pageSize`.
     */
    get("/members") {
        val caller = call.apiCaller
        call.respond(PermissionController.memberDirectory(caller.orgId, caller.userId, publicPaging(call)))
    }

    /** Lists the organization's groups, with member counts, by name. Paged with `page` and `pageSize`. */
    get("/groups") {
        val caller = call.apiCaller
        call.respond(GroupController.groupDirectory(caller.orgId, caller.userId, publicPaging(call)))
    }
}
