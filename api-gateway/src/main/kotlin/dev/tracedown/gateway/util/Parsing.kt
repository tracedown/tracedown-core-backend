package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.request.receive
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Parses a UUID string, throwing [BadRequestException] with a descriptive message on failure. */
fun parseUuid(value: String?, label: String): UUID {
    return try {
        UUID.fromString(value)
    } catch (e: Exception) {
        throw BadRequestException(ErrorCodes.INVALID_UUID)
    }
}

/**
 * Receives a typed request body; any failure is 400 `invalid_request_body`.
 * When the body parsed but failed its own validation, the field the first
 * failure names goes in `details.field` (and the validation's code in
 * `details.reason`); a body that is not JSON at all, or not sent as JSON,
 * names nothing.
 */
suspend inline fun <reified T : Any> tryReceive(call: ApplicationCall): T {
    return try {
        call.receive<T>()
    } catch (e: RequestValidationException) {
        throw invalidBody(e.reasons.firstOrNull())
    } catch (e: Exception) {
        throw BadRequestException(ErrorCodes.INVALID_REQUEST_BODY)
    }
}

/** The validation codes `Validators` writes, each naming its field: `name_too_long`, `invalid_email`… */
private val FIELD_CODE = Regex("^(?:invalid_([A-Za-z][A-Za-z0-9]*)|([A-Za-z][A-Za-z0-9]*)_(?:required|too_long|too_short))$")

/** 400 `invalid_request_body`, naming the field [reason] is about when it names one. */
@PublishedApi
internal fun invalidBody(reason: String?): ApiException {
    val match = reason?.let { FIELD_CODE.matchEntire(it) }
    val field = match?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        ?: return BadRequestException(ErrorCodes.INVALID_REQUEST_BODY)
    return ApiException(
        HttpStatusCode.BadRequest, ErrorCodes.INVALID_REQUEST_BODY,
        details = buildJsonObject {
            put("field", field)
            put("reason", reason)
        },
    )
}
