package dev.tracedown.notifications.templates

import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class NotificationBuilderTest {

    private val orgId = UUID.randomUUID()
    private val projectId = UUID.randomUUID()

    private val staticVars = mapOf(
        "s.name" to "API Health",
        "w.name" to "Production",
        "p.name" to "Core",
        "s.schedule" to "*/5 * * * *",
    )

    @Test
    fun `returns empty list when rawResult has no actions`() {
        val rawResult = buildJsonObject {
            put("outcome", "pass")
            put("elapsedMs", 120)
        }
        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list when actions has no notifications`() {
        val rawResult = buildJsonObject {
            put("outcome", "pass")
            put("elapsedMs", 120)
            putJsonObject("actions") {}
        }
        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `returns empty list when notifications array is empty`() {
        val rawResult = buildJsonObject {
            put("outcome", "pass")
            put("elapsedMs", 120)
            putJsonObject("actions") {
                putJsonArray("notifications") {}
            }
        }
        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `groups notifications by callIndex and trigger`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 300)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com/health") }
                    putJsonObject("response") { put("responseTimeMs", 250) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    // Two events with the same (callIndex=0, trigger=expect) should be grouped
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "503")
                            }
                        }
                    }
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "500")
                            }
                        }
                    }
                    // Different trigger should produce a separate group
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "timeout")
                        putJsonObject("notification") {
                            put("tag", "structured")
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(2, result.size)

        val triggers = result.map { it.trigger }.toSet()
        assertEquals(setOf("expect", "timeout"), triggers)
    }

    @Test
    fun `renders structured notification with default template`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 150)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com/status") }
                    putJsonObject("response") { put("responseTimeMs", 140) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "503")
                            }
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)

        val notification = result.first()
        assertEquals(0, notification.callIndex)
        assertEquals("expect", notification.trigger)
        assertEquals("status", notification.scope)

        // Default expect template: ${s.name} in ${w.name}.${p.name} call to ${url} has "${trigger}": [${scope}: expected ${expected}, got ${actual}]
        assertEquals(
            "API Health in Production.Core call to https://api.example.com/status has \"expect\": [status: expected 200, got 503]",
            notification.text,
        )
    }

    @Test
    fun `renders text notification using literal text as template`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 200)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com/v1") }
                    putJsonObject("response") { put("responseTimeMs", 180) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "text")
                            // Spec §12.1: a "text" notification carries its literal
                            // in `value` (a "structured" tag uses `data`).
                            put("value", "Service \${s.name} is down! URL: \${url}")
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals("Service API Health is down! URL: https://api.example.com/v1", result.first().text)
    }

    @Test
    fun `recovery is composed from the platform template with downtime, ignoring inline text`() {
        val rawResult = buildJsonObject {
            put("outcome", "success")
            put("elapsedMs", 100)
            putJsonArray("calls") {}
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", -1)
                        put("trigger", "recovered")
                        putJsonObject("notification") {
                            put("tag", "text")
                            // Inline recovery text must be ignored — the platform composes it.
                            put("value", "\${s.name} recovered")
                        }
                    }
                }
            }
        }
        val vars = staticVars + ("downtime" to "12m 0s")
        val result = NotificationBuilder.buildNotifications(rawResult, vars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals("API Health in Production.Core recovered after 12m 0s of downtime", result.first().text)
    }

    @Test
    fun `groups multiple failed scopes of one assertion into a single message`() {
        val rawResult = buildJsonObject {
            put("outcome", "failure")
            put("elapsedMs", 165)
            putJsonArray("calls") {
                addJsonObject { putJsonObject("request") { put("url", "https://api.example.com/flip") } }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0); put("trigger", "check"); put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") { put("scope", "status"); put("expected", 200); put("actual", 500) }
                        }
                    }
                    addJsonObject {
                        put("callIndex", 0); put("trigger", "check"); put("scope", "totalDelayMs")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") { put("scope", "totalDelayMs"); put("expected", 2); put("actual", 165) }
                        }
                    }
                }
            }
        }
        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals(
            "API Health in Production.Core call to https://api.example.com/flip has \"check\": " +
                "[status: expected 200, got 500; totalDelayMs: expected 2, got 165]",
            result.first().text,
        )
    }

    @Test
    fun `baseline spike renders average instead of expected`() {
        val rawResult = buildJsonObject {
            put("outcome", "success")
            put("elapsedMs", 100)
            putJsonArray("calls") {
                addJsonObject { putJsonObject("request") { put("url", "https://api.example.com/flip") } }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0); put("trigger", "baseline_spike"); put("scope", "dnsMs")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") { put("metric", "dnsMs"); put("average", 6.5); put("actual", 22) }
                        }
                    }
                }
            }
        }
        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals(
            "API Health in Production.Core call to https://api.example.com/flip has \"baseline_spike\": [dnsMs: average 6.5, got 22]",
            result.first().text,
        )
    }

    @Test
    fun `handles multiple notification events with different call indices`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 500)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com/first") }
                    putJsonObject("response") { put("responseTimeMs", 100) }
                }
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com/second") }
                    putJsonObject("response") { put("responseTimeMs", 350) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "404")
                            }
                        }
                    }
                    addJsonObject {
                        put("callIndex", 1)
                        put("trigger", "assert")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "true")
                                put("actual", "false")
                            }
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(2, result.size)

        val first = result.find { it.callIndex == 0 }!!
        assertEquals("expect", first.trigger)
        assertTrue(first.text.contains("https://api.example.com/first"))

        val second = result.find { it.callIndex == 1 }!!
        assertEquals("assert", second.trigger)
        assertTrue(second.text.contains("https://api.example.com/second"))
        // Assert template: ${s.name} in ${w.name}.${p.name} call to ${url} assertion failed: expected ${expected}, got ${actual}
        assertEquals(
            "API Health in Production.Core call to https://api.example.com/second assertion failed: expected true, got false",
            second.text,
        )
    }

    @Test
    fun `runtime vars are extracted from result JSON`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 5000)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://slow.example.com/api") }
                    putJsonObject("response") { put("responseTimeMs", 4800) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "timeout")
                        putJsonObject("notification") {
                            put("tag", "structured")
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)

        val notification = result.first()
        // Timeout template: ${s.name} call to ${url} timed out after ${ms}ms
        assertEquals(
            "API Health call to https://slow.example.com/api timed out after 4800ms",
            notification.text,
        )
    }

    @Test
    fun `defaults callIndex to 0 when missing`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 100)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com") }
                    putJsonObject("response") { put("responseTimeMs", 90) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("trigger", "expect")
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "500")
                            }
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals(0, result.first().callIndex)
    }

    @Test
    fun `defaults trigger to expect when missing`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 100)
            putJsonArray("calls") {
                addJsonObject {
                    putJsonObject("request") { put("url", "https://api.example.com") }
                    putJsonObject("response") { put("responseTimeMs", 90) }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("scope", "status")
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") {
                                put("expected", "200")
                                put("actual", "500")
                            }
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        assertEquals("expect", result.first().trigger)
    }

    @Test
    fun `uses elapsedMs as fallback when call has no responseTimeMs`() {
        val rawResult = buildJsonObject {
            put("outcome", "fail")
            put("elapsedMs", 3000)
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "timeout")
                        putJsonObject("notification") {
                            put("tag", "structured")
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(1, result.size)
        // No calls array, so ms falls back to elapsedMs
        assertTrue(result.first().text.contains("3000ms"))
    }

    @Test
    fun `connection error renders the error message from a structured notification`() {
        val rawResult = buildJsonObject {
            put("outcome", "failure")
            put("elapsedMs", 434)
            putJsonArray("calls") {
                addJsonObject {
                    put("error", "[Errno 104] Connection reset by peer")
                    putJsonObject("request") { put("url", "https://api.example.com/v1") }
                    put("response", JsonNull)
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "error")
                        put("conditionIndex", -1)
                        putJsonObject("notification") {
                            put("tag", "structured")
                            putJsonObject("data") { put("error", "[Errno 104] Connection reset by peer") }
                        }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertEquals(
            "API Health in Production.Core call to https://api.example.com/v1 failed: [Errno 104] Connection reset by peer",
            result.single().text,
        )
    }

    @Test
    fun `connection error without a message in its payload falls back to the call's error`() {
        val rawResult = buildJsonObject {
            put("outcome", "failure")
            put("elapsedMs", 20)
            putJsonArray("calls") {
                addJsonObject {
                    put("error", "Name or service not known")
                    putJsonObject("request") { put("url", "https://api.example.com/v1") }
                }
            }
            putJsonObject("actions") {
                putJsonArray("notifications") {
                    addJsonObject {
                        put("callIndex", 0)
                        put("trigger", "error")
                        putJsonObject("notification") { put("tag", "structured") }
                    }
                }
            }
        }

        val result = NotificationBuilder.buildNotifications(rawResult, staticVars, orgId, projectId)
        assertTrue(result.single().text.endsWith("failed: Name or service not known"))
    }
}
