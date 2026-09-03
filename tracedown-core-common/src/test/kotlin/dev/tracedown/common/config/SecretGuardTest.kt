package dev.tracedown.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the production secret guard. The environment is driven through
 * the explicit config-value path (not the DEPLOYMENT_ENV env var), so the tests
 * are hermetic.
 */
class SecretGuardTest {

    @Test
    fun `defaults to dev when unset`() {
        assertEquals("dev", SecretGuard.environment(null))
        assertFalse(SecretGuard.isProduction(null))
    }

    @Test
    fun `production is recognised (case-insensitive)`() {
        assertTrue(SecretGuard.isProduction("production"))
        assertTrue(SecretGuard.isProduction("Production"))
        assertFalse(SecretGuard.isProduction("staging"))
    }

    @Test
    fun `throws in production when a default is insecure`() {
        val e = assertFailsWith<IllegalStateException> {
            SecretGuard.requireSecure(
                "production",
                "test-service",
                mapOf("SECRET (default)" to true, "OTHER (ok)" to false),
            )
        }
        assertTrue(e.message!!.contains("SECRET (default)"))
        assertFalse(e.message!!.contains("OTHER (ok)"))
    }

    @Test
    fun `silent in production when nothing is insecure`() {
        SecretGuard.requireSecure("production", "test-service", mapOf("SECRET" to false))
    }

    @Test
    fun `does not fail in dev even with insecure defaults`() {
        // The whole point: dev keeps the insecure defaults without failing startup.
        // It is not silent about it (see announce), but it does not throw.
        SecretGuard.requireSecure("dev", "test-service", mapOf("SECRET (default)" to true))
        SecretGuard.requireSecure(null, "test-service", mapOf("SECRET (default)" to true))
    }

    @Test
    fun `announcing an unarmed guard never throws`() {
        SecretGuard.announce("dev", "test-service", mapOf("SECRET (default)" to true))
        SecretGuard.announce("prod", "test-service")
        SecretGuard.announce(null, "test-service")
    }

    @Test
    fun `announcing a healthy production boot is not an error`() {
        // Services with no secrets of their own (metrics, realtime, worker) call
        // announce directly. In production without the override that is the
        // ordinary boot and must not log the "guards DISABLED" ERROR — which it
        // did, on every healthy start, until the override check was added.
        val logger = org.slf4j.LoggerFactory.getLogger(SecretGuard::class.java) as ch.qos.logback.classic.Logger
        val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
        try {
            SecretGuard.announce("production", "test-service")
            val errors = appender.list.filter { it.level == ch.qos.logback.classic.Level.ERROR }
            assertTrue(errors.isEmpty(), "unexpected ERROR: ${errors.map { it.formattedMessage }}")
            assertTrue(appender.list.any { it.formattedMessage.contains("guards armed") })

            appender.list.clear()
            SecretGuard.announce("production", "test-service", mapOf("SECRET (default)" to true))
            assertTrue(appender.list.any { it.level == ch.qos.logback.classic.Level.ERROR && it.formattedMessage.contains("dev defaults in place") })
        } finally {
            logger.detachAppender(appender)
        }
    }

    @Test
    fun `near-misses for production are recognised as typos`() {
        // These are the values that leave a deployment unguarded while looking
        // to the operator like they armed it.
        assertTrue(SecretGuard.looksLikeProduction("prod"))
        assertTrue(SecretGuard.looksLikeProduction("prd"))
        assertTrue(SecretGuard.looksLikeProduction("live"))
        assertTrue(SecretGuard.looksLikeProduction("production-eu"))
    }

    @Test
    fun `ordinary environments are not flagged as typos`() {
        assertFalse(SecretGuard.looksLikeProduction("dev"))
        assertFalse(SecretGuard.looksLikeProduction("staging"))
        assertFalse(SecretGuard.looksLikeProduction("test"))
        // The literal itself is armed, not a near-miss.
        assertFalse(SecretGuard.looksLikeProduction("production"))
    }

    @Test
    fun `trailing space and casing are tolerated, other strings are not`() {
        // What genuinely fails open is a wrong string, not sloppy whitespace.
        assertTrue(SecretGuard.isProduction("  PRODUCTION "))
        assertFalse(SecretGuard.isProduction("prod"))
        assertFalse(SecretGuard.isProduction(""))
    }
}
