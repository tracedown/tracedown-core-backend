package dev.tracedown.common.domain

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `usesIncludes` detection (spec §18.4 anti-scraping). These scripts target an
 * unresolvable host (`$x` never substituted), so `evaluate` returns before it
 * queries verified domains — no database needed to exercise the text analysis.
 */
class DomainPolicyTest {

    private val org = UUID.randomUUID()

    @Test
    fun `includes against an unverified target is flagged`() {
        val e = DomainPolicy.evaluate(
            """get("${'$'}x").assert({ expect: [includes("secret", this.body)] })""",
            emptyMap(),
            org,
        )
        assertFalse(e.covered, "unresolvable host is not covered")
        assertTrue(e.usesIncludes, "includes() must be detected")
    }

    @Test
    fun `an unresolvable target is named by its raw url`() {
        val e = DomainPolicy.evaluate(
            """get("${'$'}x/health"); get("${'$'}x/health"); get("${'$'}y")""",
            emptyMap(),
            org,
        )
        assertFalse(e.covered)
        assertEquals(listOf("${'$'}x/health", "${'$'}y"), e.unverifiedHosts, "distinct, in script order")
    }

    @Test
    fun `a script with no calls names nothing`() {
        val e = DomainPolicy.evaluate("// nothing to fetch", emptyMap(), org)
        assertTrue(e.covered)
        assertEquals(emptyList(), e.unverifiedHosts)
    }

    @Test
    fun `includes is detected regardless of spacing and clause`() {
        val e = DomainPolicy.evaluate(
            """get("${'$'}x").assert({ check: [includes ("a", this.body)] })""",
            emptyMap(),
            org,
        )
        assertTrue(e.usesIncludes)
    }

    @Test
    fun `a script without includes is not flagged`() {
        val e = DomainPolicy.evaluate(
            """get("${'$'}x").assert({ expect: [count(this.body.items) eq 3] })""",
            emptyMap(),
            org,
        )
        assertFalse(e.usesIncludes, "count() and other functions must not trip the includes rule")
    }
}
