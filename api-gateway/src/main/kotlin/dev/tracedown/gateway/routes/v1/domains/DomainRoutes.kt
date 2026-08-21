package dev.tracedown.gateway.routes.v1.domains

import dev.tracedown.gateway.controllers.domains.DomainController
import dev.tracedown.gateway.data.domains.CreateDomainRequest
import dev.tracedown.gateway.data.domains.UpdateDomainRequest
import dev.tracedown.gateway.routes.v1
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
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
 * @OpenAPITag Domains
 * Domain management: add, verify, update, delete.
 */
@Resource("/api/v1/domains")
class Domains {
    @Resource("{domainId}")
    class ById(val parent: Domains = Domains(), val domainId: String) {
        @Resource("verify")
        class Verify(val parent: ById)

        @Resource("dns-handoff")
        class DnsHandoff(val parent: ById)

    }
}

fun Route.domainRoutes() {
    /** Adds a domain to the organization. */
    post<Domains> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateDomainRequest>(call)
        call.respond(DomainController.create(orgId, body, principal.userId))
    }

    /** Lists all domains in the organization. */
    get<Domains> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val pfs = parsePfsParams(call)
        call.respond(DomainController.list(orgId, principal.userId, pfs))
    }

    /** Returns a single domain with its challenge token. */
    get<Domains.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val domainId = parseUuid(resource.domainId, "domain ID")
        call.respond(DomainController.get(orgId, domainId, principal.userId))
    }

    /** Updates domain settings (wildcard). */
    patch<Domains.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val domainId = parseUuid(resource.domainId, "domain ID")
        val body = tryReceive<UpdateDomainRequest>(call)
        call.respond(DomainController.update(orgId, domainId, body, principal.userId))
    }

    /** Soft-deletes a domain. */
    delete<Domains.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val domainId = parseUuid(resource.domainId, "domain ID")
        DomainController.delete(orgId, domainId, principal.userId)
        call.respond(mapOf("ok" to true))
    }

    /** Where this domain's DNS records are edited, when we recognise the provider. */
    get<Domains.ById.DnsHandoff> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val domainId = parseUuid(resource.parent.domainId, "domain ID")
        call.respond(DomainController.dnsHandoff(orgId, domainId, principal.userId))
    }

    /** Triggers domain verification. Checks the challenge token via HTTP or DNS. */
    post<Domains.ById.Verify> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val domainId = parseUuid(resource.parent.domainId, "domain ID")
        call.respond(DomainController.verify(orgId, domainId, principal.userId))
    }
}
