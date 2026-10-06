package dev.tracedown.gateway.util

import io.ktor.utils.io.ConnectionClosedException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A client hanging up is kept out of the unhandled-error path; a cancellation
 * of our own making is not.
 */
class ClientDisconnectTest {

    @Test
    fun `the lifecycle plugin's cancellation on close is a disconnect`() {
        // The shape the plugin throws: a cancellation caused by the closed connection.
        val cancelled = CancellationException("Call context was cancelled by `HttpRequestLifecycle` plugin")
            .apply { initCause(ConnectionClosedException("Connection was closed")) }
        assertTrue(isClientDisconnect(cancelled))
    }

    @Test
    fun `a closed connection met while reading a body is a disconnect`() {
        assertTrue(isClientDisconnect(ConnectionClosedException()))
        assertTrue(isClientDisconnect(IllegalStateException("read failed", ConnectionClosedException())))
    }

    @Test
    fun `a handler's own timeout is not a disconnect`() {
        val timeout = runCatching { runBlocking { withTimeout(1) { delay(1_000) } } }.exceptionOrNull()
        assertTrue(timeout is TimeoutCancellationException, "expected a timeout, got $timeout")
        assertFalse(isClientDisconnect(timeout))
    }

    @Test
    fun `a bare cancellation and an ordinary failure are not disconnects`() {
        assertFalse(isClientDisconnect(CancellationException("cancelled")))
        assertFalse(isClientDisconnect(IllegalStateException("boom")))
    }
}
