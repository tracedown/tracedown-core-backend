package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.gateway.data.publicapi.PublicApiError
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.openapi.ExternalDocs
import io.ktor.openapi.HttpSecurityScheme
import io.ktor.openapi.OpenApiDoc
import io.ktor.openapi.OpenApiInfo
import io.ktor.openapi.Operation
import io.ktor.openapi.ReferenceOr
import io.ktor.openapi.Server
import io.ktor.openapi.Tag
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.getAllRoutes
import io.ktor.server.routing.openapi.hide
import io.ktor.server.routing.openapi.plus
import io.ktor.utils.io.ExperimentalKtorApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** The name the description gives the API-key scheme every operation requires. */
private const val KEY_SCHEME = "apiKey"

/** Where the API guide lives. */
private const val GUIDE_URL = "https://tracedown.dev/guide/api/"

/**
 * The description's own encoding. The API's encoder writes every absent
 * value as an explicit `null`, which an OpenAPI reader takes as a value of
 * the wrong type; a field that is not set is left out instead.
 */
private val descriptionJson = Json { explicitNulls = false; encodeDefaults = true }

private val API_SUMMARY = """
    The key-authenticated API. Every operation needs an API key, sent as `Authorization: Bearer td_…`. A key acts
    as the user who created it, in one organization, and may never do more than that user may; a read-only key is
    refused anything but GET and HEAD (403 `api_key_read_only`). HEAD is answered on every GET path.

    **Base URL.** The server below is relative, so that a gateway published under a path prefix still gives the
    right addresses; code generators emit it literally — set your client's base URL to the gateway's origin (and
    prefix, if any).

    **Errors** answer `{"error": code}`, with `details` for the codes that need more. A refused query parameter or
    path id names itself in `details.field`; a refused body names the field when the validation knows it; a body
    that is not JSON at all does not. `details.max` is the largest count allowed, `details.maxBytes` the largest
    size in bytes. A path no route answers is 404 `not_found`, a method a path does not take 405
    `method_not_allowed`, a path that will not reduce to one canonical spelling 400 `invalid_path` — all in the
    same shape.

    **Rate budget**: 300 requests a minute per key (the operator may set another). Requests that reached a key's
    budget carry `X-RateLimit-Limit` and `X-RateLimit-Remaining`; a refusal is 429 `rate_limited` with
    `Retry-After` in seconds. An address that keeps sending tokens that name no key (or no token) is refused 429
    `too_many_unknown_keys`, also with `Retry-After`.

    **Lists.** Paged lists answer `{items, total, page, pageSize}` and page with `page` (from 1) and `pageSize` (at
    most 100); there is no filtering or sorting beyond the named query parameters an operation lists. `/agents`,
    `/services/{id}/agents`, `/access/…` and the metrics histories answer bare arrays. Every order is fixed:
    oldest first (by creation, then id) for workspaces, projects, services, variables, webhooks and bindings; by
    name, then id, for members and groups; by id for silences; most recent first for results; by slug for agents;
    groups then users, each by name then id, for access.
""".trimIndent()

/** Each error status, with the codes it is answered with across the API. */
private val STATUS_CODES: Map<HttpStatusCode, String> = mapOf(
    HttpStatusCode.BadRequest to "A refused request: `field_invalid`, `field_required`, `invalid_uuid`, " +
        "`invalid_request_body`, `invalid_path`, `no_org_selected`, and the code a validation names.",
    HttpStatusCode.Unauthorized to "No usable key: `missing_auth_header`, `invalid_api_key`, `api_key_expired`, " +
        "`api_key_revoked`, `api_key_owner_inactive`.",
    HttpStatusCode.Forbidden to "Not allowed: `insufficient_permissions`, `api_key_read_only`, " +
        "`totp_enrollment_required`, `not_org_member`.",
    HttpStatusCode.NotFound to "`not_found` — no such resource, or one the caller may not see.",
    HttpStatusCode.MethodNotAllowed to "`method_not_allowed`.",
    HttpStatusCode.Conflict to "`already_exists`, `version_conflict`, `binding_exists`, `script_missing`, `service_inactive`.",
    HttpStatusCode.Gone to "`body_gone` — the step recorded a body that is no longer there.",
    HttpStatusCode.PayloadTooLarge to "`request_body_too_large` (the request), or `body_too_large` (a stored body; " +
        "`details.maxBytes`).",
    HttpStatusCode.TooManyRequests to "`rate_limited` (the key's budget) or `too_many_unknown_keys` (the address). " +
        "`Retry-After` says when to come back.",
    HttpStatusCode.ServiceUnavailable to "`body_store_unavailable` — retry with backoff after `Retry-After`.",
)

/**
 * The key-authenticated API's description (OpenAPI), generated from the
 * routes mounted under [PublicApi.V1] — every endpoint in that subtree, the
 * host's included, and nothing outside it — and encoded. Each of this
 * module's endpoints is described from its [PublicOperation] (see
 * [PublicApi.mount]); the schemas inferred from the classes are then refined
 * where inference cannot say enough ([refine]).
 */
fun publicApiDescription(v1: RoutingNode): String {
    val doc = OpenApiDoc(
        info = OpenApiInfo(title = "Tracedown API", version = "1", description = API_SUMMARY),
        servers = listOf(Server(url = "../../..", description = "The gateway serving this document")),
        security = listOf(mapOf(KEY_SCHEME to emptyList())),
        tags = PublicApiOperations.tags.map { (name, description) -> Tag(name = name, description = description) },
        externalDocs = ExternalDocs(url = GUIDE_URL, description = "The API guide"),
    ) + v1.getAllRoutes() + mapOf(
        KEY_SCHEME to ReferenceOr.value(
            HttpSecurityScheme(scheme = "bearer", bearerFormat = "td_…", description = "An API key, `td_…`."),
        ),
    )
    val encoded = descriptionJson.encodeToJsonElement(OpenApiDoc.serializer(), doc).jsonObject
    return descriptionJson.encodeToString(JsonObject.serializer(), refine(encoded))
}

/** Describes one of this module's endpoints from its [PublicOperation]. */
internal fun Operation.Builder.describe(operation: PublicOperation) {
    operationId = operation.operationId
    summary = operation.summary
    operation.description?.let { description = it }
    tag(operation.tag)
    if (operation.query.isNotEmpty()) {
        parameters {
            for (parameter in operation.query) {
                query(parameter.name) {
                    description = parameter.description
                    required = parameter.required
                    schema = buildSchema(parameter.type)
                }
            }
        }
    }
    operation.request?.let { type ->
        requestBody {
            required = true
            schema = buildSchema(type)
        }
    }
    responses {
        operation.status {
            description = operation.summary
            operation.response?.let { schema = buildSchema(it) }
        }
        operation.noContent?.let { meaning ->
            HttpStatusCode.NoContent { description = meaning }
        }
        for (status in operation.errorStatuses) {
            status {
                description = STATUS_CODES[status] ?: status.description
                schema = buildSchema(typeOf<PublicApiError>())
            }
        }
    }
}

// ── Refinement ──
//
// Inference reads a class's serializer, and some of what a caller needs to
// know is not there. These passes add it, from the same descriptors, to every
// public type — so a type added later is refined too, where an annotation
// forgotten on one of its fields would leave a hole.

/** Path and query parameters that hold an id. */
private val ID_PARAMETERS = setOf(
    "id", "varId", "resultId", "stepId", "resourceId", "workspaceId", "projectId",
)

/** Bounds and defaults of the integer query parameters: name to (minimum, maximum, default). */
private val INTEGER_PARAMETERS = mapOf(
    "page" to Triple(1, Int.MAX_VALUE, 1),
    "pageSize" to Triple(1, 100, 50),
    "hours" to Triple(1, 168, 24),
    "days" to Triple(1, 365, 90),
)

/** String fields that hold an instant, beyond those named `…At`. */
private val INSTANT_FIELDS = setOf("since", "until", "coveredFrom", "coveredTo", "lastStatusSince", "lastCheck")

private fun refine(doc: JsonObject): JsonObject {
    val components = doc["components"]?.jsonObject ?: return doc
    val schemas = components["schemas"]?.jsonObject ?: return doc
    val descriptors = publicDescriptors()
    val requestClasses = publicDescriptors(PublicApiOperations.all.mapNotNull { it.request }).keys

    val refined = schemas.mapValues { (key, schema) ->
        when {
            // Any JSON value, not an object: rawResult, a header value, an assertion field.
            key == "JsonElement" -> buildJsonObject { put("description", "Any JSON value.") }
            key == "PublicApiError" -> errorSchema()
            else -> descriptors[key]?.let { refineClass(schema.jsonObject, it, responseOnly = key !in requestClasses) } ?: schema
        }
    }
    val paths = doc["paths"]?.jsonObject?.mapValues { (_, item) -> refinePathItem(item.jsonObject) } ?: emptyMap()
    return JsonObject(
        doc + ("components" to JsonObject(components + ("schemas" to JsonObject(refined)))) + ("paths" to JsonObject(paths)),
    )
}

/**
 * The class descriptor behind each component, by the key the generator gives
 * it (the serial name less its package: `ApiKeyInfo.Organization`).
 */
private fun publicDescriptors(types: List<KType> = PublicApiOperations.types): Map<String, SerialDescriptor> {
    val out = mutableMapOf<String, SerialDescriptor>()
    val seen = mutableSetOf<String>()
    fun collect(descriptor: SerialDescriptor) {
        val name = descriptor.serialName.removeSuffix("?")
        if (!seen.add(name + descriptor.elementsCount)) return
        if (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT) {
            out.putIfAbsent(componentKey(name), descriptor)
        }
        descriptor.elementDescriptors.forEach(::collect)
    }
    types.forEach { collect(serializer(it).descriptor) }
    return out
}

private fun componentKey(serialName: String): String =
    serialName.split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")

private fun refineClass(schema: JsonObject, descriptor: SerialDescriptor, responseOnly: Boolean): JsonObject {
    val properties = schema["properties"]?.jsonObject ?: return schema
    val names = (0 until descriptor.elementsCount).associateBy(descriptor::getElementName)
    val refined = properties.mapValues { (name, property) ->
        val index = names[name] ?: return@mapValues property
        refineProperty(name, property.jsonObject, descriptor.getElementDescriptor(index))
    }
    var out = JsonObject(schema + ("properties" to JsonObject(refined)))
    // An answer always carries every field — defaults and nulls included —
    // so every field of a response-only class is required.
    if (responseOnly) {
        out = JsonObject(out + ("required" to JsonArray(properties.keys.map { JsonPrimitive(it) })))
    }
    return out
}

private fun refineProperty(name: String, property: JsonObject, element: SerialDescriptor): JsonObject {
    var p = property
    // A nullable property whose schema is a bare reference: the component is
    // registered once, without null, so the reference has to allow it here.
    if (element.isNullable && p.keys == setOf("\$ref")) {
        p = buildJsonObject {
            put("anyOf", buildJsonArray {
                add(p)
                add(buildJsonObject { put("type", "null") })
            })
        }
    }
    if ("format" !in p && "anyOf" !in p) {
        val kind = element.kind
        val format = when {
            kind == PrimitiveKind.STRING && (name == "id" || name.endsWith("Id")) -> "uuid"
            kind == PrimitiveKind.STRING && (name.endsWith("At") || name in INSTANT_FIELDS) -> "date-time"
            kind == PrimitiveKind.LONG -> "int64"
            else -> null
        }
        if (format != null) p = JsonObject(p + ("format" to JsonPrimitive(format)))
    }
    return p
}

/** `PublicApiError`: `details` is open, and declares the keys it is known to carry. */
private fun errorSchema(): JsonObject = buildJsonObject {
    put("type", "object")
    put("title", "PublicApiError")
    put("required", buildJsonArray { add(JsonPrimitive("error")) })
    put("properties", buildJsonObject {
        put("error", buildJsonObject { put("type", "string"); put("description", "The error code.") })
        put("details", buildJsonObject {
            put("type", buildJsonArray { add(JsonPrimitive("object")); add(JsonPrimitive("null")) })
            put("description", "What the code alone cannot say. Further keys may be added.")
            put("properties", buildJsonObject {
                put("field", buildJsonObject { put("type", "string"); put("description", "The parameter, path id or body field at fault.") })
                put("max", buildJsonObject { put("type", "integer"); put("description", "The largest count allowed.") })
                put("maxBytes", buildJsonObject { put("type", "integer"); put("format", "int64"); put("description", "The largest size allowed, in bytes.") })
                put("unknown", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { put("type", "string") })
                    put("description", "The values that name nothing.")
                })
                put("errors", buildJsonObject {
                    put("type", "array"); put("items", buildJsonObject { })
                    put("description", "A script's validation errors: `{code, callIndex, field, detail}`.")
                })
                put("reason", buildJsonObject { put("type", "string"); put("description", "A short cause.") })
            })
            put("additionalProperties", buildJsonObject { })
        })
    })
}

private val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options")

private fun refinePathItem(item: JsonObject): JsonObject = JsonObject(item.mapValues { (key, value) ->
    if (key !in HTTP_METHODS || value !is JsonObject) value else refineOperation(value)
})

private fun refineOperation(operation: JsonObject): JsonObject {
    var out = operation
    operation["parameters"]?.let { parameters ->
        out = JsonObject(out + ("parameters" to JsonArray((parameters as JsonArray).map { refineParameter(it.jsonObject) })))
    }
    operation["responses"]?.jsonObject?.let { responses ->
        val withHeaders = responses.mapValues { (status, response) ->
            if (status != "429") response else JsonObject(response.jsonObject + ("headers" to rateLimitHeaders()))
        }
        out = JsonObject(out + ("responses" to JsonObject(withHeaders)))
    }
    return out
}

private fun refineParameter(parameter: JsonObject): JsonObject {
    val name = (parameter["name"] as? JsonPrimitive)?.content ?: return parameter
    val schema = parameter["schema"]?.jsonObject ?: return parameter
    val refined: JsonObject = when {
        name in ID_PARAMETERS -> JsonObject(schema + ("format" to JsonPrimitive("uuid")))
        name == "since" -> JsonObject(schema + ("format" to JsonPrimitive("date-time")))
        name == "window" -> JsonObject(schema + ("enum" to JsonArray(listOf("24h", "7d", "30d", "90d").map { JsonPrimitive(it) })) +
            ("default" to JsonPrimitive("24h")))
        name == "resourceType" -> JsonObject(schema + ("enum" to JsonArray(listOf("workspace", "project", "service").map { JsonPrimitive(it) })))
        name in INTEGER_PARAMETERS -> {
            val (min, max, default) = INTEGER_PARAMETERS.getValue(name)
            JsonObject(schema + ("minimum" to JsonPrimitive(min)) + ("maximum" to JsonPrimitive(max)) + ("default" to JsonPrimitive(default)))
        }
        else -> schema
    }
    return JsonObject(parameter + ("schema" to refined))
}

private fun rateLimitHeaders(): JsonElement = buildJsonObject {
    put("Retry-After", buildJsonObject {
        put("description", "Seconds until the request may be repeated.")
        put("schema", buildJsonObject { put("type", "integer") })
    })
    put("X-RateLimit-Limit", buildJsonObject {
        put("description", "The key's budget for the window (on requests that reached it).")
        put("schema", buildJsonObject { put("type", "integer") })
    })
    put("X-RateLimit-Remaining", buildJsonObject {
        put("description", "What is left of it (on requests that reached it).")
        put("schema", buildJsonObject { put("type", "integer") })
    })
}

/**
 * Serves [publicApiDescription] at [PublicApi.DESCRIPTION_PATH]: outside the
 * key namespace and without a credential, because a client reads it before
 * it has a key working and nothing in the namespace is open. Metered per
 * address, like any unauthenticated read. Left out of the dashboard's own
 * description.
 *
 * Built once, on the first request — the routes do not change while the
 * gateway runs — and served from the encoded text after that.
 */
@OptIn(ExperimentalKtorApi::class)
fun Route.publicApiDescriptionRoute(v1: RoutingNode) {
    val encoded by lazy { publicApiDescription(v1) }
    get(PublicApi.DESCRIPTION_PATH) {
        call.respondText(encoded, ContentType.Application.Json)
    }.hide()
}
