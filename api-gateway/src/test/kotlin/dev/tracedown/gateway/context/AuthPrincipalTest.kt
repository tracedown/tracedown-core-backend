package dev.tracedown.gateway.context

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.UnauthorizedException
import io.ktor.http.HttpStatusCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class AuthPrincipalTest {

    @Test
    fun `a session principal has its session`() {
        val sessionId = UUID.randomUUID()
        val principal = AuthPrincipal(UUID.randomUUID(), sessionId, "a@tracedown.dev", null)
        assertEquals(sessionId, principal.sessionId)
        assertEquals(Credential.Session(sessionId), principal.credential)
    }

    @Test
    fun `a key principal has no session to give, and says so the way the namespace does`() {
        val principal = AuthPrincipal(
            userId = UUID.randomUUID(),
            email = "a@tracedown.dev",
            organizationId = UUID.randomUUID(),
            credential = Credential.ApiKey(UUID.randomUUID(), AccessLevel.WRITE),
        )
        val refusal = assertThrows<UnauthorizedException> { principal.sessionId }
        assertEquals(ErrorCodes.SESSION_REQUIRED, refusal.code)
        assertEquals(HttpStatusCode.Unauthorized, refusal.status)
    }
}
