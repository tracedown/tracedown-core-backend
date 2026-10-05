package dev.tracedown.gateway.routes.v1.me

import dev.tracedown.gateway.controllers.me.UserDataController
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.data.me.ChangeEmailRequest
import dev.tracedown.gateway.data.me.ConfirmEmailChangeRequest
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * @OpenAPITag Me
 * Account-scoped data: personal data export, and changing the account email.
 */
@Resource("/api/v1/me")
class Me {
    @Resource("export")
    class Export(val parent: Me = Me())

    @Resource("email")
    class Email(val parent: Me = Me())

    @Resource("email/confirm")
    class EmailConfirm(val parent: Me = Me())
}

fun Route.meRoutes(appConfig: AppConfig, emailPublisher: EmailPublisher) {
    /**
     * Returns a single JSON document of all data stored about the calling
     * user (secrets excluded). Versioned envelope — see UserDataExport.
     */
    get<Me.Export> {
        val principal = requireAuth(call)
        call.respond(UserDataController.export(principal.userId, principal.sessionId))
    }

    /**
     * Asks to change the account email. Requires the current password, plus a
     * TOTP code when enrolled. Nothing changes until the link mailed to the
     * new address is followed. Off unless the operator switched
     * `platform.allowEmailChange` on.
     */
    post<Me.Email> {
        val principal = requireAuth(call)
        if (!appConfig.platform.allowEmailChange) throw ForbiddenException(ErrorCodes.EMAIL_CHANGE_DISABLED)
        val body = tryReceive<ChangeEmailRequest>(call)
        val result = UserDataController.requestEmailChange(
            userId = principal.userId,
            request = body,
            emailPublisher = emailPublisher,
            confirmUrlBuilder = appConfig.platform.uri::emailChangeUrl,
        )
        call.respond(result)
    }

    /**
     * Writes the change the link mailed to the new address asked for. The link
     * is the credential, so no session is needed; every session is signed out
     * once the address has changed.
     */
    post<Me.EmailConfirm> {
        if (!appConfig.platform.allowEmailChange) throw ForbiddenException(ErrorCodes.EMAIL_CHANGE_DISABLED)
        val body = tryReceive<ConfirmEmailChangeRequest>(call)
        call.respond(UserDataController.confirmEmailChange(body.token, emailPublisher))
    }
}
