package dev.tracedown.scheduler.dispatch

import dev.tracedown.scheduler.crypto.TestPki
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * An agent that takes the job and never answers.
 *
 * The defect this covers: that was recorded as a synthetic `timeout` — it
 * counted as downtime, became the next run's `prev`, and the next good run
 * announced a recovery from an outage nobody had been told about, because
 * the stand-in carried no notification event. It is an agent fault, not an
 * observation about the target, and is recorded as an `error` result.
 */
class SilentAgentDispatchTest {

    @Test
    fun `an agent that never answers is recorded as an error, not a timeout`() {
        val agentKey = TestPki.key()
        val cert = TestPki.agentCert(agentKey, listOf(GeneralName(GeneralName.dNSName, "localhost")))
        val release = CountDownLatch(1)
        val server = TestPki.agentServerSocket(agentKey, cert)
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    val reader = socket.getInputStream().bufferedReader()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    // Hold the job: the scheduler must give up on its own.
                    release.await(30, TimeUnit.SECONDS)
                }
            }
        }
        val factory = TestPki.clientFactory()
        try {
            val dispatch = AgentDispatchService(factory, clientGraceMs = 500L)
            val result = runBlocking {
                dispatch.dispatch(
                    agentUri = "https://localhost:${server.localPort}",
                    expectedSlug = TestPki.SLUG,
                    script = "get(\"https://example.invalid/\").expect(status: 200)",
                    variables = JsonObject(emptyMap()),
                    timeoutMs = 1_000,
                    prev = null,
                )
            }

            val body = result.result!!
            assertEquals(JsonPrimitive("error"), body["outcome"])
            assertNull(body["actions"])
            assertEquals("agent did not return a result within 1500ms", body["error"]!!.jsonPrimitive.content)
            // Filed as a result, but it proves nothing about the agent, and it
            // is not something to re-dispatch elsewhere.
            assertFalse(result.fromAgent)
            assertNull(result.failure)
        } finally {
            release.countDown()
            factory.close()
            server.close()
        }
    }
}
