package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.agents.AgentDirectory
import dev.tracedown.gateway.routes.publicapi.apiCaller
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * The agents a service can be set to run on.
 */
fun Route.agentRoutes() {
    /**
     * Lists the agents the caller can name in `PUT /services/{id}/agents`, by
     * slug and label — nothing about where they are or how they are doing.
     * Any member may read it, as any member may read the agents' health.
     */
    get("/agents") {
        val caller = call.apiCaller
        call.respond(AgentDirectory.list(caller.orgId, caller.userId))
    }
}
