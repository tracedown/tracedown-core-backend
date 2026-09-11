package dev.tracedown.gateway.util

import dev.tracedown.common.storage.BodyStoreException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps a [BodyStoreException] from `BodyStoreService` onto the API's error shape:
 * 400 / 404 / 409 with the code, and the offending field as `details.field`.
 */
fun BodyStoreException.toApiException(): ApiException {
    val status = when (kind) {
        BodyStoreException.Kind.INVALID -> HttpStatusCode.BadRequest
        BodyStoreException.Kind.NOT_FOUND -> HttpStatusCode.NotFound
        BodyStoreException.Kind.CONFLICT -> HttpStatusCode.Conflict
    }
    val merged = if (field == null) details else JsonObject((details ?: JsonObject(emptyMap())) + ("field" to JsonPrimitive(field)))
    return ApiException(status, code, details = merged)
}

/** Runs [block], turning a refused body-store operation into its API error. */
inline fun <T> bodyStoreCall(block: () -> T): T = try {
    block()
} catch (e: BodyStoreException) {
    throw e.toApiException()
}
