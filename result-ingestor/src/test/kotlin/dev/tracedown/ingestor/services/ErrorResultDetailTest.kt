package dev.tracedown.ingestor.services

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The diagnostic carried by a run that errored.
 *
 * The defect this covers: a result whose outcome normalised to `error` — a Lace
 * script that failed to run, an executor that raised, an agent answering with a
 * diagnostic instead of a result — was discarded with a log warning. The person
 * who wrote the script got no history row and no message. These rows are
 * persisted now, and this is the message that has to survive with them.
 */
class ErrorResultDetailTest {

    @Test
    fun `a top-level error is the detail`() {
        val raw = buildJsonObject {
            put("outcome", "error")
            put("error", "agent failed while running the probe: HTTP 500 — Internal Server Error")
        }
        assertEquals(
            "agent failed while running the probe: HTTP 500 — Internal Server Error",
            ResultPersistenceService.errorDetail(raw),
        )
    }

    @Test
    fun `a per-call error is used when the run reports none of its own`() {
        // Spec §9 puts non-assertion failure detail on the call record.
        val raw = buildJsonObject {
            put("outcome", "error")
            putJsonArray("calls") {
                add(buildJsonObject { put("index", 0); put("error", JsonNull) })
                add(buildJsonObject { put("index", 1); put("error", "connection reset by peer") })
            }
        }
        assertEquals("connection reset by peer", ResultPersistenceService.errorDetail(raw))
    }

    @Test
    fun `a run-level error wins over a per-call one`() {
        val raw = buildJsonObject {
            put("outcome", "error")
            put("error", "script did not compile")
            putJsonArray("calls") {
                add(buildJsonObject { put("error", "connect timed out") })
            }
        }
        assertEquals("script did not compile", ResultPersistenceService.errorDetail(raw))
    }

    @Test
    fun `an errored run with nothing to say still yields a message`() {
        assertEquals(
            "no detail reported",
            ResultPersistenceService.errorDetail(buildJsonObject { put("outcome", "error") }),
        )
    }

    @Test
    fun `blank and null errors are not mistaken for detail`() {
        val raw = buildJsonObject {
            put("outcome", "error")
            put("error", "   ")
            putJsonArray("calls") {
                add(buildJsonObject { put("error", JsonNull) })
            }
        }
        assertEquals("no detail reported", ResultPersistenceService.errorDetail(raw))
    }

    @Test
    fun `a non-string error payload does not throw`() {
        // Nothing guarantees the shape of a body an agent returned instead of a
        // ProbeResult; extracting the detail must never be what loses the row.
        val raw = buildJsonObject {
            put("outcome", "error")
            put("error", buildJsonObject { put("code", "boom") })
            put("calls", buildJsonArray { add("not an object") })
        }
        assertEquals("no detail reported", ResultPersistenceService.errorDetail(raw))
    }
}
