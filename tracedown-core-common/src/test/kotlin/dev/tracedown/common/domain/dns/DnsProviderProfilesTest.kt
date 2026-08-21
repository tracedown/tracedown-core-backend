package dev.tracedown.common.domain.dns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DnsProviderProfilesTest {

    /** Name servers as the resolver returns them, from real delegations. */
    private val realDelegations = mapOf(
        "cloudflare" to listOf("kim.ns.cloudflare.com", "walt.ns.cloudflare.com"),
        "route53" to listOf("ns-1984.awsdns-56.co.uk", "ns-81.awsdns-10.com"),
        "godaddy" to listOf("ns37.domaincontrol.com", "ns38.domaincontrol.com"),
        "namecheap" to listOf("dns1.registrar-servers.com", "dns2.registrar-servers.com"),
        "digitalocean" to listOf("ns1.digitalocean.com", "ns2.digitalocean.com"),
        "google-cloud-dns" to listOf("ns-cloud-a1.googledomains.com", "ns-cloud-a2.googledomains.com"),
        "azure-dns" to listOf("ns1-01.azure-dns.com", "ns2-01.azure-dns.net"),
        "ionos" to listOf("ns-1and1.ui-dns.org", "ns-1and1.ui-dns.de"),
        "hetzner" to listOf("hydrogen.ns.hetzner.com", "oxygen.ns.hetzner.com"),
        "gandi" to listOf("ns-1-a.gandi.net", "ns-2-b.gandi.net"),
        "ovh" to listOf("dns108.ovh.net", "ns108.ovh.net"),
        "netlify" to listOf("dns1.p04.nsone.net", "ns02.netlifydns.com"),
        "vercel" to listOf("ns1.vercel-dns.com", "ns2.vercel-dns.com"),
        "squarespace" to listOf("ns01.squarespacedns.com", "ns02.squarespacedns.com"),
        "linode" to listOf("ns1.linode.com", "ns2.linode.com"),
        "porkbun" to listOf("curitiba.ns.porkbun.com", "fortaleza.ns.porkbun.com"),
        "dnsimple" to listOf("ns2.dnsimple-edge.net", "ns4.dnsimple-edge.org"),
        "name-com" to listOf("ns1jkl.name.com", "ns2mnp.name.com"),
    )

    @Test
    fun `every profile is recognised from its own delegation`() {
        for ((id, nameServers) in realDelegations) {
            assertEquals(id, DnsProviderProfiles.matching(nameServers)?.id, "for $id")
        }
    }

    @Test
    fun `every profile in the table is covered by this test`() {
        assertEquals(
            DnsProviderProfiles.ALL.map { it.id }.sorted(),
            realDelegations.keys.sorted(),
            "a profile without a delegation fixture is a profile nobody has checked",
        )
    }

    @Test
    fun `shared infrastructure names nobody`() {
        // NS1, UltraDNS and Akamai front several providers at once — matching
        // them would confidently send the user to the wrong dashboard.
        assertNull(DnsProviderProfiles.matching(listOf("dns1.p05.nsone.net", "dns2.p05.nsone.net")))
        assertNull(DnsProviderProfiles.matching(listOf("edns4.ultradns.biz", "edns4.ultradns.com")))
        assertNull(DnsProviderProfiles.matching(listOf("a4-64.akam.net", "a7-65.akam.net")))
        assertNull(DnsProviderProfiles.matching(listOf("gold.foundationdns.net")))
    }

    @Test
    fun `an unknown provider is simply unknown`() {
        assertNull(DnsProviderProfiles.matching(listOf("ns1.some-tiny-registrar.example")))
        assertNull(DnsProviderProfiles.matching(emptyList()))
    }

    @Test
    fun `zone-addressable links carry the zone, and the rest are stable pages`() {
        val cloudflare = assertNotNull(DnsProviderProfiles.byId("cloudflare"))
        assertEquals(
            "https://dash.cloudflare.com/?to=/:account/example.com/dns",
            cloudflare.dashboardUrl("example.com"),
        )
        assertEquals(
            "https://ap.www.namecheap.com/domains/domaincontrolpanel/example.com/advancedns",
            DnsProviderProfiles.byId("namecheap")?.dashboardUrl("example.com"),
        )
        // Every link we offer is https and absolute — these are opened in the
        // user's browser from a page we render.
        for (profile in DnsProviderProfiles.ALL) {
            val url = profile.dashboardUrl("example.com")
            assertTrue(url == null || url.startsWith("https://"), "bad link for ${profile.id}: $url")
        }
    }

    @Test
    fun `ids are unique, so a writer can never bind to two profiles`() {
        val ids = DnsProviderProfiles.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `zone candidates walk up to the registrable domain`() {
        assertEquals(
            listOf("api.staging.example.com", "staging.example.com", "example.com"),
            DnsProviderProfiles.zoneCandidates("api.staging.example.com"),
        )
        assertEquals(listOf("example.com"), DnsProviderProfiles.zoneCandidates("example.com"))
    }

    @Test
    fun `detection walks up to the delegated zone`() {
        val detection = DnsProviderProfiles.detect("api.staging.example.com") { candidate ->
            if (candidate == "example.com") realDelegations.getValue("namecheap") else emptyList()
        }
        assertEquals("namecheap", detection?.profile?.id)
        assertEquals("example.com", detection?.zone)
    }
}
