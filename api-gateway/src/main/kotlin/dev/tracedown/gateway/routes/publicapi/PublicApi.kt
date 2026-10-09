package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.common.audit.AuditActor
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.net.PathCanonicalizer
import dev.tracedown.gateway.context.Credential
import dev.tracedown.gateway.routes.publicapi.v1.accessRoutes
import dev.tracedown.gateway.routes.publicapi.v1.directoryRoutes
import dev.tracedown.gateway.routes.publicapi.v1.keyRoutes
import dev.tracedown.gateway.routes.publicapi.v1.metricsRoutes
import dev.tracedown.gateway.routes.publicapi.v1.projectRoutes
import dev.tracedown.gateway.routes.publicapi.v1.resultRoutes
import dev.tracedown.gateway.routes.publicapi.v1.scriptRoutes
import dev.tracedown.gateway.routes.publicapi.v1.serviceRoutes
import dev.tracedown.gateway.routes.publicapi.v1.silenceRoutes
import dev.tracedown.gateway.routes.publicapi.v1.variableRoutes
import dev.tracedown.gateway.routes.publicapi.v1.webhookRoutes
import dev.tracedown.gateway.routes.publicapi.v1.workspaceRoutes
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.util.ApiNamespace
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.Idempotency
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
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.describe
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.HttpStatusCodeContent
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.TextContent
import io.ktor.server.application.hooks.ResponseBodyReadyForSend
import io.ktor.server.plugins.mutableOriginConnectionPoint
import dev.tracedown.gateway.routes.publicapi.v1.agentRoutes
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import io.ktor.utils.io.ExperimentalKtorApi
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
 *     but a read (and the few POSTs that change nothing, [READ_ONLY_POSTS]);
 *  3. every registered [guard] has let the call through;
 *  4. the key is named as the credential behind whatever the handler goes on
 *     to audit;
 *  5. a POST carrying an `Idempotency-Key` it was already answered for is
 *     answered from the record instead of reaching its handler, and one it
 *     was not is remembered once answered ([Idempotency]).
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

    /**
     * Where [V1]'s description (OpenAPI) is served — outside [ROOT] on
     * purpose, so it needs no key: nothing in the namespace is open.
     */
    const val DESCRIPTION_PATH = "/api/openapi/public/v1.json"

    /** Host route blocks, each with the tag its endpoints carry in the description. */
    private val extensions = CopyOnWriteArrayList<Pair<String, Route.() -> Unit>>()
    private val guards = CopyOnWriteArrayList<suspend (ApiCaller, ApplicationCall) -> Unit>()

    private var mounted = false

    /**
     * Mounts additional routes inside [V1]. Paths are relative to it, and the
     * caller is available as [apiCaller]. In the API's description the
     * endpoints are listed under [tag]; a host that wants more there (a
     * summary, an operation id) describes its routes itself.
     *
     * Must be called before the gateway module builds its routing; afterwards
     * the routes would never be mounted, so that is an error rather than a
     * silent no-op.
     */
    fun routes(tag: String = "Extensions", block: Route.() -> Unit) = synchronized(this) {
        check(!mounted) { "PublicApi.routes must be registered before the gateway module is installed" }
        extensions += tag to block
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

    /**
     * The routes this module provides, with whatever a host registered.
     * Returns the [V1] subtree, which is what the API's description is
     * generated from.
     *
     * Every endpoint this module mounts is described from its entry in
     * [PublicApiOperations] — and one without an entry stops the gateway from
     * starting, so the description can never miss a route. A host's endpoints
     * carry the tag they were registered with.
     */
    @OptIn(ExperimentalKtorApi::class)
    internal fun mount(parent: Route): RoutingNode = synchronized(this) {
        val v1 = parent.route(V1) {} as RoutingNode
        v1.keyRoutes()
        v1.workspaceRoutes()
        v1.projectRoutes()
        v1.serviceRoutes()
        v1.agentRoutes()
        v1.variableRoutes()
        v1.resultRoutes()
        v1.metricsRoutes()
        v1.silenceRoutes()
        v1.accessRoutes()
        v1.directoryRoutes()
        v1.webhookRoutes()
        v1.scriptRoutes()
        for (route in v1.endpoints()) {
            val (method, path) = route
            val operation = PublicApiOperations.find(method, path)
                ?: error("${method.value} $V1$path has no entry in PublicApiOperations")
            route.node.describe { describe(operation) }
        }
        for ((tag, block) in extensions) {
            val before = v1.endpoints().map { it.node }.toSet()
            v1.block()
            v1.endpoints().filter { it.node !in before }.forEach { it.node.describe { tag(tag) } }
        }
        mountedPaths.clear()
        v1.endpoints().forEach { endpoint ->
            val pattern = (V1 + endpoint.path).split('/').joinToString("/") { segment ->
                if (segment.startsWith("{") && segment.endsWith("}")) "[^/]+" else Regex.escape(segment)
            }
            mountedPaths += endpoint.method to Regex(pattern)
        }
        mounted = true
        v1
    }

    /** An endpoint under [V1]: its method, its path relative to [V1], and its handler's node. */
    private data class Endpoint(val method: HttpMethod, val path: String, val node: RoutingNode)

    private fun RoutingNode.endpoints(): List<Endpoint> = getAllRoutes().mapNotNull { node ->
        val method = (node.selector as? HttpMethodRouteSelector)?.method ?: return@mapNotNull null
        val path = node.parent?.toString()?.removePrefix(V1) ?: return@mapNotNull null
        Endpoint(method, path, node)
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
        application.install(Responses)
    }

    private val Enforcement = createApplicationPlugin("PublicApiEnforcement") {
        on(AroundCall) { call, proceed ->
            val uri = call.request.local.uri
            if (!ApiNamespace.isPublicUri(uri)) {
                // A path that names the namespace but will not reduce to one
                // canonical form is in no namespace at all — refused here, not
                // left to whatever the router makes of it.
                if (ApiNamespace.claimsPublicUri(uri)) throw BadRequestException(ErrorCodes.INVALID_PATH)
                withContext(AuditActor.asContextElement(null)) { proceed() }
                return@on
            }
            // HEAD on a read is served as the read, without its body — the read
            // cap admits it, so the routes answer it too. A route that answers
            // HEAD itself keeps it.
            if (call.request.local.method == HttpMethod.Head &&
                !isMountedPath(uri, HttpMethod.Head) && isMountedPath(uri, HttpMethod.Get)
            ) {
                call.mutableOriginConnectionPoint.method = HttpMethod.Get
                call.attributes.put(headRequest, Unit)
            }
            val caller = admit(call)
            // A guard that answered for itself has ended the call.
            if (call.response.isCommitted) return@on
            call.attributes.put(ApiCaller.attributeKey, caller)
            // A context element, so the attribution stays with this call across
            // suspension and never reaches another. It covers the handler and
            // whatever it runs in its own coroutine or a `withContext` of it —
            // not a coroutine launched from the call, which starts outside.
            withContext(AuditActor.asContextElement(caller.keyId)) {
                // Step 5: a POST carrying an Idempotency-Key that was already
                // answered is answered again from the record, and its handler
                // does not run.
                // Only a POST a route takes: a path nothing answers has
                // nothing to remember. Its key is decided as its answer goes
                // out (Responses, below), or as it is cut off with none.
                val path = PathCanonicalizer.canonicalize(uri)
                if (call.request.local.method == HttpMethod.Post && path != null && path !in READ_ONLY_POSTS &&
                    isMountedPath(uri, HttpMethod.Post)
                ) {
                    Idempotency.begin(call, caller.keyId, caller.orgId, caller.userId, path)
                    if (call.response.isCommitted) return@withContext
                }
                try {
                    proceed()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Cut off under its handler with no answer: decided as an
                    // unknown outcome (a no-op for a call without a key).
                    Idempotency.cancelled(call)
                    throw e
                }
            }
        }
    }

    /** Every endpoint mounted under [V1]: its method, and a pattern its paths match (`{x}` is one segment). */
    private val mountedPaths = CopyOnWriteArrayList<Pair<HttpMethod, Regex>>()

    /** Whether [rawUri] names a path mounted under [V1] — for [method], or for any method when it is null. */
    internal fun isMountedPath(rawUri: String, method: HttpMethod? = null): Boolean {
        val path = PathCanonicalizer.canonicalize(rawUri) ?: return false
        return mountedPaths.any { (m, pattern) -> (method == null || m == method) && pattern.matches(path) }
    }

    /**
     * The namespace's answer for a request no handler answered — what the
     * router or the engine would send as a bare status: 405
     * `method_not_allowed` for a mounted path asked with a method it does not
     * take (the router says 404 for both), 404 `not_found` otherwise. Null
     * outside the namespace, or for a status that is not one of those.
     */
    internal fun unmatched(rawUri: String, status: HttpStatusCode): Pair<HttpStatusCode, String>? {
        if (!ApiNamespace.isPublicUri(rawUri)) return null
        return when {
            status == HttpStatusCode.MethodNotAllowed ||
                (status == HttpStatusCode.NotFound && isMountedPath(rawUri)) ->
                HttpStatusCode.MethodNotAllowed to ErrorCodes.METHOD_NOT_ALLOWED
            status == HttpStatusCode.NotFound -> HttpStatusCode.NotFound to ErrorCodes.NOT_FOUND
            else -> null
        }
    }

    /** Marks a HEAD request being answered as its GET. */
    private val headRequest = AttributeKey<Unit>("PublicApiHead")

    /** Whether [call] is a HEAD being answered by its GET's route — which may then skip the body it would send. */
    fun isHead(call: ApplicationCall): Boolean = call.attributes.contains(headRequest)

    /**
     * The namespace's answers on their way out. The router's own refusals — a
     * bare 404 for a path no route matches, a bare 405 for a method a route
     * does not take — are given the API's error shape, `{"error": code}`; a
     * handler's 404 already carries its own code and is left alone. A HEAD
     * answer is sent without the body its GET would have had.
     */
    private val Responses = createApplicationPlugin("PublicApiResponses") {
        on(ResponseBodyReadyForSend) { call, content ->
            if (!ApiNamespace.isPublicUri(call.request.local.uri)) return@on
            var answer = content
            if (content is HttpStatusCodeContent) {
                unmatched(call.request.local.uri, content.status)?.let { (status, code) ->
                    answer = TextContent("""{"error":"$code"}""", ContentType.Application.Json, status)
                }
            }
            Idempotency.capture(call, answer)
            if (call.attributes.contains(headRequest)) answer = HeadOnly(answer)
            if (answer !== content) transformBodyTo(answer)
        }
    }

    /** [original]'s status and headers, and no body. */
    private class HeadOnly(private val original: OutgoingContent) : OutgoingContent.NoContent() {
        override val status: HttpStatusCode? get() = original.status
        override val contentType: ContentType? get() = original.contentType
        override val contentLength: Long? get() = original.contentLength
        override val headers: Headers get() = original.headers
        override fun <T : Any> getProperty(key: AttributeKey<T>): T? = original.getProperty(key)
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

        if (!key.access.canWrite() && call.request.httpMethod !in READ_METHODS && !isReadOnlyPost(call)) {
            throw ForbiddenException(ErrorCodes.API_KEY_READ_ONLY)
        }

        for (guard in guards) {
            guard(caller, call)
            if (call.response.isCommitted) break
        }
        return caller
    }

    private val READ_METHODS = setOf(HttpMethod.Get, HttpMethod.Head)

    /**
     * POSTs that change nothing — a POST only because what they are given
     * does not fit in a query. A read-only key may make them (they are the one
     * exception to step 2), and they take no `Idempotency-Key`: there is
     * nothing for a repeat to do twice. Canonical paths, Core's own only: a
     * host's route is never added here.
     */
    private val READ_ONLY_POSTS = setOf("$V1/scripts/validate")

    private fun isReadOnlyPost(call: ApplicationCall): Boolean =
        call.request.httpMethod == HttpMethod.Post &&
            PathCanonicalizer.canonicalize(call.request.local.uri) in READ_ONLY_POSTS
}

/**
 * Mounts the key-authenticated API's routes, with whatever a host has
 * registered into it, and returns the subtree they were mounted in.
 */
fun Route.publicApiRoutes(): RoutingNode = PublicApi.mount(this)
