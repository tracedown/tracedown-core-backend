package dev.tracedown.gateway.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A dashboard served from an origin other than the API's own could not call it
 * without CORS — and the client sends credentials, which rules out the easy
 * wildcard: a credentialed response must name an exact origin. These cover the
 * parsing of that origin list, the fact that configuring nothing is a valid and
 * quiet default, and the detection behind the cross-origin startup hint.
 */
class CorsSettingsTest {

    @Test
    fun `a plain origin parses into host-with-port and scheme`() {
        assertEquals("app.example.com" to "https", CorsSettings.parseOrigin("https://app.example.com"))
        assertEquals("localhost:5173" to "http", CorsSettings.parseOrigin("http://localhost:5173"))
    }

    @Test
    fun `several origins may be listed`() {
        val hosts = CorsSettings.parseOrigins("https://a.example.com, https://b.example.com:8443")
        assertEquals(
            listOf("a.example.com" to "https", "b.example.com:8443" to "https"),
            hosts,
        )
    }

    @Test
    fun `blank entries and surrounding whitespace are ignored`() {
        assertEquals(
            listOf("a.example.com" to "https"),
            CorsSettings.parseOrigins("  ,  https://a.example.com ,, "),
        )
    }

    @Test
    fun `an entry that is not a plain origin is refused at startup`() {
        // Each of these would register a host nobody meant and then silently
        // fail to match at runtime — the CORS problem that costs an afternoon.
        for (bad in listOf(
            "https://app.example.com/",
            "https://app.example.com/api",
            "app.example.com",
            "https://app.example.com?x=1",
            "ftp://app.example.com",
            "https://user@app.example.com",
        )) {
            val e = assertFailsWith<IllegalStateException>(bad) { CorsSettings.parseOrigin(bad) }
            assertTrue(e.message!!.contains(CorsSettings.ORIGINS_VAR))
        }
    }

    // ── configuring nothing ──

    @Test
    fun `an unset variable parses to an empty list rather than an error`() {
        // The self-hoster who has never heard of this variable: the gateway
        // starts, and simply emits no CORS headers.
        assertEquals(emptyList(), CorsSettings.parseOrigins(""))
        assertEquals(emptyList(), CorsSettings.parseOrigins("   "))
        assertFalse(CorsSettings(hosts = emptyList()).enabled)
    }

    @Test
    fun `listed origins turn CORS on`() {
        assertTrue(CorsSettings(hosts = listOf("a.example.com" to "https")).enabled)
    }

    // ── the cross-origin hint (a log line, never a refusal) ──

    @Test
    fun `a request from another origin looks cross-origin`() {
        assertTrue(CorsSettings.looksCrossOrigin("https://app.example.com", "api.example.com"))
        assertTrue(CorsSettings.looksCrossOrigin("http://localhost:5173", "localhost:20714"))
    }

    @Test
    fun `a same-origin deployment never trips the hint`() {
        // No Origin header at all, the app's own origin, and the same host in
        // different shapes: a bundled stack must stay silent.
        assertFalse(CorsSettings.looksCrossOrigin(null, "app.example.com"))
        assertFalse(CorsSettings.looksCrossOrigin("", "app.example.com"))
        assertFalse(CorsSettings.looksCrossOrigin("https://app.example.com", "app.example.com"))
        assertFalse(CorsSettings.looksCrossOrigin("https://APP.example.com", "app.example.com"))
        // A TLS-terminating proxy forwards plain HTTP, so the schemes differ
        // while the origin is the same place — comparing them would cry wolf on
        // every bundled deployment.
        assertFalse(CorsSettings.looksCrossOrigin("https://app.example.com", "app.example.com:443"))
        assertFalse(CorsSettings.looksCrossOrigin("http://app.example.com:80", "app.example.com"))
    }

    @Test
    fun `an origin that names no host is not a hint`() {
        // A sandboxed iframe or a file:// page sends "null"; garbage is garbage.
        // Neither names a host an operator could configure.
        assertFalse(CorsSettings.looksCrossOrigin("null", "app.example.com"))
        assertFalse(CorsSettings.looksCrossOrigin("not an origin", "app.example.com"))
        assertFalse(CorsSettings.looksCrossOrigin("https://app.example.com", null))
    }
}
