package dev.tracedown.common.agents

import dev.tracedown.common.realtime.RealtimePublisher
import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Who a probe-agent event is addressed to.
 *
 * ## What Core knows
 *
 * In Core the probe fleet is platform infrastructure, not tenant data:
 * `probe_agents` has no owning organization, and every service may be pointed at
 * any agent. So a health or registration event genuinely concerns every
 * organization in the install, and there is no org to address it to. Core
 * publishes it under [GLOBAL] — a sentinel meaning *no organization owns this,
 * deliver it to every subscriber* — and the realtime fan-out treats that
 * sentinel, and only that sentinel, as exempt from its org filter.
 *
 * ## What Core deliberately does not know
 *
 * A deployment can give agents owners: a runner belonging to one organization, a
 * shared pool assigned to a subset. Under such a model the fleet feed as Core
 * publishes it is a leak — the event carries an agent's slug and liveness to
 * every authenticated session in the install, including organizations that have
 * no business knowing that runner exists. The subscribe-time membership check in
 * the realtime service cannot close that: the subscriber is a perfectly valid
 * member of *their* org; it is the event that names someone else's agent.
 *
 * That deployment installs an [Ownership] at startup. Every fleet publish then
 * addresses its real owning organizations, and the org filter the fan-out
 * already applies to every other channel does the rest. No per-client lookup, no
 * second gate — the sentinel simply stops being used.
 *
 * [AgentVisibility] is the read-side twin of this: the same question asked of a
 * REST list rather than a live event.
 *
 * ## Contract for an installed ownership
 *
 * - Called with an agent slug; returns the organizations that may learn about
 *   that agent. Returning [GLOBAL] restores Core's behaviour for that agent —
 *   the right answer for a genuinely shared runner.
 * - An **empty** result means nobody: the event is not published at all. That is
 *   deliberate. An installed ownership that cannot place an agent must not fall
 *   back to broadcasting it; a deployment wanting the shared-fleet answer says so
 *   by returning [GLOBAL].
 * - Called on the publish path, which may or may not hold an open transaction.
 *   An implementation that queries should open its own if it needs one, and
 *   should be cheap — this runs on every health check of every agent.
 * - Must not throw.
 */
object FleetAudience {

    /**
     * Addressed to no organization in particular: deliver to every subscriber of
     * the fleet channels. Core's only answer; a deployment with agent ownership
     * uses it for the agents it genuinely shares.
     */
    val GLOBAL: UUID = UUID(0, 0)

    /** The fleet channels: the detail feed and the always-subscribed summary. */
    private const val DETAIL_CHANNEL = "agents"
    private const val SUMMARY_CHANNEL = "agents:summary"

    /** Which organizations may learn about a given agent. */
    fun interface Ownership {
        fun orgsFor(agentSlug: String): Collection<UUID>
    }

    @Volatile
    private var ownership: Ownership? = null

    /**
     * Installs the deployment's ownership model. Called once at startup, before
     * anything publishes. A second call replaces the first (tests rely on this);
     * null restores Core's shared-fleet behaviour.
     */
    fun install(ownership: Ownership?) {
        this.ownership = ownership
    }

    /**
     * The organizations an event about [agentSlug] is addressed to. Without an
     * installed ownership this is always `[GLOBAL]` — the shared fleet.
     */
    fun forAgent(agentSlug: String): Collection<UUID> =
        ownership?.orgsFor(agentSlug) ?: listOf(GLOBAL)

    /**
     * Publishes one fleet event to both fleet channels, once per addressed
     * organization.
     *
     * Both channels carry the same payload: `agents:summary` is what every open
     * dashboard holds, `agents` is the detail view. Publishing them together
     * here is what keeps the two from drifting apart, which is how the sentinel
     * ended up hardcoded at three separate call sites in the first place.
     */
    fun publish(agentSlug: String, event: String, data: JsonObject) {
        for (orgId in forAgent(agentSlug)) {
            RealtimePublisher.publish(SUMMARY_CHANNEL, orgId, event, data)
            RealtimePublisher.publish(DETAIL_CHANNEL, orgId, event, data)
        }
    }
}
