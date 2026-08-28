package dev.tracedown.realtime.auth

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * DB-free coverage of the channel gate's routing decisions. Grant-based
 * decisions on resolvable resources are exercised by the integration suite (they
 * need the shared database); here we pin the paths that never touch the DB:
 *
 * - ungated non-resource channels (`org:`, `session:`) must be allowed through,
 * - a resource channel naming a malformed id is denied outright, before any
 *   lookup — a client can't smuggle a non-UUID past the gate, and
 * - the fleet feed is recognised as the fleet feed, which is what routes it to
 *   the membership check instead of falling through as "not gated here".
 *
 * `agents` used to be asserted here as ungated. It no longer is: subscribing to
 * the fleet feed now requires org membership, which is a database decision, so
 * that assertion moved to the integration suite.
 */
class ChannelAuthorizerTest {

    private val user = UUID.randomUUID()
    private val org = UUID.randomUUID()

    @Test
    fun `ungated non-resource channels are allowed through the gate`() {
        assertTrue(ChannelAuthorizer.canSubscribe(user, org, "org:$org"))
        assertTrue(ChannelAuthorizer.canSubscribe(user, org, "session:${UUID.randomUUID()}"))
    }

    @Test
    fun `resource channel with a malformed id is denied`() {
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "service:not-a-uuid"))
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "svc-edit:garbage"))
        assertFalse(ChannelAuthorizer.canSubscribe(user, org, "project:123"))
        assertFalse(ChannelAuthorizer.canRelay(user, org, "svc-edit:nope"))
    }

    @Test
    fun `the fleet feed and its variants are recognised as fleet channels`() {
        assertTrue(ChannelAuthorizer.isFleetChannel("agents"))
        assertTrue(ChannelAuthorizer.isFleetChannel("agents:summary"))
        assertTrue(ChannelAuthorizer.isFleetChannel("agents:all"))
    }

    @Test
    fun `channels that merely start with the same letters are not the fleet feed`() {
        // The old test was `startsWith("agents")`, which would also have matched
        // a future `agentsomething:` channel and exempted it from the org filter.
        assertFalse(ChannelAuthorizer.isFleetChannel("agentspoof:$org"))
        assertFalse(ChannelAuthorizer.isFleetChannel("org:$org"))
        assertFalse(ChannelAuthorizer.isFleetChannel("service:${UUID.randomUUID()}"))
    }
}
