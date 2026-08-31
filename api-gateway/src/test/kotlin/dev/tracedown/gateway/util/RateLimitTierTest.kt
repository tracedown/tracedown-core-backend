package dev.tracedown.gateway.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Which budget each path is metered against.
 *
 * The finding: the `/internal/` routes were exempt from rate limiting entirely, on a
 * publicly reachable gateway. Those routes are unauthenticated by design — they
 * carry a bootstrap token or proof of possession of an agent's private key — and
 * that argument is about *authentication*. It says nothing about cost: agent
 * registration runs a bcrypt-12 verification and signs an RSA CSR before it can
 * decide the caller was a stranger. The bundled reverse proxy publishes exactly
 * these paths, and a deployment may have no proxy at all.
 */
class RateLimitTierTest {

    @Test
    fun `agent enrolment and renewal are metered`() {
        assertEquals(RateLimiter.Tier.INTERNAL, rateLimitTierFor("/internal/agents/register"))
        assertEquals(RateLimiter.Tier.INTERNAL, rateLimitTierFor("/internal/agents/renew"))
        assertEquals(RateLimiter.Tier.INTERNAL, rateLimitTierFor("/internal/health/token/abc"))
    }

    @Test
    fun `enrolment does not spend the login budget`() {
        // A fleet coming up at once must not lock every user out of signing in,
        // and a stranger hammering enrolment must not lock the fleet out either.
        assertEquals(RateLimiter.Tier.INTERNAL, rateLimitTierFor("/internal/agents/register"))
        assertEquals(RateLimiter.Tier.AUTH, rateLimitTierFor("/api/v1/auth/login"))
    }

    @Test
    fun `the internal budget is large enough for a fleet and small enough to bound the cost`() {
        val config = RateLimitConfig(
            enabled = true,
            general = TierConfig(120, 60),
            auth = TierConfig(15, 60),
            trustedProxies = 1,
        )
        // Well above the auth tier (a bootstrapping fleet retries), well below
        // an unmetered endpoint (each request costs a bcrypt and an RSA sign).
        kotlin.test.assertTrue(config.internal.maxRequests > config.auth.maxRequests)
        kotlin.test.assertTrue(config.internal.maxRequests < config.general.maxRequests)
    }

    @Test
    fun `the sensitive auth paths keep the strict tier`() {
        assertEquals(RateLimiter.Tier.AUTH, rateLimitTierFor("/api/v1/auth/login"))
        assertEquals(RateLimiter.Tier.AUTH, rateLimitTierFor("/api/v1/auth/password-reset/request"))
        assertEquals(RateLimiter.Tier.AUTH, rateLimitTierFor("/api/v1/me/export"))
    }

    @Test
    fun `everything else is on the general tier`() {
        assertEquals(RateLimiter.Tier.GENERAL, rateLimitTierFor("/api/v1/services"))
        assertEquals(RateLimiter.Tier.GENERAL, rateLimitTierFor("/api/v1/auth/me"))
    }

    @Test
    fun `only liveness is exempt`() {
        // A liveness probe a limiter can refuse is not a liveness probe.
        assertNull(rateLimitTierFor("/ping"))
        assertEquals(RateLimiter.Tier.GENERAL, rateLimitTierFor("/pingpong"))
    }
}
