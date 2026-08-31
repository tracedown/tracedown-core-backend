package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.request.contentLength
import io.ktor.server.response.respond
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * Caps the size of a request body.
 *
 * Every other limit in the API is a *field* limit — a script may be 64 KB, a
 * name 255 characters — and those run inside `RequestValidation`, which is to
 * say after the body has been received in full and deserialized into objects.
 * They bound what gets stored; they bound nothing about what can be sent.
 * Neither Ktor nor Netty imposes a default, so without this the only thing
 * standing between the API and an arbitrarily large body is whatever reverse
 * proxy happens to be in front of it — and there may not be one.
 *
 * Two layers, because either alone leaves a hole:
 *
 * - `Content-Length`, checked before anything reads the body, which is what
 *   rejects the ordinary oversized POST without buffering a byte of it.
 * - a cap on the bytes actually read, which is what covers a chunked request
 *   that declares no length at all. It buffers at most the limit plus one byte
 *   and refuses past that, so a client cannot stream indefinitely by omitting
 *   the header.
 *
 * Install this **before** `ContentNegotiation`: both transform the received
 * body, the first one to run wins, and this has to see the raw channel.
 */
fun Application.installRequestBodyLimit(maxBytes: Long) {
    install(
        createApplicationPlugin("RequestBodyLimit") {
            onCall { call ->
                val declared = call.request.contentLength()
                if (declared != null && declared > maxBytes) {
                    // Refused on the declaration alone: nothing has read the
                    // body, and nothing will.
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to ErrorCodes.REQUEST_BODY_TOO_LARGE),
                    )
                }
            }
            onCallReceive { _ ->
                transformBody { body: ByteReadChannel ->
                    // One byte past the limit is enough to know it was exceeded,
                    // and is the most this ever holds.
                    val bytes = body.readRemaining(maxBytes + 1).readByteArray()
                    if (bytes.size > maxBytes) throw PayloadTooLargeException(maxBytes)
                    ByteReadChannel(bytes)
                }
            }
        },
    )
}
