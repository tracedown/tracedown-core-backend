package dev.tracedown.common.realtime

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class ConnectionAuthorizerTest {

    private fun ctx() = ConnectionAuthorizer.ConnectionContext(
        userId = UUID.randomUUID(),
        sessionId = UUID.randomUUID(),
        orgId = UUID.randomUUID(),
    )

    @AfterEach
    fun reset() = ConnectionAuthorizer.clear()

    @Test
    fun `default with no authorizer registered allows`() {
        assertEquals(ConnectionAuthorizer.Decision.Allow, ConnectionAuthorizer.authorize(ctx()))
    }

    @Test
    fun `a registered authorizer's verdict is honoured`() {
        ConnectionAuthorizer.register { ConnectionAuthorizer.Decision.Deny("nope") }
        val decision = ConnectionAuthorizer.authorize(ctx())
        assertTrue(decision is ConnectionAuthorizer.Decision.Deny)
        assertEquals("nope", (decision as ConnectionAuthorizer.Decision.Deny).reason)
    }

    @Test
    fun `a throwing authorizer fails open rather than closing every socket`() {
        ConnectionAuthorizer.register { throw IllegalStateException("boom") }
        assertEquals(ConnectionAuthorizer.Decision.Allow, ConnectionAuthorizer.authorize(ctx()))
    }
}
