package dev.tracedown.gateway

import dev.tracedown.common.validation.Validatable
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.gateway.data.auth.LoginRequest
import dev.tracedown.gateway.data.auth.SwitchOrgRequest
import dev.tracedown.gateway.data.bulk.BulkRequest
import dev.tracedown.gateway.data.bulk.BulkSubRequest
import dev.tracedown.gateway.data.domains.CreateDomainRequest
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.requestvalidation.RequestValidation
import io.ktor.server.plugins.requestvalidation.RequestValidationException
import io.ktor.server.plugins.requestvalidation.ValidationResult
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The self-validating request DTOs and the plugin that runs them.
 *
 * Two levels:
 *  - [validate] on each DTO returns the right error codes (or none) — the per-DTO
 *    rules that the sweep added, checked directly without HTTP or a database.
 *  - the `RequestValidation` + `StatusPages` wiring turns a failing body into a
 *    `400 {"error":"<code>"}` before the handler runs, and lets a valid body through.
 *
 * The wiring block below mirrors api-gateway's `Application.module()` install; a
 * DB-backed integration test would exercise the identical plugin over real routes.
 */
class RequestValidationTest {

    // ── per-DTO rules ────────────────────────────────────────────────────────

    @Test
    fun `LoginRequest accepts a well-formed body`() {
        assertTrue(LoginRequest("user@example.com", "password123").validate().isEmpty())
    }

    @Test
    fun `LoginRequest flags a malformed email and a blank password`() {
        assertEquals(listOf("invalid_email"), LoginRequest("not-an-email", "pw").validate())
        assertEquals(listOf("password_required"), LoginRequest("user@example.com", "").validate())
    }

    @Test
    fun `LoginRequest rejects an over-long email before the format check`() {
        val huge = "a".repeat(300) + "@example.com"
        // maxLen is composed before email(), so the length code is the first (reported) one.
        assertEquals("email_too_long", LoginRequest(huge, "password123").validate().first())
    }

    @Test
    fun `SwitchOrgRequest requires a real UUID`() {
        assertTrue(SwitchOrgRequest("3f6b0e2a-9c1d-4b7e-8a2f-1c2d3e4f5a6b").validate().isEmpty())
        assertEquals(listOf("invalid_orgId"), SwitchOrgRequest("42").validate())
        // Blank trips notBlank *and* uuid (an empty string is non-null); the plugin
        // reports the first code, which is the meaningful one.
        assertEquals("orgId_required", SwitchOrgRequest("").validate().first())
    }

    @Test
    fun `CreateApiKeyRequest caps the name at the column width and bounds the expiry`() {
        assertTrue(CreateApiKeyRequest("ci key", 30, password = "pw").validate().isEmpty())
        assertTrue(CreateApiKeyRequest("ci key", null, password = "pw").validate().isEmpty(), "null expiry is allowed")
        assertEquals(listOf("name_too_long"), CreateApiKeyRequest("n".repeat(129), 30, password = "pw").validate())
        assertEquals(listOf("invalid_expiresInDays"), CreateApiKeyRequest("ci key", 0, password = "pw").validate())
        assertEquals(listOf("invalid_expiresInDays"), CreateApiKeyRequest("ci key", 3651, password = "pw").validate())
    }

    @Test
    fun `CreateApiKeyRequest knows two access levels and asks for the password`() {
        assertTrue(CreateApiKeyRequest("k", access = "read", password = "pw").validate().isEmpty())
        assertTrue(CreateApiKeyRequest("k", access = "write", password = "pw").validate().isEmpty())
        assertEquals(listOf("invalid_access"), CreateApiKeyRequest("k", access = "admin", password = "pw").validate())
        // Minting a credential re-proves identity: a request without a password is not one.
        assertEquals(listOf("password_required"), CreateApiKeyRequest("k", password = "").validate())
    }

    @Test
    fun `CreateDomainRequest restricts the verification type to the known challenges`() {
        assertTrue(CreateDomainRequest("example.com", "http-01").validate().isEmpty())
        assertTrue(CreateDomainRequest("example.com", "dns-01").validate().isEmpty())
        assertEquals(listOf("invalid_verificationType"), CreateDomainRequest("example.com", "tls-01").validate())
    }

    @Test
    fun `CreateDomainRequest validates each exception entry`() {
        assertTrue(CreateDomainRequest("example.com", exceptions = listOf("a.example.com")).validate().isEmpty())
        assertEquals(
            listOf("exceptions_too_long"),
            CreateDomainRequest("example.com", exceptions = listOf("x".repeat(257))).validate(),
        )
    }

    @Test
    fun `BulkRequest cascades validation into every sub-request`() {
        val ok = BulkRequest(listOf(BulkSubRequest("GET", "/v1/services")))
        assertTrue(ok.validate().isEmpty())

        // The plugin only sees the top-level body, so a blank nested method would go
        // unchecked without the cascade — assert the cascade surfaces it.
        val bad = BulkRequest(listOf(BulkSubRequest("GET", "/ok"), BulkSubRequest("", "/v1/x")))
        assertEquals(listOf("method_required"), bad.validate())
    }

    // ── plugin wiring: bad body -> 400 {"error":<code>}, valid -> handler runs ──

    @Test
    fun `the plugin rejects an invalid body with 400 and the first code`() = testApplication {
        application { installValidationHarness() }

        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"nope","password":"pw"}""")
        }

        assertEquals(HttpStatusCode.BadRequest, res.status)
        assertTrue(res.bodyAsText().contains("\"invalid_email\""), "body carries the first error code")
    }

    @Test
    fun `the plugin lets a valid body reach the handler`() = testApplication {
        application { installValidationHarness() }

        val res = client.post("/login") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"user@example.com","password":"password123"}""")
        }

        assertEquals(HttpStatusCode.OK, res.status)
        assertTrue(res.bodyAsText().contains("handled"), "the handler ran")
    }
}

/**
 * Mirrors the `RequestValidation` + `StatusPages` install from api-gateway's
 * `Application.module()`, over a single `/login` route, so the mechanism can be
 * exercised without a database.
 */
private fun io.ktor.server.application.Application.installValidationHarness() {
    install(ContentNegotiation) { json() }
    install(RequestValidation) {
        validate<Validatable> { body ->
            val errors = body.validate()
            if (errors.isEmpty()) ValidationResult.Valid else ValidationResult.Invalid(errors)
        }
    }
    install(StatusPages) {
        exception<RequestValidationException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                mapOf("error" to (cause.reasons.firstOrNull() ?: "invalid_request")),
            )
        }
    }
    routing {
        post("/login") {
            call.receive<LoginRequest>()
            call.respond("handled")
        }
    }
}
