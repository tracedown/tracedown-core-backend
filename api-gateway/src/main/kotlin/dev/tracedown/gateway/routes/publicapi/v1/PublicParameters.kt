package dev.tracedown.gateway.routes.publicapi.v1

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.fieldError
import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

// How the key-authenticated API reads its query parameters. Every refusal
// names the parameter (`details.field`): a caller assembling a URL by hand
// should not have to guess which part of it was wrong.

/** An id in the path: malformed is 400 `invalid_uuid` naming the path parameter. */
internal fun ApplicationCall.pathUuid(name: String): UUID {
    val raw = parameters[name]
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw fieldError(name, ErrorCodes.INVALID_UUID)
    } catch (_: NullPointerException) {
        throw fieldError(name, ErrorCodes.INVALID_UUID)
    }
}

/** A path parameter's text. The route only matches when it is there. */
internal fun ApplicationCall.pathText(name: String): String =
    parameters[name] ?: throw fieldError(name, ErrorCodes.FIELD_REQUIRED)

/** A required id in the query: absent is 400 `field_required`, malformed 400 `invalid_uuid`. */
internal fun ApplicationCall.requiredUuidQuery(name: String): UUID {
    val raw = request.queryParameters[name]
    if (raw.isNullOrEmpty()) throw fieldError(name, ErrorCodes.FIELD_REQUIRED)
    return try {
        UUID.fromString(raw)
    } catch (_: IllegalArgumentException) {
        throw fieldError(name, ErrorCodes.INVALID_UUID)
    }
}

/** A required text value in the query: absent is 400 `field_required`. */
internal fun ApplicationCall.requiredQuery(name: String): String {
    val raw = request.queryParameters[name]
    if (raw.isNullOrEmpty()) throw fieldError(name, ErrorCodes.FIELD_REQUIRED)
    return raw
}

/** An integer in the query, [default] when absent; anything else is 400 `field_invalid`. */
internal fun ApplicationCall.intQuery(name: String, default: Int): Int {
    val raw = request.queryParameters[name] ?: return default
    return raw.toIntOrNull() ?: throw fieldError(name)
}

/** An ISO-8601 instant in the query, null when absent; anything else is 400 `field_invalid`. */
internal fun ApplicationCall.instantQuery(name: String): Instant? {
    val raw = request.queryParameters[name] ?: return null
    return try {
        Instant.parse(raw)
    } catch (_: DateTimeParseException) {
        throw fieldError(name)
    }
}

/**
 * A set of values from [allowed] in the query: the parameter repeated, a
 * comma-separated list, or both. Empty when absent; anything outside
 * [allowed], or an empty item, is 400 `field_invalid`.
 */
internal fun ApplicationCall.valuesQuery(name: String, allowed: Set<String>): Set<String> {
    val raw = request.queryParameters.getAll(name) ?: return emptySet()
    val values = raw.flatMap { it.split(',') }.map { it.trim() }
    if (values.any { it !in allowed }) throw fieldError(name)
    return values.toSet()
}

/** One value from [allowed] in the query, null when absent; anything else is 400 `field_invalid`. */
internal fun ApplicationCall.choiceQuery(name: String, allowed: Set<String>): String? {
    val raw = request.queryParameters[name] ?: return null
    if (raw !in allowed) throw fieldError(name)
    return raw
}
