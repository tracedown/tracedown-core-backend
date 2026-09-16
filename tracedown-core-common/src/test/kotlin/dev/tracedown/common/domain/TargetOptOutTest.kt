package dev.tracedown.common.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A host that publishes `_tracedown-noprobe` is not probed. What matters here
 * is which names get asked — too few and an apex opt-out never covers the
 * subdomain actually being probed; too many and one dispatch becomes a chain of
 * DNS round-trips, or a question about somebody else's zone.
 */
class TargetOptOutTest {

    private fun lookupOf(vararg published: String): (String) -> List<String> {
        val names = published.map { TargetOptOut.recordName(it) }.toSet()
        return { name -> if (name in names) listOf("") else emptyList() }
    }

    @Test
    fun `the record is asked for under one prefix`() {
        assertEquals("_tracedown-noprobe.example.com", TargetOptOut.recordName("example.com"))
    }

    @Test
    fun `the exact host is asked first, then each parent`() {
        assertEquals(
            listOf("api.eu.example.com", "eu.example.com", "example.com"),
            TargetOptOut.candidates("api.eu.example.com"),
        )
    }

    @Test
    fun `the walk stops at a registrable name`() {
        assertEquals(listOf("example.com"), TargetOptOut.candidates("example.com"))
        // Never `com`: that is a question about a registry's zone, not a target's.
        assertFalse(TargetOptOut.candidates("api.example.com").contains("com"))
    }

    @Test
    fun `no host is asked about more than three names`() {
        val deep = TargetOptOut.candidates("a.b.c.d.e.example.com")
        assertEquals(TargetOptOut.MAX_LOOKUPS, deep.size)
        assertEquals(listOf("a.b.c.d.e.example.com", "b.c.d.e.example.com", "c.d.e.example.com"), deep)
    }

    @Test
    fun `a host is normalised before it is asked about`() {
        assertEquals(listOf("example.com"), TargetOptOut.candidates("EXAMPLE.com."))
    }

    @Test
    fun `an address literal is never looked up`() {
        assertEquals(emptyList(), TargetOptOut.candidates("192.0.2.10"))
        assertEquals(emptyList(), TargetOptOut.candidates("2001:db8::1"))
        assertFalse(TargetOptOut.published("192.0.2.10") { error("no lookup may happen for an address") })
    }

    @Test
    fun `a single-label host is asked about as it stands`() {
        assertEquals(listOf("localhost"), TargetOptOut.candidates("localhost"))
    }

    @Test
    fun `a record on the host itself opts it out`() {
        assertTrue(TargetOptOut.published("api.example.com", lookupOf("api.example.com")))
    }

    @Test
    fun `a record at the apex covers what is under it`() {
        assertTrue(TargetOptOut.published("api.eu.example.com", lookupOf("example.com")))
    }

    @Test
    fun `a record deeper than the target does not opt the target out`() {
        assertFalse(TargetOptOut.published("example.com", lookupOf("api.example.com")))
    }

    @Test
    fun `a host with no record is probed`() {
        assertFalse(TargetOptOut.published("api.example.com", lookupOf("other.example")))
    }

    @Test
    fun `a record found on the host costs one lookup`() {
        val asked = mutableListOf<String>()
        val lookup: (String) -> List<String> = { name ->
            asked.add(name)
            listOf("")
        }
        assertTrue(TargetOptOut.published("api.eu.example.com", lookup))
        assertEquals(listOf("_tracedown-noprobe.api.eu.example.com"), asked)
    }

    @Test
    fun `a resolver that fails means no record, never a stopped probe`() {
        assertFalse(TargetOptOut.published("api.example.com") { throw RuntimeException("SERVFAIL") })
    }
}
