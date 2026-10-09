package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.gateway.controllers.notifications.NotificationTemplateController
import dev.tracedown.gateway.data.notifications.BindProjectRequest
import dev.tracedown.gateway.data.notifications.CreateNotificationTemplateRequest
import dev.tracedown.gateway.data.notifications.UpdateNotificationTemplateRequest
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.util.publicPaging
import dev.tracedown.gateway.util.tryReceive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * Notification templates — the text a notification is written with — and
 * which projects use them.
 */
fun Route.notificationTemplateRoutes() {
    /** Lists the organization's templates, oldest first. Paged with `page` and `pageSize`. */
    get("/notification-templates") {
        val caller = call.apiCaller
        call.respond(NotificationTemplateController.list(caller.orgId, caller.userId, publicPaging(call)))
    }

    /** Creates a template, bound to the projects in `projectIds` when it names any. */
    post("/notification-templates") {
        val caller = call.apiCaller
        val body = tryReceive<CreateNotificationTemplateRequest>(call)
        call.respond(NotificationTemplateController.create(caller.orgId, body, caller.userId))
    }

    /** Returns one template. */
    get("/notification-templates/{id}") {
        val caller = call.apiCaller
        val templateId = call.pathUuid("id")
        call.respond(NotificationTemplateController.get(caller.orgId, templateId, caller.userId))
    }

    /** Renames a template or replaces its text. */
    patch("/notification-templates/{id}") {
        val caller = call.apiCaller
        val templateId = call.pathUuid("id")
        val body = tryReceive<UpdateNotificationTemplateRequest>(call)
        call.respond(NotificationTemplateController.update(caller.orgId, templateId, body, caller.userId))
    }

    /** Deletes a template. */
    delete("/notification-templates/{id}") {
        val caller = call.apiCaller
        val templateId = call.pathUuid("id")
        NotificationTemplateController.delete(caller.orgId, templateId, caller.userId)
        call.respond(mapOf("ok" to true))
    }

    /** The templates bound to a project, oldest first. Paged with `page` and `pageSize`. */
    get("/projects/{id}/notification-templates") {
        val caller = call.apiCaller
        val projectId = call.pathUuid("id")
        call.respond(NotificationTemplateController.listForProject(caller.orgId, projectId, caller.userId, publicPaging(call)))
    }

    /** Binds a template to a project. Binding one that is bound already changes nothing. */
    put("/projects/{id}/notification-templates/{templateId}") {
        val caller = call.apiCaller
        val projectId = call.pathUuid("id")
        val templateId = call.pathUuid("templateId")
        call.respond(
            NotificationTemplateController.bindProject(
                caller.orgId, templateId, BindProjectRequest(projectId.toString()), caller.userId, alreadyBoundOk = true,
            ),
        )
    }

    /** Unbinds a template from a project. */
    delete("/projects/{id}/notification-templates/{templateId}") {
        val caller = call.apiCaller
        val projectId = call.pathUuid("id")
        val templateId = call.pathUuid("templateId")
        call.respond(NotificationTemplateController.unbindProject(caller.orgId, templateId, projectId, caller.userId))
    }
}
