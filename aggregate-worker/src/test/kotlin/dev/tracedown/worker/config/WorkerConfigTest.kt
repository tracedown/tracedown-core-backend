package dev.tracedown.worker.config

import com.typesafe.config.ConfigFactory
import io.ktor.server.config.ApplicationConfig
import io.ktor.server.config.HoconApplicationConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The retention windows as an operator actually sets them: through the shipped
 * `application.conf` and the environment variables it substitutes.
 *
 * These read the real resource rather than a hand-built map, because the thing
 * most likely to break is the `${?BODY_RETENTION_DAYS}` line itself — a typo
 * there leaves the code perfectly correct and the variable inert.
 */
class WorkerConfigTest {

    /** The shipped application.conf, resolved against [env] as the environment. */
    private fun conf(env: Map<String, String> = emptyMap()): ApplicationConfig {
        // The overrides go in at the root, which is where `${'$'}{?BODY_RETENTION_DAYS}`
        // looks first — the same place the real environment is consulted.
        val resolved = ConfigFactory.parseMap(env)
            .withFallback(ConfigFactory.parseResources("application.conf"))
            .resolve()
        return HoconApplicationConfig(resolved)
    }

    @Test
    fun `the body window is off by default so an upgrade changes nothing`() {
        // -1: bodies follow their results, exactly as before the split. Any
        // other default sheds bodies on upgrade for every installation whose
        // result window is not that number.
        assertEquals(-1, WorkerConfig.load(conf()).bodyRetentionDays)
        assertEquals(90, WorkerConfig.load(conf()).resultRetentionDays)
    }

    @Test
    fun `BODY_RETENTION_DAYS overrides the configured value`() {
        assertEquals(30, WorkerConfig.load(conf(mapOf("BODY_RETENTION_DAYS" to "30"))).bodyRetentionDays)
        assertEquals(-1, WorkerConfig.load(conf(mapOf("BODY_RETENTION_DAYS" to "-1"))).bodyRetentionDays)
        assertEquals(
            365,
            WorkerConfig.load(conf(mapOf("RESULT_RETENTION_DAYS" to "365"))).resultRetentionDays,
        )
    }

    @Test
    fun `a body window of zero is refused at startup, by name`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkerConfig.load(conf(mapOf("BODY_RETENTION_DAYS" to "0")))
        }
        assertEquals(
            "BODY_RETENTION_DAYS must not be 0 — use -1 to never expire by age, or a positive number of days",
            error.message,
        )
    }

    @Test
    fun `a result window of zero is refused at startup, by name`() {
        // The dangerous half: 0 days reads as "expire everything now" to the
        // cutoff arithmetic. Refusing to start is the only safe answer.
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkerConfig.load(conf(mapOf("RESULT_RETENTION_DAYS" to "0")))
        }
        assertEquals(
            "RESULT_RETENTION_DAYS must not be 0 — use -1 to never expire by age, or a positive number of days",
            error.message,
        )
    }

    @Test
    fun `a retention window that is not a number is refused, not silently defaulted`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            WorkerConfig.load(conf(mapOf("BODY_RETENTION_DAYS" to "ninety")))
        }
        assertTrue(error.message!!.startsWith("BODY_RETENTION_DAYS must be a whole number of days"), error.message)
    }
}
