package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

open class ApiException(
    val status: HttpStatusCode,
    val code: String,
    override val message: String = code,
    /**
     * Machine-readable context for codes that cannot say enough on their own —
     * serialized alongside the code as `details`. Never user-facing text: the
     * client still maps [code] to its own copy and uses this to fill the blanks
     * (which resources are in the way, and so on).
     */
    val details: JsonObject? = null,
) : RuntimeException(message)

class UnauthorizedException(code: String = ErrorCodes.INVALID_TOKEN) :
    ApiException(HttpStatusCode.Unauthorized, code)

class ForbiddenException(code: String = ErrorCodes.FORBIDDEN) :
    ApiException(HttpStatusCode.Forbidden, code)

class NotFoundException(code: String = ErrorCodes.NOT_FOUND) :
    ApiException(HttpStatusCode.NotFound, code)

/** The resource existed but is permanently unavailable (410). */
class GoneException(code: String) :
    ApiException(HttpStatusCode.Gone, code)

class BadRequestException(code: String = ErrorCodes.FIELD_INVALID) :
    ApiException(HttpStatusCode.BadRequest, code)

class ConflictException(code: String = ErrorCodes.ALREADY_EXISTS, details: JsonObject? = null) :
    ApiException(HttpStatusCode.Conflict, code, details = details)

class TooManyRequestsException(code: String = ErrorCodes.RATE_LIMITED) :
    ApiException(HttpStatusCode.TooManyRequests, code)

/**
 * A 400 about one request field, naming it: `{"error": code, "details":
 * {"field": field, …}}` — so a caller knows which parameter to fix without
 * guessing from the code alone. [extra] adds what else the caller needs (the
 * largest value allowed, say).
 */
fun fieldError(
    field: String,
    code: String = ErrorCodes.FIELD_INVALID,
    extra: JsonObjectBuilder.() -> Unit = {},
): ApiException = ApiException(
    HttpStatusCode.BadRequest, code,
    details = buildJsonObject {
        put("field", JsonPrimitive(field))
        extra()
    },
)
