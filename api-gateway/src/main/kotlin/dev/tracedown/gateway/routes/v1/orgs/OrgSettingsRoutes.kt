package dev.tracedown.gateway.routes.v1.orgs

import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.controllers.orgs.OrgSettingsController
import dev.tracedown.gateway.controllers.orgs.OrgVariableController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.UpdateVariableRequest
import dev.tracedown.gateway.util.parsePfsParams
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.orgs.DeleteOrgRequest
import dev.tracedown.gateway.data.orgs.TransferOwnershipRequest
import dev.tracedown.gateway.data.orgs.UpdateOrgSettingsRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.gateway.util.parseUuid
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * @OpenAPITag Organization Settings
 * Organization settings, ownership transfer, deletion, and org-level variables.
 */
@Resource("/api/v1/org")
class Org {
    @Resource("settings")
    class Settings(val parent: Org = Org())

    @Resource("transfer")
    class Transfer(val parent: Org = Org())

    @Resource("variables")
    class Variables(val parent: Org = Org()) {
        @Resource("{varId}")
        class ById(val parent: Variables = Variables(), val varId: String)
    }
}

fun Route.orgSettingsRoutes(appConfig: AppConfig, emailPublisher: EmailPublisher) {
    /** Returns the organization's settings. */
    get<Org.Settings> {
        val (principal, orgId) = requireAuthWithOrg(call)
        call.respond(OrgSettingsController.getSettings(orgId, principal.userId))
    }

    /** Updates organization name and/or TOTP enforcement. */
    patch<Org.Settings> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<UpdateOrgSettingsRequest>(call)
        call.respond(OrgSettingsController.updateSettings(orgId, body, principal.userId))
    }

    /** Transfers org ownership to another active member. Owner only. */
    post<Org.Transfer> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<TransferOwnershipRequest>(call)
        // Sensitive operation: re-verify the owner's password + TOTP.
        AuthController.verifyIdentity(principal.userId, body.password, body.code)
        val newOwnerId = parseUuid(body.newOwnerId, "new owner ID")
        call.respond(OrgSettingsController.transferOwnership(orgId, newOwnerId, principal.userId))
    }

    /** Lists org-level variables. */
    get<Org.Variables> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(OrgVariableController.list(orgId, principal.userId, pfs))
    }

    /** Creates an org-level variable. */
    post<Org.Variables> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateVariableRequest>(call)
        call.respond(OrgVariableController.create(orgId, body, principal.userId))
    }

    /** Reveals a variable's decrypted value. */
    get<Org.Variables.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val varId = parseUuid(resource.varId, "variable ID")
        call.respond(OrgVariableController.reveal(orgId, varId, principal.userId))
    }

    /** Updates a variable's value. */
    patch<Org.Variables.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val varId = parseUuid(resource.varId, "variable ID")
        val body = tryReceive<UpdateVariableRequest>(call)
        call.respond(OrgVariableController.update(orgId, varId, body, principal.userId))
    }

    /** Soft-deletes a variable. */
    delete<Org.Variables.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val varId = parseUuid(resource.varId, "variable ID")
        OrgVariableController.delete(orgId, varId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /**
     * Soft-deletes the organization outright. Owner only, with the same
     * password + TOTP confirmation as an ownership transfer. Single-org
     * installs bootstrap a fresh org on the next gateway startup.
     */
    delete<Org> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<DeleteOrgRequest>(call)
        AuthController.verifyIdentity(principal.userId, body.password, body.code)
        // Read before the delete: afterwards the org row is gone from the
        // non-deleted view and the owner's membership with it.
        val (orgName, owner) = transaction {
            val orgName = Organizations.selectAll()
                .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
                .firstOrNull()?.get(Organizations.name)
            val owner = Users.selectAll().where { Users.id eq principal.userId }.firstOrNull()
            orgName to owner
        }
        OrgSettingsController.deleteOrg(orgId, principal.userId, appConfig.systemLimits.purgeRetentionDays)
        if (orgName != null && owner != null) {
            sendOrgDeletedEmail(emailPublisher, owner, orgName, appConfig.systemLimits.purgeRetentionDays)
        }
        call.respond(mapOf("ok" to true))
    }
}

/**
 * The one mail an org deletion sends: to the owner who did it, confirming what
 * went and when its data is purged. The account-deletion cascade does not come
 * through here — its own notice covers the orgs it takes with it.
 */
private fun sendOrgDeletedEmail(
    emailPublisher: EmailPublisher,
    owner: ResultRow,
    orgName: String,
    purgeRetentionDays: Int,
) {
    val now = Instant.now()
    val date = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC)
    emailPublisher.publish(
        to = owner[Users.email],
        subject = "$orgName has been deleted",
        type = "system.org-deleted",
        vars = mapOf(
            "userName" to owner[Users.displayName],
            "orgName" to orgName,
            "deletedDate" to date.format(now),
            "purgeDate" to date.format(now.plusSeconds(purgeRetentionDays * 86400L)),
        ),
        source = "api-gateway",
    )
}
