package dev.tracedown.common.agents

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AgentSlugsTest {

    @Test
    fun `lowercase letters, digits and hyphens, 64 at most`() {
        for (slug in listOf("a", "eu-west-1", "0runner", "a".repeat(64))) assertTrue(AgentSlugs.isValid(slug), slug)
    }

    @Test
    fun `anything else is refused`() {
        for (slug in listOf("", "-lead", "Upper", "under_score", "dot.ted", "sp ace", "a/b", "a".repeat(65), "\$deleted:1:x")) {
            assertFalse(AgentSlugs.isValid(slug), slug)
        }
    }
}
