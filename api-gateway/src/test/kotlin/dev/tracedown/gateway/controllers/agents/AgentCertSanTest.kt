package dev.tracedown.gateway.controllers.agents

import org.bouncycastle.asn1.DEROctetString
import org.bouncycastle.asn1.x509.GeneralName
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress

/**
 * The names an agent certificate carries: the slug the scheduler pins, then the
 * host in the agent's URI that the scheduler dials and its TLS client verifies.
 */
class AgentCertSanTest {

    private fun sans(slug: String, uri: String?): List<Pair<Int, String>> =
        CaService.subjectAltNames(slug, uri).map { it.tagNo to render(it) }

    private fun render(name: GeneralName): String = when (name.tagNo) {
        GeneralName.iPAddress -> InetAddress.getByAddress(DEROctetString.getInstance(name.name).octets).hostAddress
        else -> name.name.toString()
    }

    @Test
    fun `a named host is a DNS SAN next to the slug`() {
        assertEquals(
            listOf(GeneralName.dNSName to "runner", GeneralName.dNSName to "runner.example.com"),
            sans("runner", "https://runner.example.com:8443"),
        )
    }

    @Test
    fun `an IPv4 literal is an IP SAN`() {
        assertEquals(
            listOf(GeneralName.dNSName to "runner", GeneralName.iPAddress to "203.0.113.10"),
            sans("runner", "https://203.0.113.10:8443"),
        )
    }

    @Test
    fun `an IPv6 literal is an IP SAN without its brackets`() {
        assertEquals(
            listOf(GeneralName.dNSName to "runner", GeneralName.iPAddress to "2001:db8:0:0:0:0:0:10"),
            sans("runner", "https://[2001:db8::10]:8443"),
        )
    }

    @Test
    fun `a URI whose host is the slug carries the slug once`() {
        assertEquals(listOf(GeneralName.dNSName to "runner"), sans("runner", "https://runner:8443"))
        assertEquals(listOf(GeneralName.dNSName to "runner"), sans("runner", "https://RUNNER:8443"))
    }

    @Test
    fun `no URI, or one without a host, carries the slug only`() {
        assertEquals(listOf(GeneralName.dNSName to "runner"), sans("runner", null))
        assertEquals(listOf(GeneralName.dNSName to "runner"), sans("runner", "not a uri"))
    }
}
