package dev.tracedown.gateway.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** The run handle's bound, as the gateway configures it. */
class RunExpiryConfigTest {

    private fun limits(expiry: Long?, timeoutMs: Int = SystemLimitsConfig.DEFAULT_PROBE_TIMEOUT_MS) = SystemLimitsConfig(
        auditLogRetentionDays = 90,
        purgeRetentionDays = 0,
        resultRetentionDays = 90,
        maxVarsPerResource = 100,
        maxApiKeysPerUser = 20,
        runRequestExpirySeconds = expiry,
        probeDefaultTimeoutMs = timeoutMs,
    )

    @Test
    fun `unset derives the bound from the probe timeout, and a setting overrides it`() {
        assertEquals(600, limits(null).effectiveRunExpirySeconds)
        assertEquals(760, limits(null, timeoutMs = 300_000).effectiveRunExpirySeconds)
        assertEquals(90, limits(90).effectiveRunExpirySeconds)
    }

    @Test
    fun `zero, negative, empty and junk read as unset`() {
        for (raw in listOf(null, "", " ", "0", "-5", "ten")) assertNull(SystemLimitsConfig.positive(raw), "'$raw'")
        assertEquals(42L, SystemLimitsConfig.positive(" 42 "))
    }
}
