package dev.tracedown.gateway.routes.publicapi

import dev.tracedown.gateway.controllers.events.EventFeedController
import dev.tracedown.gateway.controllers.events.EventTypes
import dev.tracedown.gateway.controllers.alerts.SystemAlertController
import dev.tracedown.gateway.data.publicapi.PublicApiError
import dev.tracedown.gateway.util.EventPollSlots
import dev.tracedown.gateway.util.Idempotency
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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
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
    refused anything but GET and HEAD (403 `api_key_read_only`) — and `POST /scripts/validate`, which changes
    nothing. HEAD is answered on every GET path.

    **Idempotent requests.** Every POST (but `/scripts/validate`) takes an `Idempotency-Key` header: 1–128
    printable ASCII characters of the caller's choosing, unique per request. A repeat with the same key and the same
    request (method, path, query parameters, content type and body) is never made a second time:
    - after a success (2xx), it is answered with the first answer for 24 hours, marked `Idempotent-Replayed: true`
      (status, type and body; headers the first answer carried are not kept);
    - after a refusal or failure (4xx, 5xx), nothing was remembered — it runs again;
    - while the first is still being answered, 409 `idempotency_in_progress` with `Retry-After`;
    - when the first was cut off while it ran (its client went away), or succeeded with an answer over 256 KiB (that
      answer carried `Idempotency-Status: not-kept`), 409 `idempotency_outcome_unknown` for 24 hours: it may have
      taken effect, so it is not made again — check, then use a new key.
    A request still marked as being answered after 5 minutes is answered 409 `idempotency_outcome_unknown` too. The
    same key with a different request is 422 `idempotency_key_reused`. What is remembered — answers and unknown
    outcomes, at their stored size — counts against a budget per organization (64 MiB unless the operator set
    another), in a window fixed at 24 hours from its first use; once it is spent, a request with a new key is refused
    before it runs, 429 `idempotency_limit_reached` with `Retry-After` (when the window ends). A replay checks the
    caller is still a member of the organization, not the route's own permission again. When the store that remembers keys
    does not answer, a request carrying one is refused, 503 `idempotency_unavailable` with `Retry-After`.

    **Unknown values.** New values can appear in `state`, `status`, `trigger` and `reason` fields; treat one you do
    not know as you would the nearest one you do, never as an error.

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
    `Retry-After` in seconds. The event feed (`/events`) is charged per look instead of per request: the first look
    of a read that waits is free, and every later look, every answer given without waiting and every 410 costs one
    request; a key whose budget is spent is refused on arrival. Its answers carry no `X-RateLimit-*` headers. An address that keeps sending tokens that name no key (or no token) is refused 429
    `too_many_unknown_keys`, also with `Retry-After`.

    **Lists.** Paged lists answer `{items, total, page, pageSize}` and page with `page` (from 1) and `pageSize` (at
    most 100); there is no filtering or sorting beyond the named query parameters an operation lists. `/agents`,
    `/services/{id}/agents`, `/access/…` and the metrics histories answer bare arrays. Every order is fixed:
    oldest first (by creation, then id) for workspaces, projects, services, variables, webhooks and bindings; by
    name, then id, for members and groups; by id for silences; most recent first for results (or oldest first, with
    `order=asc`); by slug for agents; groups then users, each by name then id, for access.
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
    HttpStatusCode.UnprocessableEntity to "`idempotency_key_reused` — the `Idempotency-Key` was used with a different request.",
    HttpStatusCode.Gone to "`body_gone` — the step recorded a body that is no longer there; `cursor_expired` — the " +
        "event cursor is older than the events kept (`details.oldest` is where to start again).",
    HttpStatusCode.PayloadTooLarge to "`request_body_too_large` (the request), or `body_too_large` (a stored body; " +
        "`details.maxBytes`).",
    HttpStatusCode.TooManyRequests to "`rate_limited` (the key's budget), `too_many_unknown_keys` (the address) or " +
        "`too_many_event_polls` (event reads open). " +
        "`Retry-After` says when to come back.",
    HttpStatusCode.ServiceUnavailable to "`body_store_unavailable` — retry with backoff after `Retry-After`.",
)

/** What an operation taking an `Idempotency-Key` adds to the shared texts. */
private val IDEMPOTENCY_CODES: Map<HttpStatusCode, String> = mapOf(
    HttpStatusCode.BadRequest to " A malformed `Idempotency-Key` is `field_invalid` with `details.field` `Idempotency-Key`.",
    HttpStatusCode.Conflict to " `idempotency_in_progress` (the request with this `Idempotency-Key` is still being answered; " +
        "`Retry-After`), `idempotency_outcome_unknown` (it may have taken effect; use a new key).",
    HttpStatusCode.TooManyRequests to " `idempotency_limit_reached` — the organization's budget for remembered answers is " +
        "spent; `Retry-After` says when it comes back.",
    HttpStatusCode.ServiceUnavailable to " `idempotency_unavailable` — retry after `Retry-After`.",
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
    if (operation.query.isNotEmpty() || operation.idempotent) {
        parameters {
            for (parameter in operation.query) {
                query(parameter.name) {
                    description = parameter.description
                    required = parameter.required
                    schema = buildSchema(parameter.type)
                }
            }
            if (operation.idempotent) {
                header(Idempotency.HEADER) {
                    description = "Makes the request safe to repeat: 1–128 printable ASCII characters, unique per " +
                        "request. A repeat within 24 hours is answered as the first was, with `Idempotent-Replayed: true`."
                    required = false
                    schema = buildSchema(typeOf<String>())
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
                description = (STATUS_CODES[status] ?: status.description) +
                    (if (operation.idempotent) IDEMPOTENCY_CODES[status].orEmpty() else "")
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
    "id", "varId", "resultId", "stepId", "resourceId", "workspaceId", "projectId", "runId", "templateId",
)

/** Bounds and defaults of the integer query parameters: name to (minimum, maximum, default). */
private val INTEGER_PARAMETERS = mapOf(
    "page" to Triple(1, Int.MAX_VALUE, 1),
    "pageSize" to Triple(1, 100, 50),
    "hours" to Triple(1, 168, 24),
    "days" to Triple(1, 365, 90),
    "wait" to Triple(0, EventFeedController.MAX_WAIT_SECONDS, 0),
    "limit" to Triple(1, EventFeedController.MAX_LIMIT, EventFeedController.MAX_LIMIT),
)

/**
 * The closed value sets of string fields, by component and property — kept
 * here, beside their sources, rather than as annotations that could drift
 * from them.
 */
private val FIELD_ENUMS: Map<String, Map<String, List<String>>> = mapOf(
    "FeedEvent" to mapOf("type" to EventTypes.ALL),
    "EventResource" to mapOf("type" to EventTypes.RESOURCE.values.distinct()),
    "PublicSystemAlert" to mapOf("severity" to listOf("warning", "error")),
    "RulePresetSummary" to mapOf("scope" to listOf("org", "workspace")),
)

/** The query parameters held to a set of values, and the values: the operations' own. */
private val PARAMETER_VALUES: Map<String, List<String>> = PublicApiOperations.all
    .flatMap { it.query }.mapNotNull { q -> q.values?.let { q.name to it } }.toMap()

/** The defaults of the query parameters held to a set of values. */
private val PARAMETER_DEFAULTS = mapOf("order" to "desc", "state" to SystemAlertController.ACTIVE)

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
            else -> descriptors[key]?.let {
                withEnums(refineClass(schema.jsonObject, it, responseOnly = key !in requestClasses), FIELD_ENUMS[key])
            } ?: schema
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
    // By descriptor, not by name: every list is called the same, and keying
    // on the name stopped the walk at the second list it met — a class
    // reached only through that list was never refined.
    val seen = mutableSetOf<SerialDescriptor>()
    fun collect(descriptor: SerialDescriptor) {
        val name = descriptor.serialName.removeSuffix("?")
        if (!seen.add(descriptor)) return
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

/** [schema] with the closed value sets [enums] declared on its properties. */
private fun withEnums(schema: JsonObject, enums: Map<String, List<String>>?): JsonObject {
    if (enums == null) return schema
    val properties = schema["properties"]?.jsonObject ?: return schema
    val refined = properties.mapValues { (name, property) ->
        val values = enums[name] ?: return@mapValues property
        JsonObject(property.jsonObject + ("enum" to JsonArray(values.map { JsonPrimitive(it) })))
    }
    return JsonObject(schema + ("properties" to JsonObject(refined)))
}

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
    // A nullable property held to a set of values may also be null: the set says so.
    if (element.isNullable && p["enum"] is JsonArray && JsonNull !in p["enum"]!!.jsonArray) {
        p = JsonObject(p + ("enum" to JsonArray(p["enum"]!!.jsonArray + JsonNull)))
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
                put("oldest", buildJsonObject {
                    put("type", "string")
                    put("description", "On `cursor_expired`: the event cursor to start again from.")
                })
                put("bound", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(EventPollSlots.Bound.entries.map { JsonPrimitive(it.wire) }))
                    put("description", "On `too_many_event_polls`: which bound is reached.")
                })
            })
            put("additionalProperties", buildJsonObject { })
        })
    })
}

private val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options")

private fun refinePathItem(item: JsonObject): JsonObject = JsonObject(item.mapValues { (key, value) ->
    if (key !in HTTP_METHODS || value !is JsonObject) value else refineOperation(value)
})

/** The operation ids that answer bytes rather than JSON. */
private val BINARY_OPERATIONS = PublicApiOperations.all.filter { it.binary }.map { it.operationId }.toSet()

/** Every operation that takes an `Idempotency-Key`, and so may answer with `Idempotent-Replayed`. */
private val IDEMPOTENT_OPERATIONS = PublicApiOperations.all.filter { it.idempotent }.map { it.operationId }.toSet()

private fun refineOperation(operation: JsonObject): JsonObject {
    var out = operation
    val id = (operation["operationId"] as? JsonPrimitive)?.content
    if (id in BINARY_OPERATIONS) out = binaryAnswer(out)
    if (id in IDEMPOTENT_OPERATIONS) out = replayHeader(out)
    out = retryAfter(out, setOf("503"))
    operation["parameters"]?.let { parameters ->
        out = JsonObject(out + ("parameters" to JsonArray((parameters as JsonArray).map { refineParameter(it.jsonObject) })))
    }
    out["responses"]?.jsonObject?.let { responses ->
        val withHeaders = responses.mapValues { (status, response) ->
            if (status != "429") response else JsonObject(response.jsonObject + ("headers" to rateLimitHeaders()))
        }
        out = JsonObject(out + ("responses" to JsonObject(withHeaders)))
    }
    return out
}

/** A download: its success answer is the stored bytes, with the header that makes it one. */
private fun binaryAnswer(operation: JsonObject): JsonObject {
    val responses = operation["responses"]?.jsonObject ?: return operation
    val ok = responses["200"]?.jsonObject ?: return operation
    val refined = JsonObject(ok - "content" + mapOf(
        "description" to JsonPrimitive(
            "The stored bytes, under the type the store recorded when the gateway repeats it (an image, JSON, text…), " +
                "`application/octet-stream` otherwise.",
        ),
        "content" to buildJsonObject {
            put("application/octet-stream", buildJsonObject {
                put("schema", buildJsonObject { put("type", "string"); put("format", "binary") })
            })
        },
        "headers" to buildJsonObject {
            put("Content-Disposition", buildJsonObject {
                put("description", "`attachment`, with a file name.")
                put("schema", buildJsonObject { put("type", "string") })
            })
            put("X-Content-Type-Options", buildJsonObject {
                put("description", "`nosniff`.")
                put("schema", buildJsonObject { put("type", "string") })
            })
            put("Content-Length", buildJsonObject {
                put("description", "The body's size in bytes.")
                put("schema", buildJsonObject { put("type", "integer"); put("format", "int64") })
            })
            put("Cache-Control", buildJsonObject {
                put("description", "`private, no-store`: a body can carry whatever the probed endpoint answered.")
                put("schema", buildJsonObject { put("type", "string") })
            })
        },
    ))
    return JsonObject(operation + ("responses" to JsonObject(responses + ("200" to refined))))
}

/** Declares `Idempotent-Replayed` on an idempotent operation's success answer. */
private fun replayHeader(operation: JsonObject): JsonObject {
    val responses = operation["responses"]?.jsonObject ?: return operation
    val successes = responses.filterKeys { it.startsWith("2") }.mapValues { (_, response) ->
        val r = response.jsonObject
        val headers = r["headers"]?.jsonObject ?: JsonObject(emptyMap())
        JsonObject(r + ("headers" to JsonObject(headers + ("Idempotent-Replayed" to buildJsonObject {
            put("description", "`true` when this is the remembered answer of an earlier request with the same `Idempotency-Key`.")
            put("schema", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("true")) }) })
        }) + (Idempotency.STATUS_HEADER to buildJsonObject {
            put("description", "`not-kept` when the answer was too large to remember: a repeat answers 409 `idempotency_outcome_unknown`.")
            put("schema", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add(JsonPrimitive("not-kept")) }) })
        }))))
    }
    return retryAfter(JsonObject(operation + ("responses" to JsonObject(responses + successes))), setOf("409"))
}

/** Declares `Retry-After` on the given error answers of an operation. */
private fun retryAfter(operation: JsonObject, statuses: Set<String>): JsonObject {
    val responses = operation["responses"]?.jsonObject ?: return operation
    val retryable = responses.filterKeys { it in statuses }.mapValues { (_, response) ->
        val r = response.jsonObject
        val headers = r["headers"]?.jsonObject ?: JsonObject(emptyMap())
        JsonObject(r + ("headers" to JsonObject(headers + ("Retry-After" to buildJsonObject {
            put("description", "Seconds until the request is worth repeating, where the code says so.")
            put("schema", buildJsonObject { put("type", "integer") })
        }))))
    }
    return JsonObject(operation + ("responses" to JsonObject(responses + retryable)))
}

private fun refineParameter(parameter: JsonObject): JsonObject {
    val name = (parameter["name"] as? JsonPrimitive)?.content ?: return parameter
    val schema = parameter["schema"]?.jsonObject ?: return parameter
    val refined: JsonObject = when {
        name in ID_PARAMETERS -> JsonObject(schema + ("format" to JsonPrimitive("uuid")))
        name == "since" || name == "until" -> JsonObject(schema + ("format" to JsonPrimitive("date-time")))
        // Held to a set: the values the handler checks against.
        name in PARAMETER_VALUES && schema["type"]?.let { (it as? JsonPrimitive)?.content } == "array" ->
            JsonObject(schema + ("items" to buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(PARAMETER_VALUES.getValue(name).map { JsonPrimitive(it) }))
            }))
        name in PARAMETER_VALUES -> JsonObject(schema + ("enum" to JsonArray(PARAMETER_VALUES.getValue(name).map { JsonPrimitive(it) })) +
            (PARAMETER_DEFAULTS[name]?.let { mapOf("default" to JsonPrimitive(it)) } ?: emptyMap()))
        name == "window" -> JsonObject(schema + ("enum" to JsonArray(listOf("24h", "7d", "30d", "90d").map { JsonPrimitive(it) })) +
            ("default" to JsonPrimitive("24h")))
        name == "resourceType" -> JsonObject(schema + ("enum" to JsonArray(listOf("workspace", "project", "service").map { JsonPrimitive(it) })))
        name in INTEGER_PARAMETERS -> {
            val (min, max, default) = INTEGER_PARAMETERS.getValue(name)
            JsonObject(schema + ("minimum" to JsonPrimitive(min)) + ("maximum" to JsonPrimitive(max)) + ("default" to JsonPrimitive(default)))
        }
        else -> schema
    }
    // `status` is repeated or comma-separated: explode=true takes the first,
    // and the comma form is described in the parameter's text.
    val style = if (name == "status" || name == "types") mapOf("style" to JsonPrimitive("form"), "explode" to JsonPrimitive(true)) else emptyMap()
    return JsonObject(parameter + ("schema" to refined) + style)
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
