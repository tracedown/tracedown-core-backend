package dev.tracedown.gateway.data.domains

import kotlinx.serialization.Serializable

/**
 * Where to send someone so their own DNS provider can take the record.
 *
 * `dashboard` — a deep link straight to the page that edits this zone's
 * records, with the record still to paste. It needs no credential and nothing
 * registered, so it covers every provider recognisable from a delegation.
 * `none` — the provider is not one we recognise; the manual instructions stand
 * on their own.
 *
 * A host application may offer more (see the `domain-dns-setup` slot); this is
 * what every installation has.
 */
@Serializable
data class DnsHandoffDto(
    val mode: String,
    val providerName: String? = null,
    val url: String? = null,
) {
    companion object {
        val NONE = DnsHandoffDto(mode = "none")
    }
}
