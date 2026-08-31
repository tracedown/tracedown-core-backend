package dev.tracedown.gateway.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `rateLimit.trustedProxies` defaults to 1 — right for the bundled reverse proxy
 * and nothing else. With a platform edge in front of a proxy tier there are two
 * hops, and every request then resolves to the edge's address: one rate-limit key
 * for the whole deployment, so the 16th login in a minute from anybody 429s
 * everybody. Nothing about that is visible from the inside, which is what these
 * two signals are for — one at startup, one from the traffic itself.
 */
class ProxyChainObserverTest {

    // ── startup: the value was never configured ──

    @Test
    fun `production warns when the hop count was left at its default`() {
        val said = mutableListOf<String>()
        val warned = ProxyChainObserver.warnIfDefaultInProduction(
            production = true,
            explicitlySet = false,
            trustedProxies = 1,
            warn = { said += it },
        )
        assertTrue(warned)
        assertTrue(said.single().contains("TRUSTED_PROXIES"))
    }

    @Test
    fun `an explicitly configured hop count is not warned about`() {
        val said = mutableListOf<String>()
        assertFalse(
            ProxyChainObserver.warnIfDefaultInProduction(
                production = true,
                explicitlySet = true,
                trustedProxies = 2,
                warn = { said += it },
            ),
        )
        assertTrue(said.isEmpty())
    }

    @Test
    fun `dev is not warned about`() {
        val said = mutableListOf<String>()
        assertFalse(
            ProxyChainObserver.warnIfDefaultInProduction(
                production = false,
                explicitlySet = false,
                trustedProxies = 1,
                warn = { said += it },
            ),
        )
        assertTrue(said.isEmpty())
    }

    // ── runtime: the chains contradict the configured hop count ──

    /**
     * Replays a window of requests through the limiter's own IP derivation and
     * the observer, returning the distinct keys the limiter would have used and
     * whatever the observer said about them.
     */
    private fun replay(trustedProxies: Int, chains: List<String>): Pair<Set<String>, List<String>> {
        val said = mutableListOf<String>()
        val observer = ProxyChainObserver(
            trustedProxies = trustedProxies,
            sampleSize = chains.size,
            warn = { said += it },
        )
        val keys = mutableSetOf<String>()
        for (xff in chains) {
            val forwarded = xff.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            val ip = resolveClientIp(xff, "10.0.0.2", trustedProxies)
            keys += ip
            observer.observe(ip, forwarded)
        }
        return keys to said
    }

    /** Twenty distinct clients through an edge and then an inner proxy. */
    private val twoHopTraffic = (1..20).map { "203.0.113.$it, 198.51.100.7" }

    @Test
    fun `two real hops with the count set to one collapses every caller onto the edge`() {
        val (keys, _) = replay(trustedProxies = 1, chains = twoHopTraffic)
        assertEquals(setOf("198.51.100.7"), keys)
    }

    @Test
    fun `the collapse is reported, naming the variable to change`() {
        val (_, said) = replay(trustedProxies = 1, chains = twoHopTraffic)
        assertEquals(1, said.size)
        assertTrue(said.single().contains("TRUSTED_PROXIES=1"))
        assertTrue(said.single().contains("198.51.100.7"))
    }

    @Test
    fun `the same traffic with the correct hop count keys on the real clients and says nothing`() {
        val (keys, said) = replay(trustedProxies = 2, chains = twoHopTraffic)
        assertEquals(20, keys.size)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `one busy client behind one proxy is not mistaken for a misconfiguration`() {
        // Correctly configured, one caller: the resolved key is a constant, but
        // so is the claimed client, so there is nothing to report.
        val (keys, said) = replay(trustedProxies = 1, chains = List(20) { "203.0.113.9" })
        assertEquals(setOf("203.0.113.9"), keys)
        assertTrue(said.isEmpty())
    }

    @Test
    fun `a hop count of zero behind a real proxy collapses just as completely, and is reported`() {
        // 0 means "ignore X-Forwarded-For": correct for a gateway exposed
        // directly, catastrophic behind a proxy, and the same shape either way.
        val (keys, said) = replay(trustedProxies = 0, chains = twoHopTraffic)
        assertEquals(setOf("10.0.0.2"), keys)
        assertEquals(1, said.size)
        assertTrue(said.single().contains("TRUSTED_PROXIES=0"))
    }

    @Test
    fun `the report is rate limited to once per interval`() {
        val said = mutableListOf<String>()
        var now = 0L
        val observer = ProxyChainObserver(
            trustedProxies = 1,
            sampleSize = 10,
            warnEveryMillis = 60_000,
            clock = { now },
            warn = { said += it },
        )
        repeat(5) { window ->
            repeat(10) { i -> observer.observe("198.51.100.7", listOf("203.0.113.$window$i", "198.51.100.7")) }
            now += 1_000
        }
        assertEquals(1, said.size)

        now += 120_000
        repeat(10) { i -> observer.observe("198.51.100.7", listOf("203.0.113.9$i", "198.51.100.7")) }
        assertEquals(2, said.size)
    }

    @Test
    fun `observing tolerates anything a caller can put in the header`() {
        // It is a log line, not a control: a client can send whatever it likes,
        // and none of it may throw or change a decision.
        val observer = ProxyChainObserver(1, sampleSize = 4, warn = {})
        observer.observe("10.0.0.2", emptyList())
        observer.observe("10.0.0.2", listOf(""))
        observer.observe("10.0.0.2", listOf("not-an-ip", "::1"))
        observer.observe("10.0.0.2", List(50) { "203.0.113.$it" })
        observer.observe("10.0.0.2", listOf("203.0.113.1"))
    }
}
