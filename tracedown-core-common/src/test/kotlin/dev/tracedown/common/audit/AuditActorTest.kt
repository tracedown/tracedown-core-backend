package dev.tracedown.common.audit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.Executors

/**
 * The key behind an audited action has to stay with its own request. Request
 * threads are shared, so the two ways to get this wrong are leaving the value
 * on a thread for the next request to find, and losing it when the request
 * resumes somewhere else.
 */
class AuditActorTest {

    @Test
    fun `the key stays with its coroutine and is never seen by another on the same thread`() = runBlocking {
        val oneThread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val key = UUID.randomUUID()
        try {
            withContext(oneThread) {
                val attributed = launch {
                    withContext(AuditActor.asContextElement(key)) {
                        yield()
                        delay(40)
                        assertEquals(key, AuditActor.currentApiKeyId())
                    }
                }
                // Runs on the very same thread while the first is suspended.
                val other = launch {
                    delay(10)
                    assertNull(AuditActor.currentApiKeyId())
                }
                joinAll(attributed, other)
                assertNull(AuditActor.currentApiKeyId(), "Nothing may be left on the thread")
            }
        } finally {
            oneThread.close()
        }
    }

    @Test
    fun `the key follows its coroutine onto another thread`() = runBlocking {
        val key = UUID.randomUUID()
        withContext(Dispatchers.Default + AuditActor.asContextElement(key)) {
            withContext(Dispatchers.IO) {
                assertEquals(key, AuditActor.currentApiKeyId())
            }
            assertEquals(key, AuditActor.currentApiKeyId())
        }
        assertNull(AuditActor.currentApiKeyId())
    }
}
