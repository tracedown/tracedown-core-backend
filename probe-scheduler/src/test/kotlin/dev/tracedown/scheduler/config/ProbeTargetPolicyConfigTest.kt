package dev.tracedown.scheduler.config

import com.typesafe.config.ConfigFactory
import dev.tracedown.common.net.ProbeTargetPolicy
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The dispatch path applied `DomainPolicy` and nothing else: no address check
 * and no scheme check stood between a customer's script and whatever network the
 * agents run in. The gate now consults [ProbeTargetPolicy], and this pins the
 * configuration that decides how strict it is — including that the shipped
 * default keeps a self-hosted install probing its own network, which is a
 * primary use of the product and must not regress.
 */
class ProbeTargetPolicyConfigTest {

    private val shipped = ConfigFactory.parseResources("application.conf").resolve()

    @Test
    fun `the shipped configuration declares the policy and its override`() {
        assertEquals("auto", shipped.getString("probe.targetPolicy"))
        val raw = javaClass.classLoader.getResource("application.conf")!!.readText()
        assertTrue(
            raw.contains("PROBE_TARGET_POLICY"),
            "the policy must be settable from the environment, not only from the packaged file",
        )
    }

    @Test
    fun `the default keeps a self-hosted install probing its own network`() {
        // A fresh checkout, a dev stack, a docker compose run: none of them are
        // a production deployment, so private targets stay allowed and nothing
        // an existing install does stops working.
        assertEquals(
            ProbeTargetPolicy.Mode.ALLOW_PRIVATE,
            ProbeTargetPolicy.resolveMode(
                shipped.getString("probe.targetPolicy"),
                trustedDomainMode = shipped.getBoolean("scheduler.trustedDomainMode"),
                production = false,
            ),
        )
    }

    @Test
    fun `a production install that requires domain ownership gets the restricted mode`() {
        // trustedDomainMode off is the operator saying targets must be proven to
        // belong to the org writing the script — i.e. scripts arrive from
        // parties other than the operator. That is the install where a probe is
        // a request-forgery primitive against the agents' network.
        assertEquals(
            ProbeTargetPolicy.Mode.PUBLIC_ONLY,
            ProbeTargetPolicy.resolveMode(
                shipped.getString("probe.targetPolicy"),
                trustedDomainMode = false,
                production = true,
            ),
        )
    }

    @Test
    fun `an operator who only probes their own infrastructure keeps private targets in production`() {
        assertEquals(
            ProbeTargetPolicy.Mode.ALLOW_PRIVATE,
            ProbeTargetPolicy.resolveMode(
                shipped.getString("probe.targetPolicy"),
                trustedDomainMode = true,
                production = true,
            ),
        )
    }
}
