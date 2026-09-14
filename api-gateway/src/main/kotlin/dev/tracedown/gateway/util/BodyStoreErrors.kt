package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.storage.BodyStoreException
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.v1.exceptions.ExposedSQLException
import java.sql.SQLException

/**
 * Maps a [BodyStoreException] from `BodyStoreService` onto the API's error shape:
 * 400 / 404 / 409 with the code, the offending request field as `details.field`
 * and, when the field is present but wrong rather than missing, why as
 * `details.reason`.
 */
fun BodyStoreException.toApiException(): ApiException {
    val status = when (kind) {
        BodyStoreException.Kind.INVALID -> HttpStatusCode.BadRequest
        BodyStoreException.Kind.NOT_FOUND -> HttpStatusCode.NotFound
        BodyStoreException.Kind.CONFLICT -> HttpStatusCode.Conflict
    }
    val extra = buildMap {
        field?.let { put("field", JsonPrimitive(it)) }
        reason?.let { put("reason", JsonPrimitive(it)) }
    }
    val merged = if (extra.isEmpty()) details else JsonObject((details ?: JsonObject(emptyMap())) + extra)
    return ApiException(status, code, details = merged)
}

/**
 * Runs [block], turning a refused body-store operation into its API error.
 *
 * A store is read, checked and written across several statements, so two callers
 * can still collide below the checks: one deletes the store another is assigning,
 * or two create the same name at once. Postgres settles those on the constraints
 * — a foreign key violation (23503) or a unique violation (23505) — and both mean
 * the same thing to the caller as the check they slipped past: someone else got
 * there first, try again. A 500 would say the server broke instead.
 */
inline fun <T> bodyStoreCall(block: () -> T): T = try {
    block()
} catch (e: BodyStoreException) {
    throw e.toApiException()
} catch (e: ExposedSQLException) {
    throw constraintConflict(e) ?: e
}

/** The 409 a constraint violation on a body store means, or null when it is not one. */
@PublishedApi
internal fun constraintConflict(e: ExposedSQLException): ApiException? {
    var cause: Throwable? = e
    while (cause != null) {
        val state = (cause as? SQLException)?.sqlState
        when (state) {
            "23503" -> return ApiException(HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_NOT_FOUND)
            "23505" -> return ApiException(HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_NAME_TAKEN, details = null)
        }
        cause = cause.cause?.takeIf { it !== cause }
    }
    return null
}
