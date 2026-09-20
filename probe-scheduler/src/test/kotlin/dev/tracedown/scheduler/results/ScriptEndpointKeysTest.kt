package dev.tracedown.scheduler.results

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class ScriptEndpointKeysTest {

    @BeforeEach
    fun reset() = ScriptEndpointKeys.clearCache()

    @Test
    fun `keys come back one per call, in the script's order`() {
        val script = """
            post("${'$'}p.baseUrl/login", { body: json({ user: "${'$'}o.user" }) }).expect(status: 200).store({ token: this.body.token })
            get("${'$'}p.baseUrl/orders?limit=10").expect(status: 200)
            get("${'$'}p.baseUrl/orders/${'$'}${'$'}orderId").expect(status: 200)
        """.trimIndent()

        assertEquals(
            listOf(
                "POST {p.baseUrl}/login",
                "GET {p.baseUrl}/orders",
                "GET {p.baseUrl}/orders/{orderId}",
            ),
            ScriptEndpointKeys.keysFor(script),
        )
    }

    @Test
    fun `two calls to the same endpoint key the same, and keep their positions`() {
        val script = """
            get("https://api.example.com/health").expect(status: 200)
            get("https://api.example.com/orders").expect(status: 200)
            get("https://API.example.com/health/").expect(status: 200)
        """.trimIndent()

        assertEquals(
            listOf(
                "GET https://api.example.com/health",
                "GET https://api.example.com/orders",
                "GET https://api.example.com/health",
            ),
            ScriptEndpointKeys.keysFor(script),
        )
    }

    @Test
    fun `a script that does not parse yields no keys at all`() {
        assertNull(ScriptEndpointKeys.keysFor("""get("https://api.example.com/x" .expect(status: 200)"""))
        assertNull(ScriptEndpointKeys.keysFor("this is not a script"))
        assertNull(ScriptEndpointKeys.keysFor(""))
        assertNull(ScriptEndpointKeys.keysFor("   "))
    }

    @Test
    fun `a script with no chain method is refused by the grammar and yields no keys`() {
        // The parser requires at least one chain method per call (spec 2.3);
        // a dispatch of such a script must still go ahead, unkeyed.
        assertNull(ScriptEndpointKeys.keysFor("""get("https://api.example.com/x")"""))
    }

    @Test
    fun `the same script is parsed once`() {
        val script = """get("https://api.example.com/x").expect(status: 200)"""
        val first = ScriptEndpointKeys.keysFor(script)
        val second = ScriptEndpointKeys.keysFor(script)
        assertSame(first, second, "a repeated script should come back off the cache")
    }

    @Test
    fun `two scripts that differ by one character are two cache entries`() {
        val a = """get("https://api.example.com/a").expect(status: 200)"""
        val b = """get("https://api.example.com/b").expect(status: 200)"""
        assertEquals(listOf("GET https://api.example.com/a"), ScriptEndpointKeys.keysFor(a))
        assertEquals(listOf("GET https://api.example.com/b"), ScriptEndpointKeys.keysFor(b))
    }

    @Test
    fun `a key never carries a value, only the name of the variable that holds one`() {
        val script = """get("https://api.example.com/${'$'}${'$'}apiToken/secrets").expect(status: 200)"""
        assertEquals(listOf("GET https://api.example.com/{apiToken}/secrets"), ScriptEndpointKeys.keysFor(script))
    }
}
