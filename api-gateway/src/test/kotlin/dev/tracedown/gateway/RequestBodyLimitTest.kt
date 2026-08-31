package dev.tracedown.gateway

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.installRequestBodyLimit
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.PayloadTooLargeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Nothing capped the size of a request body. Every other limit in the API is a
 * *field* limit — a script may be 64 KB — and those run inside
 * `RequestValidation`, i.e. after the body has been received in full and turned
 * into objects. Neither Ktor nor Netty imposes a default, so on a deployment
 * with no reverse proxy in front there was no ceiling at all.
 *
 * The wiring below mirrors `Application.module()`: the limit is installed before
 * `ContentNegotiation` (both transform the received body; the first to run wins)
 * and `StatusPages` maps the overflow to 413.
 */
class RequestBodyLimitTest {

    @Serializable
    private data class Payload(val script: String)

    private val limit = 1024L

    /** True while the handler ran — i.e. the body was deserialized. */
    private val deserialized = AtomicBoolean(false)

    private fun body(size: Int): String =
        """{"script":"${"x".repeat(size)}"}"""

    @Test
    fun `a body within the limit is accepted`() = testApplication {
        application {
            installRequestBodyLimit(limit)
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<PayloadTooLargeException> { call, _ ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to ErrorCodes.REQUEST_BODY_TOO_LARGE),
                    )
                }
            }
            routing {
                post("/x") {
                    val payload = call.receive<Payload>()
                    deserialized.set(true)
                    call.respond(mapOf("length" to payload.script.length.toString()))
                }
            }
        }
        val response = client.post("/x") {
            contentType(ContentType.Application.Json)
            setBody(body(100))
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(deserialized.get())
    }

    @Test
    fun `an oversized body is refused before it is deserialized`() = testApplication {
        application {
            installRequestBodyLimit(limit)
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<PayloadTooLargeException> { call, _ ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to ErrorCodes.REQUEST_BODY_TOO_LARGE),
                    )
                }
            }
            routing {
                post("/x") {
                    call.receive<Payload>()
                    deserialized.set(true)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        val response = client.post("/x") {
            contentType(ContentType.Application.Json)
            setBody(body(limit.toInt() * 4))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertTrue(response.bodyAsText().contains(ErrorCodes.REQUEST_BODY_TOO_LARGE))
        // The point of the finding: the handler never ran, so nothing was
        // deserialized and no per-field validation had to be reached.
        assertFalse(deserialized.get(), "the body reached the handler despite exceeding the limit")
    }

    @Test
    fun `a body that declares no length cannot stream past the limit either`() = testApplication {
        application {
            installRequestBodyLimit(limit)
            install(ContentNegotiation) { json() }
            install(StatusPages) {
                exception<PayloadTooLargeException> { call, _ ->
                    call.respond(
                        HttpStatusCode.PayloadTooLarge,
                        mapOf("error" to ErrorCodes.REQUEST_BODY_TOO_LARGE),
                    )
                }
            }
            routing {
                post("/x") {
                    call.receive<Payload>()
                    deserialized.set(true)
                    call.respond(HttpStatusCode.OK)
                }
            }
        }
        val channel = ByteChannel(autoFlush = true)
        CoroutineScope(Dispatchers.Default).launch {
            channel.writeStringUtf8("""{"script":"""" + "x".repeat(limit.toInt() * 4) + """"}""")
            channel.close()
        }
        // A channel body carries no Content-Length — the engine chunks it — so
        // only the read cap can stop this one.
        val response = client.post("/x") {
            contentType(ContentType.Application.Json)
            setBody(channel)
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, response.status)
        assertFalse(deserialized.get())
    }
}
