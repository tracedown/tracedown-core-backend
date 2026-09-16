package dev.tracedown.scheduler.config

import com.typesafe.config.ConfigFactory
import dev.tracedown.common.domain.TargetOptOut
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

/**
 * Honouring a target's do-not-probe record is a default, not an opt-in: an
 * install that has to be configured before it stops probing hosts that asked it
 * not to is an install that will not do it. The off switch still has to exist,
 * and has to be reachable from the environment — an operator whose targets are
 * all their own infrastructure should not have to edit a packaged file.
 */
class TargetOptOutConfigTest {

    private val shipped = ConfigFactory.parseResources("application.conf").resolve()

    @Test
    fun `the shipped configuration honours the record`() {
        assertTrue(shipped.getBoolean("probe.honourTargetOptOut"))
    }

    @Test
    fun `the setting is reachable from the environment`() {
        val raw = javaClass.classLoader.getResource("application.conf")!!.readText()
        assertTrue(
            raw.contains("PROBE_HONOUR_TARGET_OPT_OUT"),
            "the check must be settable from the environment, not only from the packaged file",
        )
        assertTrue(
            raw.contains(TargetOptOut.RECORD_PREFIX),
            "the packaged file has to name the record an operator would be asked about",
        )
    }
}
