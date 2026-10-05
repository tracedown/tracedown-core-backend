package dev.tracedown.scheduler.dispatch

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The results the platform builds in place of a run no executor finished.
 *
 * The defect this covers: a synthetic `timeout` carried no notification event
 * (no extension ran to emit one), so the dispatcher — which alerts on nothing
 * else — stayed silent about the outage, while the next successful run still
 * emitted `recovered` from `prev.outcome`. The only mail said it was over.
 */
class SyntheticProbeResultTest {

    @Test
    fun `a synthetic timeout announces itself with a run-level timeout event`() {
        val result = SyntheticProbeResult.timeout(45_000, diagnostic = "run did not finish", text = "T timed out")

        assertEquals(JsonPrimitive("timeout"), result["outcome"])
        assertEquals(JsonPrimitive(45_000), result["elapsedMs"])
        assertEquals(buildJsonArray {}, result["calls"])
        assertEquals(JsonPrimitive("run did not finish"), result["error"])
        // Exactly the notification_event shape laceEmitRecovery emits for a
        // run-level event: nothing to point at, so callIndex -1 and no scope.
        val expected = buildJsonObject {
            put("callIndex", -1)
            put("conditionIndex", -1)
            put("trigger", "timeout")
            put("scope", JsonNull)
            put("notification", buildJsonObject {
                put("tag", "text")
                put("value", "T timed out")
            })
        }
        assertEquals(listOf(expected), result["actions"]!!.jsonObject["notifications"]!!.jsonArray.toList())
    }

    @Test
    fun `a synthetic error claims nothing about the target`() {
        // `error` does not move last_run_id, so no recovery is ever announced
        // from it — there is no outage to pair an alert with. This is what a
        // silent agent is recorded as.
        val result = SyntheticProbeResult.error("HTTP 500 — boom")

        assertEquals(JsonPrimitive("error"), result["outcome"])
        assertEquals(buildJsonArray {}, result["calls"])
        assertEquals(JsonPrimitive("HTTP 500 — boom"), result["error"])
        assertNull(result["actions"])
    }
}
