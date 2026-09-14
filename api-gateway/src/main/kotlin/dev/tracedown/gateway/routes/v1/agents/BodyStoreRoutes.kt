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
 * Where an organization's agents keep the response bodies they capture, when not
 * in the default (environment-configured) store. A store is `s3` (endpoint,
 * region, bucket, prefix, access key + secret) or `filesystem` (a root directory
 * mounted into the gateway and the ingestor at the same path, inside
 * `BODY_STORE_FILESYSTEM_BASES`). Mode `import`: the agent writes to the store
 * and each body is copied into the default store at ingest. Mode `in_place`:
 * the body stays in the store and is read from it on demand; the platform never
 * deletes from it. The secret access key is write-only — responses carry
 * `hasSecret` instead. Same permission as agent management (org settings).
 *
 * A store belongs to the organization it was created in. Another organization's
 * store is not listed and naming its id is `body_store_not_found`, whatever the
 * caller's permissions.
 *
 * Each agent writes under its own sub-location of a store (`<prefix>/<slug>`,
 * `<root>/<slug>`), which is what the bootstrap token's `bodyStore` prints and
 * the only place ingest accepts that agent's bodies from.
 *
 * Errors: `body_store_not_found` (404), `body_store_in_use` (409, `details`
 * counts agents/tokens/bodies), `body_store_name_taken` (409),
 * `body_store_location_locked` (409, the location may not move while a stored
 * body lives in it), `invalid_store_kind`, `invalid_store_mode`,
 * `store_field_required`, `field_invalid`, `field_too_long` (400,
 * `details.field`, and `details.reason` on `field_invalid`).
 */
@Resource("/api/v1/body-stores")
class BodyStores {
    /** The default store, read-only: `{ kind, bucket?, prefix?, rootPath? }`. */
    @Resource("default")
    class Default(val parent: BodyStores = BodyStores())

    @Serializable
    @Resource("{id}")
    class ById(val parent: BodyStores = BodyStores(), val id: String, val forgetBodies: Boolean = false) {
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
    /** Lists the organization's body stores with the number of agents on each. */
    get<BodyStores> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val stores = transaction {
            requireOrgRead(orgId, principal.userId) { it.settings }
            BodyStoreService.list(orgId)
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
                val store = BodyStoreService.create(orgId, body, InterceptorContext(orgId = orgId, userId = principal.userId))
                AuditService.log(
                    orgId, principal.userId, "create.body_store", "body_store", store.id,
                    entityDisplayName = store.name,
                    comment = "${store.kind}/${store.mode}",
                )
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
                val ctx = InterceptorContext(orgId = orgId, userId = principal.userId)
                val store = BodyStoreService.update(orgId, id, body, ctx)
                // What moved, never a value: the audit trail of a store is about
                // repointing, and one of its fields is a credential.
                AuditService.log(
                    orgId, principal.userId, "update.body_store", "body_store", store.id,
                    entityDisplayName = store.name,
                    comment = (ctx.extra["changed"] as? String)?.takeIf { it.isNotEmpty() }?.let { "changed: $it" },
                )
                store
            }
        }
        call.respond(updated)
    }

    /**
     * Deletes a store — 409 `body_store_in_use` while an agent, an outstanding
     * token or a stored body names it.
     *
     * `?forgetBodies=true` releases those instead: its agents move back to the
     * default store, its outstanding tokens lose their stamp, and every step
     * whose body lives in the store forgets that body (reason `storeRemoved`).
     * The objects themselves are never touched — they belong to whoever owns the
     * store, and after this the platform simply no longer knows about them.
     */
    delete<BodyStores.ById> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val id = storeIdOf(resource.id)
        val forget = resource.forgetBodies
        bodyStoreCall {
            transaction {
                requireOrgWrite(orgId, principal.userId) { it.settings }
                val name = BodyStoreService.get(orgId, id).name
                val released = BodyStoreService.delete(orgId, id, forget, InterceptorContext(orgId = orgId, userId = principal.userId))
                AuditService.log(
                    orgId, principal.userId, "delete.body_store", "body_store", id.toString(),
                    entityDisplayName = name,
                    comment = if (!forget) null else {
                        "forgot ${released.bodies} bodies, unassigned ${released.agents} agents, " +
                            "${released.tokens} tokens"
                    },
                )
            }
        }
        call.respond(OkResponse(true))
    }

    /** Probes the store (read-only) and reports whether it answered. */
    post<BodyStores.ById.Test> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val id = storeIdOf(resource.parent.id)
        transaction { requireOrgWrite(orgId, principal.userId) { it.settings } }
        call.respond(bodyStoreCall { BodyStoreService.test(orgId, id) })
    }
}
