package dev.tracedown.common.util

import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The endpoint key is what every per-endpoint statistic is grouped by and what
 * is written into an aggregate row, so it has to be exactly reproducible from
 * the script alone. These tests pin the derivation form by form.
 */
class EndpointKeysTest {

    // ── The worked examples ─────────────────────────────────────────────

    @Test
    fun `a call keys on its method and its written URL template`() {
        assertEquals("POST {p.baseUrl}/login", EndpointKeys.key("post", "\$p.baseUrl/login"))
        assertEquals("GET {p.baseUrl}/orders", EndpointKeys.key("get", "\$p.baseUrl/orders?limit=10"))
        assertEquals("GET {p.baseUrl}/orders/{orderId}", EndpointKeys.key("get", "\$p.baseUrl/orders/\$\$orderId"))
        assertEquals("GET https://api.example.com/v1/items", EndpointKeys.key("get", "https://API.example.com/v1/items/"))
        assertEquals("GET {nextLink}", EndpointKeys.key("get", "\$\$nextLink"))
    }

    @Test
    fun `two calls with the same method and template are one endpoint`() {
        assertEquals(
            EndpointKeys.key("get", "\$p.baseUrl/orders/\$\$id"),
            EndpointKeys.key("GET", "\$p.baseUrl/orders/\$\$id"),
        )
    }

    // ── Every interpolation form of spec 3.5 ────────────────────────────

    @Test
    fun `an injected variable becomes a placeholder`() {
        assertEquals("/a/{token}/b", EndpointKeys.template("/a/\$token/b"))
    }

    @Test
    fun `a run-scope variable becomes a placeholder`() {
        assertEquals("/a/{orderId}", EndpointKeys.template("/a/\$\$orderId"))
    }

    @Test
    fun `the braced forms name the same variables`() {
        assertEquals("/a/{token}", EndpointKeys.template("/a/\${\$token}"))
        assertEquals("/a/{orderId}", EndpointKeys.template("/a/\${\$\$orderId}"))
    }

    @Test
    fun `a braced form may sit against adjacent text`() {
        assertEquals("{host}name/x", EndpointKeys.template("\${\$host}name/x"))
        assertEquals("https://{host}.example.com/x", EndpointKeys.template("https://\${\$host}.example.com/x"))
    }

    @Test
    fun `an escaped dollar still names a variable, as the spec says it does`() {
        // Spec 3.5: `\$` produces a literal `$` but does NOT prevent
        // interpolation. The lexer resolves the escape before a parsed script
        // ever reaches here, so both spellings have to agree.
        assertEquals("/a/{name}", EndpointKeys.template("/a/\\\$name"))
        assertEquals(EndpointKeys.template("/a/\$name"), EndpointKeys.template("/a/\\\$name"))
    }

    @Test
    fun `a dollar that begins no reference is literal text`() {
        assertEquals("/a/\$1", EndpointKeys.template("/a/\$1"))
        assertEquals("/a/\$/b", EndpointKeys.template("/a/\$/b"))
        assertEquals("/a/\$", EndpointKeys.template("/a/\$"))
        assertEquals("/a/\$\$9", EndpointKeys.template("/a/\$\$9"))
        assertEquals("/a/\$\$", EndpointKeys.template("/a/\$\$"))
        assertEquals("/price-\$5", EndpointKeys.template("/price-\$5"))
    }

    @Test
    fun `an unclosed or empty brace is literal text`() {
        assertEquals("/a/\${}", EndpointKeys.template("/a/\${}"))
        assertEquals("/a/\${name}", EndpointKeys.template("/a/\${name}"))
    }

    @Test
    fun `an unclosed brace leaves its dollar as text and the name inside still interpolates`() {
        // What the canonical executor does: the braced alternative fails to
        // match, the scan moves on one character, and the `$name` that starts
        // inside the brace matches the bare form. The key has to agree with the
        // run, not with what the brace looked like it meant.
        assertEquals("/a/\${{name}", EndpointKeys.template("/a/\${\$name"))
    }

    @Test
    fun `a name runs to the first character that cannot be in one`() {
        assertEquals("{host}-suffix", EndpointKeys.template("\$host-suffix"))
        assertEquals("{a_b9}/x", EndpointKeys.template("\$a_b9/x"))
    }

    // ── Scoped names ────────────────────────────────────────────────────

    @Test
    fun `each scope letter carries its dotted name into the placeholder`() {
        assertEquals("{o.key}", EndpointKeys.template("\$o.key"))
        assertEquals("{w.key}", EndpointKeys.template("\$w.key"))
        assertEquals("{p.key}", EndpointKeys.template("\$p.key"))
        assertEquals("{s.key}", EndpointKeys.template("\$s.key"))
        assertEquals("{s.key}", EndpointKeys.template("\${\$s.key}"))
    }

    @Test
    fun `a dotted name that is not a scope splits the way a run would read it`() {
        // Only the four scope letters are rewritten into a Lace identifier, so
        // only they interpolate with the dot. `$foo.bar` interpolates `$foo`
        // and leaves `.bar` as text — the key has to say the same.
        assertEquals("{foo}.bar", EndpointKeys.template("\$foo.bar"))
        assertEquals("{sx}.bar", EndpointKeys.template("\$sx.bar"))
        assertEquals("{s}.", EndpointKeys.template("\$s."))
        assertEquals("{s}.9", EndpointKeys.template("\$s.9"))
    }

    // ── The query/fragment cut ──────────────────────────────────────────

    @Test
    fun `the cut is made on the template, so an interpolated query goes too`() {
        assertEquals("{p.baseUrl}/orders", EndpointKeys.template("\$p.baseUrl/orders?page=\$\$n&size=10"))
        assertEquals("/a", EndpointKeys.template("/a#\$\$frag"))
        assertEquals("/a", EndpointKeys.template("/a#x?y"))
        assertEquals("/a", EndpointKeys.template("/a?y#x"))
        assertEquals("", EndpointKeys.template("?everything"))
    }

    @Test
    fun `a question mark inside a variable name cannot exist, so nothing else is cut`() {
        assertEquals("https://api.example.com/a%3Fb", EndpointKeys.template("https://api.example.com/a%3Fb"))
    }

    // ── Casing, trailing slash, whitespace, length ──────────────────────

    @Test
    fun `a literal scheme and host are lower-cased and nothing else is`() {
        assertEquals("https://api.example.com/Orders/Item", EndpointKeys.template("HTTPS://API.Example.COM/Orders/Item"))
    }

    @Test
    fun `a host that carries a placeholder keeps its case`() {
        assertEquals("https://{Host}/A", EndpointKeys.template("https://\${\$Host}/A"))
    }

    @Test
    fun `userinfo is left alone`() {
        assertEquals("https://User:Pass@api.example.com/x", EndpointKeys.template("https://User:Pass@API.EXAMPLE.com/x"))
    }

    @Test
    fun `a trailing slash is removed but a bare slash is kept`() {
        assertEquals("https://api.example.com/v1", EndpointKeys.template("https://api.example.com/v1/"))
        assertEquals("/", EndpointKeys.template("/"))
        assertEquals("https://api.example.com", EndpointKeys.template("https://api.example.com/"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("https://api.example.com/x", EndpointKeys.template("   https://api.example.com/x   "))
        assertEquals("https://api.example.com/x", EndpointKeys.template("  https://api.example.com/x  ?q=1"))
    }

    @Test
    fun `a template is truncated to 200 characters`() {
        val long = "https://api.example.com/" + "a".repeat(400)
        val template = EndpointKeys.template(long)
        assertEquals(200, template.length)
        assertEquals(EndpointKeys.MAX_TEMPLATE_CHARS, template.length)
        // The longest method plus a space plus the longest template is what
        // the VARCHAR(210) columns have to hold.
        assertTrue(EndpointKeys.key("delete", long).length <= 210)
    }

    @Test
    fun `unicode and percent-encoded paths are left untouched`() {
        assertEquals("https://api.example.com/caf%C3%A9/über", EndpointKeys.template("https://api.example.com/caf%C3%A9/über"))
        assertEquals("https://api.example.com/商品/1", EndpointKeys.template("https://api.example.com/商品/1"))
    }

    @Test
    fun `empty and odd inputs produce something storable`() {
        assertEquals("", EndpointKeys.template(""))
        assertEquals("", EndpointKeys.template("     "))
        assertEquals("GET ", EndpointKeys.key("get", ""))
        assertEquals("* {expr}", EndpointKeys.key(null, null))
        assertEquals("* {expr}", EndpointKeys.key("  ", 42))
        assertEquals("GET {expr}", EndpointKeys.key("get", listOf("not a string")))
    }

    // ── No value can reach a key ────────────────────────────────────────

    @Test
    fun `no function here can be handed a variable's value`() {
        // A URL may carry a secret (a token in a path), and a key is stored and
        // shown. The guarantee is structural rather than a rule someone has to
        // remember: there is nowhere in any signature to put a value.
        val offenders = EndpointKeys::class.java.declaredMethods
            .filter { Modifier.isPublic(it.modifiers) && !it.isSynthetic }
            .flatMap { method -> method.parameterTypes.map { method.name to it } }
            .filter { (_, type) -> type != String::class.java && type != Any::class.java }
        assertEquals(emptyList(), offenders, "an endpoint key must not be derivable from any value")
    }

    // ── The fallback, for rows that carry no key ────────────────────────

    @Test
    fun `a fallback key names no method`() {
        assertEquals("* https://api.example.com/orders", EndpointKeys.fallbackKey("https://api.example.com/orders"))
    }

    @Test
    fun `the fallback drops the query and the fragment`() {
        assertEquals("* https://api.example.com/orders", EndpointKeys.fallbackKey("https://api.example.com/orders?page=2"))
        assertEquals("* https://api.example.com/orders", EndpointKeys.fallbackKey("https://api.example.com/orders#top"))
    }

    @Test
    fun `the fallback masks segments that read as identifiers`() {
        assertEquals("https://api.example.com/orders/{id}", EndpointKeys.normalizeResolvedUrl("https://api.example.com/orders/42"))
        assertEquals(
            "https://api.example.com/u/{id}",
            EndpointKeys.normalizeResolvedUrl("https://api.example.com/u/2f1c9b0e-1f2a-4c3d-9e8f-0a1b2c3d4e5f"),
        )
        assertEquals("https://api.example.com/b/{id}", EndpointKeys.normalizeResolvedUrl("https://api.example.com/b/deadbeefdeadbeef"))
        assertEquals(
            "https://api.example.com/t/{id}",
            EndpointKeys.normalizeResolvedUrl("https://api.example.com/t/abcdefghijklmnopqrstuvwx"),
        )
        assertEquals("/a/{id}/{id}", EndpointKeys.normalizeResolvedUrl("/a/1/2"))
    }

    @Test
    fun `the fallback leaves names that are not identifiers`() {
        assertEquals("https://api.example.com/v1/orders", EndpointKeys.normalizeResolvedUrl("https://api.example.com/v1/orders"))
        // 15 hex characters is under the run length, and it is not 24 token
        // characters either.
        assertEquals("/b/deadbeefdeadbee", EndpointKeys.normalizeResolvedUrl("/b/deadbeefdeadbee"))
        assertEquals("/b/abcdefghijklmnopqrstuvw", EndpointKeys.normalizeResolvedUrl("/b/abcdefghijklmnopqrstuvw"))
    }

    @Test
    fun `the fallback never masks the host`() {
        // A host can look exactly like a long opaque token; it is not an id.
        assertEquals(
            "https://abcdefghijklmnopqrstuvwxyz/x",
            EndpointKeys.normalizeResolvedUrl("https://abcdefghijklmnopqrstuvwxyz/x"),
        )
        assertEquals("https://deadbeefdeadbeef", EndpointKeys.normalizeResolvedUrl("https://deadbeefdeadbeef"))
    }

    @Test
    fun `the fallback survives an empty or relative url`() {
        assertEquals("* ", EndpointKeys.fallbackKey(""))
        assertEquals("* /", EndpointKeys.fallbackKey("/"))
        assertEquals("* orders/{id}", EndpointKeys.fallbackKey("orders/42"))
    }

    @Test
    fun `a fallback key fits the column too`() {
        assertTrue(EndpointKeys.fallbackKey("https://api.example.com/" + "a".repeat(400)).length <= 210)
    }

    // ── Round trip ──────────────────────────────────────────────────────

    @Test
    fun `a key splits back into its method and its template`() {
        assertEquals("GET" to "{p.baseUrl}/orders", EndpointKeys.split("GET {p.baseUrl}/orders"))
        assertEquals("*" to "/a/{id}", EndpointKeys.split("* /a/{id}"))
        assertEquals("GET" to "", EndpointKeys.split("GET "))
        assertEquals("nospace" to "", EndpointKeys.split("nospace"))
    }

    @Test
    fun `a template never contains the characters the key format reserves`() {
        // The split is on the first space, so a template must not open with
        // one — the trim is what guarantees it.
        assertNull(EndpointKeys.template("  /a  ").firstOrNull()?.takeIf { it == ' ' })
    }
}
