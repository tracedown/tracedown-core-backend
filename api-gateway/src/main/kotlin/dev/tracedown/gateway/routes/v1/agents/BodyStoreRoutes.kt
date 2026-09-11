package dev.tracedown.gateway.routes.v1.agents

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.storage.BodyStoreInput
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.bodyStoreCall
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import dev.tracedown.gateway.util.tryReceive
import io.ktor.http.HttpStatusCode
import io.ktor.resources.Resource
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * @OpenAPITag Body stores
 * Where agents keep the response bodies they capture, when not in the default
 * (environment-configured) store. A store is `s3` (endpoint, region, bucket,
 * prefix, access key + secret) or `filesystem` (a root directory mounted into
 * the gateway, the ingestor and the worker at the same path, inside
 * `BODY_STORE_FILESYSTEM_BASES`). Mode `import`: the agent writes to the store
 * and each body is copied into the default store at ingest. Mode `in_place`:
 * the body stays in the store and is read from it on demand; the platform never
 * deletes from it. The secret access key is write-only — responses carry
 * `hasSecret` instead. Same permission as agent management (org settings).
 *
 * Errors: `body_store_not_found` (404), `body_store_in_use` (409, `details`
 * counts agents/tokens/bodies), `body_store_name_taken` (409),
 * `invalid_store_kind`, `invalid_store_mode`, `store_field_required`,
 * `field_invalid`, `field_too_long` (400, `details.field`).
 */
@Resource("/api/v1/body-stores")
class BodyStores {
    /** The default store, read-only: `{ kind, bucket?, prefix?, rootPath? }`. */
    @Resource("default")
    class Default(val parent: BodyStores = BodyStores())

    @Serializable
    @Resource("{id}")
    class ById(val parent: BodyStores = BodyStores(), val id: String) {
        /** Read-only probe with the store's credentials → `{ ok, error? }`. */
        @Serializable
        @Resource("test")
        class Test(val parent: ById)
    }
}

@Serializable
data class OkResponse(val ok: Boolean)

/** A store id from the path; a malformed one names no store. */
private fun storeIdOf(raw: String): UUID =
    runCatching { UUID.fromString(raw) }.getOrNull() ?: throw NotFoundException(ErrorCodes.BODY_STORE_NOT_FOUND)

/** Registers the body-store management routes. */
fun Route.bodyStoreRoutes() {
    /** Lists the body stores with the number of agents on each. */
    get<BodyStores> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val stores = transaction {
            requireOrgRead(orgId, principal.userId) { it.settings }
            BodyStoreService.list()
        }
        call.respond(stores)
    }

    /** Describes the default store (configured by environment, not editable here). */
    get<BodyStores.Default> {
        val (principal, orgId) = requireAuthWithOrg(call)
        transaction { requireOrgRead(orgId, principal.userId) { it.settings } }
        call.respond(BodyStoreService.defaultStore)
    }

    /** Creates a store; responds 201 with the store row. */
    post<BodyStores> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<BodyStoreInput>(call)
        val created = bodyStoreCall {
            transaction {
                requireOrgWrite(orgId, principal.userId) { it.settings }
                val store = BodyStoreService.create(body, InterceptorContext(orgId = orgId, userId = principal.userId))
                AuditService.log(orgId, principal.userId, "create.body_store", "body_store", store.id, entityDisplayName = store.name)
                store
            }
        }
        call.respond(HttpStatusCode.Created, created)
    }

    /** Replaces a store's settings; an omitted `secretAccessKey` keeps the stored one. Responds with the row. */
    put<BodyStores.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val id = storeIdOf(resource.id)
        val body = tryReceive<BodyStoreInput>(call)
        val updated = bodyStoreCall {
            transaction {
                requireOrgWrite(orgId, principal.userId) { it.settings }
                val store = BodyStoreService.update(id, body, InterceptorContext(orgId = orgId, userId = principal.userId))
                AuditService.log(orgId, principal.userId, "update.body_store", "body_store", store.id, entityDisplayName = store.name)
                store
            }
        }
        call.respond(updated)
    }

    /** Deletes a store — 409 `body_store_in_use` while an agent, a token or a stored body names it. */
    delete<BodyStores.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val id = storeIdOf(resource.id)
        bodyStoreCall {
            transaction {
                requireOrgWrite(orgId, principal.userId) { it.settings }
                val name = BodyStoreService.get(id).name
                BodyStoreService.delete(id, InterceptorContext(orgId = orgId, userId = principal.userId))
                AuditService.log(orgId, principal.userId, "delete.body_store", "body_store", id.toString(), entityDisplayName = name)
            }
        }
        call.respond(OkResponse(true))
    }

    /** Probes the store (read-only) and reports whether it answered. */
    post<BodyStores.ById.Test> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val id = storeIdOf(resource.parent.id)
        transaction { requireOrgWrite(orgId, principal.userId) { it.settings } }
        call.respond(bodyStoreCall { BodyStoreService.test(id) })
    }
}
