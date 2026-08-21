package dev.tracedown.common.domain.dns

/**
 * A DNS provider recognisable from a zone's name servers, and where its own UI
 * edits that zone's records.
 *
 * This is knowledge, not capability: a profile says "this zone is on Namecheap,
 * and its records are edited here", which is enough to send someone straight to
 * the right page. It costs one DNS lookup and no credential, so a provider we
 * have no other relationship with is still recognised.
 */
data class DnsProviderProfile(
    val id: String,
    val displayName: String,
    /**
     * Distinctive fragments of this provider's name-server hostnames. Shared
     * infrastructure (`nsone.net`, `ultradns`, `akam.net`) is deliberately
     * absent — it is used by several providers at once, so it would name the
     * wrong one.
     */
    val nameServerTokens: List<String>,
    /**
     * Builds the link to where this zone's records are edited. Some providers
     * address a zone directly; others only have a stable list page, which is
     * still one click closer than the front door. Null when there is no page
     * worth sending someone to, in which case the provider is still named.
     */
    private val dnsPage: ((zone: String) -> String)? = null,
) {
    fun dashboardUrl(zone: String): String? = dnsPage?.invoke(zone)

    fun matches(nameServers: List<String>): Boolean =
        nameServers.any { server -> nameServerTokens.any { server.contains(it) } }
}

/**
 * The providers we recognise. Adding one is a row here — detection and the
 * hand-off link both fall out of it, with no API client to write.
 *
 * Zone-addressable links are used only where the pattern is documented or
 * stable; everything else points at the provider's domain/DNS list, which is
 * correct for every account rather than plausible for some.
 */
object DnsProviderProfiles {

    val ALL: List<DnsProviderProfile> = listOf(
        // Cloudflare resolves `:account` from whoever is signed in, so the zone
        // name alone addresses the right page (documented deep-link format).
        DnsProviderProfile(
            id = "cloudflare",
            displayName = "Cloudflare",
            nameServerTokens = listOf(".ns.cloudflare.com"),
            dnsPage = { "https://dash.cloudflare.com/?to=/:account/$it/dns" },
        ),
        DnsProviderProfile(
            id = "route53",
            displayName = "Amazon Route 53",
            // ns-1984.awsdns-56.co.uk — the token sits mid-name, not at the end.
            nameServerTokens = listOf("awsdns-"),
            dnsPage = { "https://console.aws.amazon.com/route53/v2/hostedzones" },
        ),
        DnsProviderProfile(
            id = "godaddy",
            displayName = "GoDaddy",
            nameServerTokens = listOf("domaincontrol.com", ".godaddy.com"),
            dnsPage = { "https://dcc.godaddy.com/control/dns" },
        ),
        DnsProviderProfile(
            id = "namecheap",
            displayName = "Namecheap",
            nameServerTokens = listOf("registrar-servers.com"),
            dnsPage = { "https://ap.www.namecheap.com/domains/domaincontrolpanel/$it/advancedns" },
        ),
        DnsProviderProfile(
            id = "digitalocean",
            displayName = "DigitalOcean",
            nameServerTokens = listOf(".digitalocean.com"),
            dnsPage = { "https://cloud.digitalocean.com/networking/domains/$it" },
        ),
        DnsProviderProfile(
            id = "google-cloud-dns",
            displayName = "Google Cloud DNS",
            nameServerTokens = listOf("googledomains.com"),
            dnsPage = { "https://console.cloud.google.com/net-services/dns/zones" },
        ),
        DnsProviderProfile(
            id = "azure-dns",
            displayName = "Azure DNS",
            nameServerTokens = listOf("azure-dns."),
            dnsPage = { "https://portal.azure.com/#browse/Microsoft.Network%2FdnsZones" },
        ),
        DnsProviderProfile(
            id = "ionos",
            displayName = "IONOS",
            nameServerTokens = listOf("ui-dns."),
            dnsPage = { "https://my.ionos.com/domains" },
        ),
        DnsProviderProfile(
            id = "hetzner",
            displayName = "Hetzner",
            nameServerTokens = listOf(".ns.hetzner.com", ".ns.hetzner.de"),
            dnsPage = { "https://dns.hetzner.com/" },
        ),
        DnsProviderProfile(
            id = "gandi",
            displayName = "Gandi",
            nameServerTokens = listOf("gandi.net", "gandi-ns.fr"),
            dnsPage = { "https://admin.gandi.net/domain" },
        ),
        DnsProviderProfile(
            id = "ovh",
            displayName = "OVH",
            nameServerTokens = listOf(".ovh.net"),
            dnsPage = { "https://www.ovh.com/manager/" },
        ),
        DnsProviderProfile(
            id = "netlify",
            displayName = "Netlify",
            nameServerTokens = listOf("netlifydns.com"),
            dnsPage = { "https://app.netlify.com/" },
        ),
        DnsProviderProfile(
            id = "vercel",
            displayName = "Vercel",
            nameServerTokens = listOf("vercel-dns.com"),
            dnsPage = { "https://vercel.com/dashboard/domains" },
        ),
        DnsProviderProfile(
            id = "squarespace",
            displayName = "Squarespace",
            nameServerTokens = listOf("squarespacedns.com"),
            dnsPage = { "https://account.squarespace.com/domains" },
        ),
        DnsProviderProfile(
            id = "linode",
            displayName = "Linode",
            nameServerTokens = listOf(".linode.com"),
            dnsPage = { "https://cloud.linode.com/domains" },
        ),
        DnsProviderProfile(
            id = "porkbun",
            displayName = "Porkbun",
            nameServerTokens = listOf(".porkbun.com"),
            dnsPage = { "https://porkbun.com/account/domains" },
        ),
        DnsProviderProfile(
            id = "dnsimple",
            displayName = "DNSimple",
            nameServerTokens = listOf("dnsimple.com", "dnsimple-edge."),
            dnsPage = { "https://dnsimple.com/dashboard" },
        ),
        DnsProviderProfile(
            id = "name-com",
            displayName = "Name.com",
            nameServerTokens = listOf(".name.com"),
            dnsPage = { "https://www.name.com/account/domain" },
        ),
    )

    fun byId(id: String): DnsProviderProfile? = ALL.firstOrNull { it.id == id }

    /** The profile whose name servers serve this zone, if we recognise them. */
    fun matching(nameServers: List<String>): DnsProviderProfile? =
        ALL.firstOrNull { it.matches(nameServers) }

    /** A recognised provider, and the zone of theirs the domain sits in. */
    data class Detection(val profile: DnsProviderProfile, val zone: String)

    /**
     * The provider whose name servers serve [domain] — or a zone above it, so
     * `api.example.com` is recognised from `example.com`'s delegation. One DNS
     * lookup per level, no credential, so it works for providers we have no
     * other relationship with.
     */
    fun detect(
        domain: String,
        nameServers: (String) -> List<String> = TxtLookup::nsOrEmpty,
    ): Detection? {
        for (candidate in zoneCandidates(domain)) {
            val servers = nameServers(candidate)
            if (servers.isEmpty()) continue
            val match = matching(servers) ?: continue
            return Detection(match, candidate)
        }
        return null
    }

    /** `api.example.com` → `api.example.com`, `example.com`. */
    fun zoneCandidates(domain: String): List<String> {
        val labels = domain.lowercase().trim('.').split('.')
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }
}
