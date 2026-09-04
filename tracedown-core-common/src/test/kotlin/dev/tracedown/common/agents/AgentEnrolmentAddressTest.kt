package dev.tracedown.common.agents

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The address printed next to a bootstrap token used to be a literal in the
 * dashboard — the gateway's container name on the shipped Docker network —
 * which is wrong for every agent that is not on that network. These pin the
 * seam that replaces it: configured value or an honest null, never a guess.
 */
class AgentEnrolmentAddressTest {

    @AfterTest
    fun reset() = AgentEnrolmentAddress.install(null)

    @Test
    fun `nothing installed answers null, not a guess`() {
        assertNull(AgentEnrolmentAddress.resolve())
    }

    @Test
    fun `a blank configuration is unconfigured`() {
        AgentEnrolmentAddress.install(AgentEnrolmentAddress.fixed("   "))
        assertNull(AgentEnrolmentAddress.resolve())
        AgentEnrolmentAddress.install(AgentEnrolmentAddress.fixed(null))
        assertNull(AgentEnrolmentAddress.resolve())
    }

    @Test
    fun `a configured value is normalised to a base URL`() {
        AgentEnrolmentAddress.install(AgentEnrolmentAddress.fixed(" https://tracedown.example.com/ "))
        assertEquals("https://tracedown.example.com", AgentEnrolmentAddress.resolve())
    }

    @Test
    fun `an installed source is asked on every call`() {
        var current: String? = "https://a.example"
        AgentEnrolmentAddress.install { current }
        assertEquals("https://a.example", AgentEnrolmentAddress.resolve())
        current = "https://b.example/"
        assertEquals("https://b.example", AgentEnrolmentAddress.resolve())
        current = ""
        assertNull(AgentEnrolmentAddress.resolve())
    }
}
