package dev.tracedown.common.agents

import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The read-side seam for the probe fleet.
 *
 * The defect it exists for: the fleet endpoints resolved agents globally. The
 * admin list returned every registered agent's slug, label and `agent_uri` —
 * the address of the machine running the probes — the health-check endpoint
 * resolved any slug by name, and the health roster answered any authenticated
 * session with no organization context at all. In Core that is correct on its
 * own terms (agents genuinely are shared platform infrastructure, `probe_agents`
 * has no owning org), so the fix cannot be an ownership filter baked into Core.
 * It is a permission gate plus this seam: a deployment that DOES give agents
 * owners narrows every fleet read through it.
 *
 * These pin the seam's contract, which is what the Core endpoints rely on.
 */
class AgentVisibilityTest {

    private val org = UUID.randomUUID()
    private val otherOrg = UUID.randomUUID()
    private val user = UUID.randomUUID()

    @AfterTest
    fun reset() = AgentVisibility.install(null)

    @Test
    fun `core installs nothing and sees the whole fleet`() {
        assertFalse(AgentVisibility.isFiltered())
        assertEquals(
            setOf("eu-1", "us-1", "private-a"),
            AgentVisibility.visible(org, user, listOf("eu-1", "us-1", "private-a")),
        )
        assertTrue(AgentVisibility.canSee(org, user, "anything-at-all"))
    }

    @Test
    fun `an installed filter narrows the list and the single-slug check together`() {
        // Stand-in for a deployment where "private-a" belongs to another org.
        AgentVisibility.install { orgId, _, slugs ->
            slugs.filterTo(mutableSetOf()) { it != "private-a" || orgId == otherOrg }
        }

        assertTrue(AgentVisibility.isFiltered())
        assertEquals(
            setOf("eu-1", "us-1"),
            AgentVisibility.visible(org, user, listOf("eu-1", "us-1", "private-a")),
        )
        // The single-slug path must agree with the list path, or the per-agent
        // endpoints hand back one at a time exactly what the list withholds.
        assertFalse(AgentVisibility.canSee(org, user, "private-a"))
        assertTrue(AgentVisibility.canSee(otherOrg, user, "private-a"))
        assertTrue(AgentVisibility.canSee(org, user, "eu-1"))
    }

    @Test
    fun `a filter that hides everything hides everything`() {
        AgentVisibility.install { _, _, _ -> emptySet() }
        assertTrue(AgentVisibility.visible(org, user, listOf("eu-1", "us-1")).isEmpty())
        assertFalse(AgentVisibility.canSee(org, user, "eu-1"))
    }

    @Test
    fun `uninstalling restores the unfiltered default`() {
        AgentVisibility.install { _, _, _ -> emptySet() }
        AgentVisibility.install(null)
        assertFalse(AgentVisibility.isFiltered())
        assertTrue(AgentVisibility.canSee(org, user, "eu-1"))
    }

    @Test
    fun `an empty fleet is answered without consulting anything`() {
        AgentVisibility.install { _, _, _ -> error("must not be asked to filter nothing meaningfully") }
        assertTrue(AgentVisibility.visible(org, user, emptyList()).isEmpty())
    }
}
