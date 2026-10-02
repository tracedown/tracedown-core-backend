package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.common.audit.AuditActor
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.context.Credential
import dev.tracedown.gateway.routes.publicapi.v1.keyRoutes
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.util.ApiNamespace
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.UnauthorizedException
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.call
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Who is calling the key-authenticated API: the user a key acts as, the
 * organization the key belongs to, and the key itself.
 *
 * [userId] and [orgId] are what a handler passes to a controller, exactly as a
 * session route passes its principal's. [access] has already been enforced by
 * the time a handler sees this.
 */
data class ApiCaller(
    val userId: UUID,
    val orgId: UUID,
    val email: String,
    val keyId: UUID,
    /** The key's own ceiling — an `AccessLevel`: read or write. */
    val access: Short,
) {
    internal companion object {
        val attributeKey = AttributeKey<ApiCaller>("ApiCaller")
    }
}

/** The caller of a request inside the key-authenticated API. Only defined there. */
val ApplicationCall.apiCaller: ApiCaller
    get() = attributes[ApiCaller.attributeKey]

/**
 * The key-authenticated API: a route tree of its own, with its own version
 * line, reached with an API key instead of a session.
 *
 * It is not the dashboard's API with a different lock on it. The dashboard's
 * routes are shaped by its screens; these are shaped for direct callers —
 * scripts, pipelines, agents — and may offer an operation the dashboard has no
 * route for. What does not differ is who may do what.
 *
 * **Everything a request must pass is enforced ahead of the handlers, not by
 * them.** Every request whose path lies in the namespace ([ROOT]) — whatever
 * route it is going to, however and wherever that route was registered, and
 * whether or not one exists — goes through, in this order:
 *
 *  1. the key's request budget is spent and the key resolved to the user it
 *     acts as ([requireAuth]; the namespace is what makes it read a key).
 *     Budget first: a call that is about to be refused, for any reason below,
 *     has still spent its budget;
 *  2. the key's own ceiling is applied: a read-only key is refused anything
 *     but a read;
 *  3. every registered [guard] has let the call through;
 *  4. the key is named as the credential behind whatever the handler goes on
 *     to audit.
 *
 * So there is no handler that can forget one of them, and no way to mount a
 * route that is in the namespace but outside them: the steps are keyed on the
 * request's path, not on the route tree ([install]).
 *
 * **What a key may do is never more than its user may.** A handler here decides
 * with the same permission checks, keyed on [ApiCaller.userId] in
 * [ApiCaller.orgId], that meet that user's session — by calling the controller
 * function the dashboard calls wherever there is one, and through the same
 * permission functions where there is not. A handler never authorizes on the
 * key. Reads must not change state: step 2 reads the method and trusts it.
 *
 * ## Extending
 *
 * A host adds to this API without changing it:
 *  - [routes] mounts further endpoints inside [V1];
 *  - [guard] adds a check to step 3, for this module's endpoints and the
 *    host's alike;
 *  - the interception hooks on controller operations apply to calls arriving
 *    here as they do to any other, because the same functions are called.
 *
 * Register [routes] during startup, before the gateway module is installed. A
 * [guard] takes effect from the moment it is registered.
 */
object PublicApi {

    /** Everything under this prefix is authenticated by an API key, and only by one. */
    const val ROOT = ApiNamespace.PUBLIC_ROOT

    const val V1 = "$ROOT/v1"

    private val extensions = CopyOnWriteArrayList<Route.() -> Unit>()
    private val guards = CopyOnWriteArrayList<suspend (ApiCaller, ApplicationCall) -> Unit>()

    private var mounted = false

    /**
     * Mounts additional routes inside [V1]. Paths are relative to it, and the
     * caller is available as [apiCaller].
     *
     * Must be called before the gateway module builds its routing; afterwards
     * the routes would never be mounted, so that is an error rather than a
     * silent no-op.
     */
    fun routes(block: Route.() -> Unit) = synchronized(this) {
        check(!mounted) { "PublicApi.routes must be registered before the gateway module is installed" }
        extensions += block
    }

    /**
     * Adds a check that runs for every call to this API after its caller is
     * known and before its handler. Throw an `ApiException` to refuse the
     * call; a guard that responds instead stops the call just the same.
     */
    fun guard(check: suspend (ApiCaller, ApplicationCall) -> Unit) {
        guards += check
    }

    /**
     * Removes every registered route block and guard, and lets [routes] be
     * called again. For tests, which build more than one gateway in a process;
     * a running gateway that calls this has dropped its guards.
     */
    fun clearAll() = synchronized(this) {
        extensions.clear()
        guards.clear()
        mounted = false
    }

    /** The routes this module provides, with whatever a host registered. */
    internal fun mount(parent: Route) = synchronized(this) {
        parent.route(V1) {
            keyRoutes()
            extensions.forEach { it() }
        }
        mounted = true
    }

    /**
     * The steps, as an application plugin: it runs for every call in the
     * namespace, before routing decides whether there is a handler at all. It
     * wraps the rest of the call rather than merely preceding it, because step
     * 4 is a coroutine context the handler has to run inside — and a call
     * outside the namespace runs under the empty attribution for the same
     * reason: a request resumed on a thread another request is using must
     * find its own answer there, not the neighbour's.
     */
    internal fun install(application: Application) {
        application.install(Enforcement)
    }

    private val Enforcement = createApplicationPlugin("PublicApiEnforcement") {
        on(AroundCall) { call, proceed ->
            if (!ApiNamespace.isPublicUri(call.request.local.uri)) {
                withContext(AuditActor.asContextElement(null)) { proceed() }
                return@on
            }
            val caller = admit(call)
            // A guard that answered for itself has ended the call.
            if (call.response.isCommitted) return@on
            call.attributes.put(ApiCaller.attributeKey, caller)
            // A context element, so the attribution stays with this call across
            // suspension and never reaches another. It covers the handler and
            // whatever it runs in its own coroutine or a `withContext` of it —
            // not a coroutine launched from the call, which starts outside.
            withContext(AuditActor.asContextElement(caller.keyId)) { proceed() }
        }
    }

    /** Runs a handler around the remainder of the call's pipeline. */
    private object AroundCall : Hook<suspend (ApplicationCall, suspend () -> Unit) -> Unit> {
        override fun install(
            pipeline: ApplicationCallPipeline,
            handler: suspend (ApplicationCall, suspend () -> Unit) -> Unit,
        ) {
            pipeline.intercept(ApplicationCallPipeline.Plugins) { handler(call) { proceed() } }
        }
    }

    /** Steps 1–3. Returns the caller, or throws the refusal. */
    private suspend fun admit(call: ApplicationCall): ApiCaller {
        val principal = requireAuth(call)
        // The namespace already guarantees a key; a session reaching here would
        // mean that guarantee broke, and the answer to that is not to proceed.
        val key = principal.credential as? Credential.ApiKey
            ?: throw UnauthorizedException(ErrorCodes.INVALID_API_KEY)
        val orgId = principal.organizationId
            ?: throw BadRequestException(ErrorCodes.NO_ORG_SELECTED)
        val caller = ApiCaller(principal.userId, orgId, principal.email, key.keyId, key.access)

        if (!key.access.canWrite() && call.request.httpMethod !in READ_METHODS) {
            throw ForbiddenException(ErrorCodes.API_KEY_READ_ONLY)
        }

        for (guard in guards) {
            guard(caller, call)
            if (call.response.isCommitted) break
        }
        return caller
    }

    private val READ_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)
}

/** Mounts the key-authenticated API's routes, with whatever a host has registered into it. */
fun Route.publicApiRoutes() = PublicApi.mount(this)
