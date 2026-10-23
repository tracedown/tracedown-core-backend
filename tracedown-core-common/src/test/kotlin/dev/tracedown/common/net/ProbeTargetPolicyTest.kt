package dev.tracedown.common.net

import java.net.InetAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the probe-target policy. DNS is injected, so nothing here
 * touches the network or depends on what a resolver happens to answer.
 */
class ProbeTargetPolicyTest {

    private val publicOnly = ProbeTargetPolicy.Mode.PUBLIC_ONLY
    private val allowPrivate = ProbeTargetPolicy.Mode.ALLOW_PRIVATE

    /** A resolver that answers everything with one fixed address. */
    private fun resolvingTo(ip: String): (String) -> List<InetAddress> =
        { listOf(InetAddress.getByName(ip)) }

    private val failingResolver: (String) -> List<InetAddress> =
        { throw java.net.UnknownHostException(it) }

    // ---- mode resolution ---------------------------------------------------

    @Test
    fun `auto allows private targets for an install that only probes its own infrastructure`() {
        assertEquals(
            allowPrivate,
            ProbeTargetPolicy.resolveMode(null, trustedDomainMode = true, production = true),
        )
    }

    @Test
    fun `auto allows private targets outside production`() {
        assertEquals(
            allowPrivate,
            ProbeTargetPolicy.resolveMode("auto", trustedDomainMode = false, production = false),
        )
    }

    @Test
    fun `auto restricts a production install that requires domain ownership`() {
        assertEquals(
            publicOnly,
            ProbeTargetPolicy.resolveMode(null, trustedDomainMode = false, production = true),
        )
    }

    @Test
    fun `an explicit setting wins over both signals`() {
        assertEquals(
            publicOnly,
            ProbeTargetPolicy.resolveMode("public-only", trustedDomainMode = true, production = false),
        )
        assertEquals(
            allowPrivate,
            ProbeTargetPolicy.resolveMode("allow-private", trustedDomainMode = false, production = true),
        )
    }

    @Test
    fun `an unrecognised value falls back to auto rather than failing`() {
        assertEquals(
            allowPrivate,
            ProbeTargetPolicy.resolveMode("yes-please", trustedDomainMode = true, production = true),
        )
    }

    // ---- scheme ------------------------------------------------------------

    @Test
    fun `non-http schemes are refused in both modes`() {
        for (mode in listOf(publicOnly, allowPrivate)) {
            assertEquals(
                ProbeTargetPolicy.REASON_SCHEME,
                ProbeTargetPolicy.checkResolved("file:///etc/passwd", mode, resolvingTo("93.184.216.34")),
            )
            assertEquals(
                ProbeTargetPolicy.REASON_SCHEME,
                ProbeTargetPolicy.checkSyntax("gopher://example.com/", mode),
            )
        }
    }

    @Test
    fun `plain http is a legitimate probe target`() {
        assertNull(ProbeTargetPolicy.checkResolved("http://example.com/health", publicOnly, resolvingTo("93.184.216.34")))
    }

    // ---- allow-private mode ------------------------------------------------

    @Test
    fun `allow-private lets a self-hoster probe their own network`() {
        for (url in listOf(
            "http://192.168.1.10/health",
            "http://localhost:8080/ping",
            "http://testbin:20780/status/200",
            "https://10.0.0.5/api",
        )) {
            assertNull(ProbeTargetPolicy.checkResolved(url, allowPrivate, resolvingTo("192.168.1.10")), url)
            assertNull(ProbeTargetPolicy.checkSyntax(url, allowPrivate), url)
        }
    }

    // ---- public-only mode --------------------------------------------------

    @Test
    fun `public-only refuses literal private and loopback targets`() {
        val cases = mapOf(
            "http://127.0.0.1/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://10.1.2.3/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://192.168.0.1/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://172.16.4.4/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://169.254.169.254/latest/meta-data/" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://100.64.1.1/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://[fd00::1]/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            "http://0.0.0.0/x" to ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
        )
        for ((url, reason) in cases) {
            assertEquals(reason, ProbeTargetPolicy.checkResolved(url, publicOnly, failingResolver), url)
            // The same refusal is reachable without DNS, which is what save-time uses.
            assertEquals(reason, ProbeTargetPolicy.checkSyntax(url, publicOnly), url)
        }
    }

    @Test
    fun `public-only refuses internal hostnames`() {
        for (url in listOf(
            "http://localhost/x",
            "https://gateway.railway.internal/x",
            "http://db.internal/x",
            "http://printer.local/x",
        )) {
            assertEquals(
                ProbeTargetPolicy.REASON_INTERNAL_HOST,
                ProbeTargetPolicy.checkResolved(url, publicOnly, failingResolver),
                url,
            )
        }
    }

    @Test
    fun `public-only refuses a public name that resolves into the private network`() {
        // The rebinding-adjacent case a syntax check cannot see: an ordinary
        // name whose A record points at RFC1918.
        assertEquals(
            ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            ProbeTargetPolicy.checkResolved("https://inside.example.com/x", publicOnly, resolvingTo("10.0.0.9")),
        )
        // …and it is invisible to the DNS-free save-time check, by design.
        assertNull(ProbeTargetPolicy.checkSyntax("https://inside.example.com/x", publicOnly))
    }

    @Test
    fun `public-only allows an ordinary public target`() {
        assertNull(ProbeTargetPolicy.checkResolved("https://api.example.com/v1/health", publicOnly, resolvingTo("93.184.216.34")))
    }

    @Test
    fun `a target whose DNS is down is allowed through so the probe can fail honestly`() {
        assertNull(ProbeTargetPolicy.checkResolved("https://gone.example.com/x", publicOnly, failingResolver))
        assertNull(ProbeTargetPolicy.checkResolved("https://gone.example.com/x", publicOnly) { emptyList() })
    }

    @Test
    fun `a host built at runtime is refused under public-only but deferred at save time`() {
        assertEquals(
            ProbeTargetPolicy.REASON_DYNAMIC_HOST,
            ProbeTargetPolicy.checkResolved("https://\$\$host/x", publicOnly, failingResolver),
        )
        assertNull(ProbeTargetPolicy.checkSyntax("https://\$\$host/x", publicOnly))
    }

    @Test
    fun `a variable in the path does not make the host unjudgeable`() {
        assertNull(
            ProbeTargetPolicy.checkResolved("https://api.example.com/orders/\$\$id", publicOnly, resolvingTo("93.184.216.34")),
        )
        assertEquals(
            ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            ProbeTargetPolicy.checkResolved("https://api.example.com/orders/\$\$id", publicOnly, resolvingTo("10.0.0.1")),
        )
    }

    @Test
    fun `credentials in the authority do not hide the host`() {
        assertEquals(
            ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            ProbeTargetPolicy.checkResolved("http://user:pass@127.0.0.1:9000/x", publicOnly, failingResolver),
        )
    }

    // ---- script evaluation -------------------------------------------------

    @Test
    fun `every call in a script is judged, and the first refusal is reported`() {
        val script = """
            get("https://api.example.com/health").expect(status: 200)
            get("http://169.254.169.254/latest/meta-data/").expect(status: 200)
        """.trimIndent()
        val decision = ProbeTargetPolicy.evaluate(script, emptyMap(), publicOnly, resolvingTo("93.184.216.34"))
        assertFalse(decision.allowed)
        assertEquals("http://169.254.169.254/latest/meta-data/", decision.source)
        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, decision.reason)
    }

    @Test
    fun `an interpolated variable is substituted before the target is judged`() {
        // The whole reason a save-time check alone is not enough: the URL in the
        // script is harmless, the value behind it is not.
        val script = """get("${'$'}o_endpoint/health").expect(status: 200)"""
        val blocked = ProbeTargetPolicy.evaluate(
            script,
            mapOf("o_endpoint" to "http://192.168.1.1"),
            publicOnly,
            failingResolver,
        )
        assertFalse(blocked.allowed)
        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, blocked.reason)

        val allowed = ProbeTargetPolicy.evaluate(
            script,
            mapOf("o_endpoint" to "https://api.example.com"),
            publicOnly,
            resolvingTo("93.184.216.34"),
        )
        assertTrue(allowed.allowed)
    }

    @Test
    fun `a script with no calls is allowed`() {
        assertTrue(ProbeTargetPolicy.evaluate("# nothing here", emptyMap(), publicOnly, failingResolver).allowed)
    }

    @Test
    fun `save-time evaluation catches a literal internal target`() {
        val script = """post("http://10.0.0.1/admin", body: "{}")"""
        val decision = ProbeTargetPolicy.evaluateSyntax(script, emptyMap(), publicOnly)
        assertFalse(decision.allowed)
        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, decision.reason)
        // …and is a no-op for an install that allows private targets.
        assertTrue(ProbeTargetPolicy.evaluateSyntax(script, emptyMap(), allowPrivate).allowed)
    }

    @Test
    fun `every refusal is reported with its call, and the first is the one a save makes`() {
        val script = "get(\"https://example.com/\")\n" +
            "get(\"http://10.0.0.1/a\")\n" +
            "get(\"ftp://example.com/b\")"
        val all = ProbeTargetPolicy.refusalsBySyntax(script, emptyMap(), publicOnly)
        assertEquals(listOf(1, 2), all.map { it.callIndex })
        assertEquals(
            listOf(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, ProbeTargetPolicy.REASON_SCHEME),
            all.map { it.reason },
        )
        assertEquals(listOf("http://10.0.0.1/a", "ftp://example.com/b"), all.map { it.source })
        assertEquals(all.first(), ProbeTargetPolicy.evaluateSyntax(script, emptyMap(), publicOnly))
        assertTrue(ProbeTargetPolicy.refusalsBySyntax("get(\"https://example.com/\")", emptyMap(), publicOnly).isEmpty())
    }

    @Test
    fun `a refusal names the target as written, never as substituted`() {
        // The substituted URL may carry a decrypted value; only `source`, the
        // call as written, is kept to be logged.
        val script = """get("${'$'}p.baseUrl/health")"""
        val vars = mapOf("p_baseUrl" to "http://10.0.0.5")
        for (decision in listOf(
            ProbeTargetPolicy.evaluateSyntax(script, vars, publicOnly),
            ProbeTargetPolicy.evaluate(script, vars, publicOnly, failingResolver),
        )) {
            assertFalse(decision.allowed)
            assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, decision.reason)
            assertEquals("${'$'}p.baseUrl/health", decision.source)
            assertFalse(decision.toString().contains("10.0.0.5"))
        }
    }

    @Test
    fun `references are read as Lace and the scheduler read them`() {
        val vars = mapOf("p_baseUrl" to "https://example.org", "p_host" to "10.0.0.5", "s_sub" to "api")
        fun sub(url: String) = ProbeTargetPolicy.substituteVars(url, vars)
        // A scoped reference, and the identifier the scheduler rewrites it to,
        // read the same variable.
        assertEquals("https://example.org/health", sub("${'$'}p.baseUrl/health"))
        assertEquals("https://example.org/health", sub("${'$'}p_baseUrl/health"))
        // The braced form is replaced whole.
        assertEquals("https://example.org/health", sub("${'$'}{${'$'}p.baseUrl}/health"))
        assertEquals("https://api.example.com/", sub("https://${'$'}{${'$'}s.sub}.example.com/"))
        // One dot belongs to a scoped name; the rest is text.
        assertEquals("https://api.example.com/", sub("https://${'$'}s.sub.example.com/"))
        // Not references: braces with no `$` inside, and run-scope variables,
        // which nothing injected resolves.
        assertEquals("${'$'}{p.baseUrl}/health", sub("${'$'}{p.baseUrl}/health"))
        assertEquals("${'$'}${'$'}p_host/x", sub("${'$'}${'$'}p_host/x"))
    }

    @Test
    fun `a braced or scoped host is judged on its value at save and at dispatch`() {
        val vars = mapOf("p_host" to "http://10.0.0.5")
        for (script in listOf(
            """get("${'$'}{${'$'}p.host}/health")""",
            """get("${'$'}{${'$'}p_host}/health")""",
        )) {
            assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, ProbeTargetPolicy.evaluateSyntax(script, vars, publicOnly).reason, script)
            assertEquals(
                ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
                ProbeTargetPolicy.evaluate(script, vars, publicOnly, failingResolver).reason,
                script,
            )
        }
        // Braces without a `$` inside are not a reference: unresolved, refused.
        val unbraced = ProbeTargetPolicy.evaluateSyntax("""get("${'$'}{p.host}/health")""", vars, publicOnly)
        assertEquals(ProbeTargetPolicy.REASON_MALFORMED, unbraced.reason)
    }

    @Test
    fun `an escaped reference is the reference it interpolates as`() {
        // `\$p.host` lexes to `$p.host`, which Lace still interpolates.
        val script = """get("https://\${'$'}p.host/")"""
        val vars = mapOf("p_host" to "10.0.0.5")
        assertEquals(ProbeTargetPolicy.REASON_PRIVATE_ADDRESS, ProbeTargetPolicy.evaluateSyntax(script, vars, publicOnly).reason)
        assertEquals(
            ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            ProbeTargetPolicy.evaluate(script, vars, publicOnly, failingResolver).reason,
        )
        // An escaped quote does not end the URL early.
        assertEquals(
            listOf("https://example.com/a\"b"),
            ProbeTargetPolicy.targetUrls("""get("https://example.com/a\"b")"""),
        )
    }

    @Test
    fun `an authority with a backslash or a control character is refused`() {
        for (script in listOf(
            """get("https://1\t27.0.0.1/")""",
            """get("https://127.0.0.1\n/")""",
            """get("https://example.com\\@10.0.0.1/")""",
        )) {
            assertEquals(ProbeTargetPolicy.REASON_MALFORMED, ProbeTargetPolicy.evaluateSyntax(script, emptyMap(), publicOnly).reason, script)
            assertEquals(
                ProbeTargetPolicy.REASON_MALFORMED,
                ProbeTargetPolicy.evaluate(script, emptyMap(), publicOnly, resolvingTo("93.184.216.34")).reason,
                script,
            )
        }
        assertNull(ProbeTargetPolicy.hostOf("https://1\t27.0.0.1/"))
    }

    @Test
    fun `a numeric host that is not a canonical dotted quad is refused`() {
        for (host in listOf(
            "0177.0.0.1", "0x7f.0.0.1", "0x7f000001", "2130706433", "017700000001",
            "0177.1", "127.1", "127.0.1", "127.0.0.1.", "127.000.0.1", "256.1.1.1",
        )) {
            val script = """get("http://$host/")"""
            assertEquals(ProbeTargetPolicy.REASON_MALFORMED, ProbeTargetPolicy.evaluateSyntax(script, emptyMap(), publicOnly).reason, host)
            assertEquals(
                ProbeTargetPolicy.REASON_MALFORMED,
                ProbeTargetPolicy.evaluate(script, emptyMap(), publicOnly, resolvingTo("93.184.216.34")).reason,
                host,
            )
        }
        // Canonical addresses and names that merely start with digits are judged as before.
        assertTrue(ProbeTargetPolicy.evaluateSyntax("""get("http://93.184.216.34/")""", emptyMap(), publicOnly).allowed)
        assertTrue(ProbeTargetPolicy.evaluateSyntax("""get("https://1e100.net/")""", emptyMap(), publicOnly).allowed)
        assertTrue(ProbeTargetPolicy.evaluateSyntax("""get("https://123.example.com/")""", emptyMap(), publicOnly).allowed)
        assertEquals(
            ProbeTargetPolicy.REASON_PRIVATE_ADDRESS,
            ProbeTargetPolicy.evaluateSyntax("""get("http://127.0.0.1/")""", emptyMap(), publicOnly).reason,
        )
    }

    @Test
    fun `the host of a target is what a per-host check is made about`() {
        assertEquals("api.example.com", ProbeTargetPolicy.hostOf("https://API.Example.com:8443/health?x=1"))
        assertEquals("192.0.2.10", ProbeTargetPolicy.hostOf("http://192.0.2.10/"))
        assertEquals("2001:db8::1", ProbeTargetPolicy.hostOf("https://[2001:db8::1]/health"))
        // Nothing to make a check about: the host is assembled at runtime, or
        // the URL names no host at all.
        assertNull(ProbeTargetPolicy.hostOf("https://${'$'}o_endpoint/health"))
        assertNull(ProbeTargetPolicy.hostOf("not a url"))
    }

    @Test
    fun `the startup description names the setting an operator would change`() {
        assertTrue(ProbeTargetPolicy.describe(allowPrivate, null).contains("PROBE_TARGET_POLICY"))
        assertTrue(ProbeTargetPolicy.describe(publicOnly, "auto").contains("PROBE_TARGET_POLICY"))
    }
}
