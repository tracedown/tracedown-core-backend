package dev.tracedown.common.variables

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The one reader of variable references every script consumer shares: the
 * scheduler's rewrite, endpoint templates and both target policies. Where a
 * reference ends decides which value a host is judged on.
 */
class ScriptReferencesTest {

    private val d = "$"

    private fun names(text: String) = ScriptReferences.find(text).map { Triple(it.name, it.run, it.braced) }

    @Test
    fun `the four Lace forms`() {
        assertEquals(
            listOf(
                Triple("a", false, false),
                Triple("b", true, false),
                Triple("c", false, true),
                Triple("e", true, true),
            ),
            names("${d}a ${d}${d}b ${d}{${d}c} ${d}{${d}${d}e}"),
        )
    }

    @Test
    fun `one dot belongs to a scoped name, and only to a scoped name`() {
        val refs = ScriptReferences.find("https://${d}s.sub.example.com/${d}foo.bar")
        assertEquals(listOf("s.sub", "foo"), refs.map { it.name })
        assertEquals("s_sub", refs[0].variable)
        assertEquals("s", refs[0].scope)
        assertEquals("sub", refs[0].key)
        assertEquals("foo", refs[1].variable)
    }

    @Test
    fun `a dollar that begins no reference is text`() {
        assertEquals(emptyList(), names("${d}1 ${d}/ ${d}{p.key} ${d}"))
    }

    @Test
    fun `replace swaps whole references and keeps the rest`() {
        val out = ScriptReferences.replace("${d}{${d}p.host}name/${d}p.key/${d}missing") { ref ->
            mapOf("p_host" to "H", "p_key" to "K")[ref.variable]
        }
        assertEquals("Hname/K/${d}missing", out)
    }

    @Test
    fun `string escapes resolve as the Lace lexer resolves them`() {
        assertEquals("a\"b\\c\nd\te\rf${d}g\\x", ScriptReferences.unescape("a\\\"b\\\\c\\nd\\te\\rf\\${d}g\\x"))
    }
}
