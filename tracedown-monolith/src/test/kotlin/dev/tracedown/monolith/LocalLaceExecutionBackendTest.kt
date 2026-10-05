package dev.tracedown.monolith

import dev.tracedown.scheduler.dispatch.ProbeExecutionBackend
import dev.tracedown.scheduler.dispatch.SyntheticProbeResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The embedded executor's run budget.
 *
 * The defect this covers: the budget was a `withTimeout` around a blocking,
 * non-suspending call, which can only cancel at a suspension point — so it
 * never fired, a run that hung held a dispatch worker for as long as the
 * script's own per-call timeouts allowed, and the synthetic timeout (and the
 * notification event it now carries) was unreachable.
 */
class LocalLaceExecutionBackendTest {

    private fun request(timeoutMs: Int) = ProbeExecutionBackend.Request(
        serviceId = UUID.randomUUID(),
        orgId = UUID.randomUUID(),
        projectId = UUID.randomUUID(),
        workspaceId = UUID.randomUUID(),
        probeMode = "consecutive",
        script = "get(\"https://example.invalid/\").expect(status: 200)",
        variables = JsonObject(emptyMap()),
        timeoutMs = timeoutMs,
        prev = null,
        allowBodySave = false,
        secretValues = emptySet(),
    )

    private val quick: JsonObject = buildJsonObject {
        put("outcome", "success")
        put("elapsedMs", 10)
    }

    /** A runner that blocks until released, recording whether it was told it had been abandoned. */
    private class Hang {
        val release = CountDownLatch(1)
        val sawAbandoned = AtomicBoolean(false)
        val runner: (ProbeExecutionBackend.Request, AtomicBoolean) -> JsonObject = { _, abandoned ->
            release.await(10, TimeUnit.SECONDS)
            sawAbandoned.set(abandoned.get())
            buildJsonObject { put("outcome", "success") }
        }
    }

    @Test
    fun `a run that outlives its budget is answered at the budget with a timeout event`() {
        val hang = Hang()
        val backend = LocalLaceExecutionBackend("/nonexistent", runner = hang.runner)
        try {
            val started = System.nanoTime()
            val (execution) = runBlocking { backend.execute(request(timeoutMs = 1_000)) }
            val waitedMs = (System.nanoTime() - started) / 1_000_000

            val result = execution.result!!
            assertEquals(JsonPrimitive("timeout"), result["outcome"])
            assertEquals("probe did not finish within its 1000ms run budget", result["error"]!!.jsonPrimitive.content)
            val event = result["actions"]!!.jsonObject["notifications"]!!.jsonArray.single().jsonObject
            assertEquals(JsonPrimitive("timeout"), event["trigger"])
            assertEquals(JsonPrimitive(-1), event["callIndex"])
            assertEquals(
                "${SyntheticProbeResult.SERVICE_PREFIX} timed out: the run did not complete within 1000ms",
                event["notification"]!!.jsonObject["value"]!!.jsonPrimitive.content,
            )
            // The whole point: answered on the budget — neither early nor when
            // the run ends — and the elapsed time is the measured one.
            assertTrue(waitedMs in 1_000..2_500, "answered after ${waitedMs}ms")
            assertTrue(result["elapsedMs"]!!.jsonPrimitive.content.toLong() in 1_000..2_500)

            // The abandoned run learns it was abandoned, so it writes nothing.
            hang.release.countDown()
            Thread.sleep(200)
            assertTrue(hang.sawAbandoned.get())
        } finally {
            hang.release.countDown()
            backend.close()
        }
    }

    @Test
    fun `a non-positive timeout means no budget, not an instant timeout`() {
        // The agent omits the budget for a non-positive configured timeout and
        // runs to the script's own per-call timeouts; so does the monolith.
        val backend = LocalLaceExecutionBackend("/nonexistent") { _, _ ->
            Thread.sleep(300)
            quick
        }
        try {
            for (timeoutMs in listOf(0, -1)) {
                val (execution) = runBlocking { backend.execute(request(timeoutMs = timeoutMs)) }
                assertEquals(quick, execution.result)
            }
        } finally {
            backend.close()
        }
    }

    @Test
    fun `a run the pool never started is nothing learned, not a timeout of the target`() {
        val hang = Hang()
        val backend = LocalLaceExecutionBackend("/nonexistent", poolSize = 1, runner = hang.runner)
        try {
            runBlocking {
                // Occupy the only worker past its budget …
                val (first) = backend.execute(request(timeoutMs = 1_000))
                assertEquals(JsonPrimitive("timeout"), first.result!!["outcome"])
                // … then the next run expires in the queue.
                val (queued) = backend.execute(request(timeoutMs = 1_000))
                assertNull(queued.result)
                assertEquals("agent_rejected", queued.failureReason)
            }
        } finally {
            hang.release.countDown()
            backend.close()
        }
    }

    @Test
    fun `a run inside its budget is returned untouched`() {
        val backend = LocalLaceExecutionBackend("/nonexistent") { _, abandoned ->
            assertFalse(abandoned.get())
            quick
        }
        try {
            val (execution) = runBlocking { backend.execute(request(timeoutMs = 5_000)) }
            assertEquals(quick, execution.result)
            assertNull(execution.agentId)
        } finally {
            backend.close()
        }
    }

    @Test
    fun `a run that throws is an error, with no event and no claim about the target`() {
        val backend = LocalLaceExecutionBackend("/nonexistent") { _, _ -> throw IllegalStateException("parse failed") }
        try {
            val (execution) = runBlocking { backend.execute(request(timeoutMs = 5_000)) }
            val result = execution.result!!
            assertEquals(JsonPrimitive("error"), result["outcome"])
            assertEquals(JsonPrimitive("parse failed"), result["error"])
            assertNull(result["actions"])
        } finally {
            backend.close()
        }
    }

    @Test
    fun `per-call timeout and recovery texts are dispatcher templates over the service prefix`() {
        val config = LocalLaceRun.extensionConfig(listOf("laceNotifications", "laceEmitRecovery"))
        assertEquals(
            mapOf("timeout_message" to "\${s.name} in \${w.name}.\${p.name} call to \${url} timed out"),
            config["laceNotifications"],
        )
        assertEquals(
            mapOf("recovery_message" to "\${s.name} in \${w.name}.\${p.name} recovered"),
            config["laceEmitRecovery"],
        )
        assertNull(LocalLaceRun.extensionConfig(listOf("laceNotifications"))["laceEmitRecovery"])
    }
}
