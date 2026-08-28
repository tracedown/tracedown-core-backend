package dev.tracedown.common.agents

import java.util.UUID

/**
 * Which probe agents a caller is allowed to see.
 *
 * ## What Core knows
 *
 * In Core the probe fleet is platform infrastructure, not tenant data:
 * `probe_agents` has no owning organization, every service may be pointed at
 * any agent, and the health feed is published globally on purpose. So for a
 * self-hosted install the correct rule is a PERMISSION gate, not an ownership
 * filter — the caller must be a member of an organization, and the fleet
 * surfaces that carry configuration (the admin list, the health-check history)
 * additionally require the org `settings` grant that governs infrastructure.
 * Every agent that passes the gate is genuinely theirs to see. Core installs no
 * filter and this object costs a null check.
 *
 * ## What Core deliberately does not know
 *
 * A deployment can layer ownership on top: an `organization_id` on the agent
 * row, private runners belonging to one org, assignment of shared agents to a
 * subset of orgs. Under such a model a permission gate is no longer enough —
 * a member with `settings` read in org A must not be handed org B's runner
 * slugs, labels or `agent_uri` addresses. That deployment installs a [Filter]
 * at startup and every fleet read narrows through it.
 *
 * Core keeps none of that vocabulary. It knows only that something may want to
 * narrow a list of slugs for a given (org, user), and asks.
 *
 * Reads are the surface this exists for. Fleet MUTATIONS already run through
 * the `agent.delete` / `agent.update` / `agent.bootstrap.create` interception
 * points, where a before-hook can refuse; a filter here is the read-side twin.
 *
 * ## Contract for an installed filter
 *
 * - Called with the caller's org and user and the slugs a Core query already
 *   found; returns the subset they may see. Returning all of them is the
 *   identity behaviour Core has without a filter.
 * - Called from inside an open transaction on the fleet read paths, so a filter
 *   backed by a query should assume an ambient transaction rather than opening
 *   its own.
 * - Must not throw. A gate that fails closed belongs in the permission check
 *   that runs before this, not here.
 */
object AgentVisibility {

    /** Narrows a set of agent slugs to those (orgId, userId) may see. */
    fun interface Filter {
        fun visible(orgId: UUID, userId: UUID, slugs: Collection<String>): Set<String>
    }

    @Volatile
    private var filter: Filter? = null

    /**
     * Installs the deployment's filter. Called once at startup, before routes
     * are serving. A second call replaces the first (tests rely on this).
     */
    fun install(filter: Filter?) {
        this.filter = filter
    }

    /**
     * True when a filter is installed — i.e. this deployment narrows the fleet
     * per caller. Lets a hot path (the realtime fan-out) skip the work entirely
     * on a plain Core install, where the answer is always "all of them".
     */
    fun isFiltered(): Boolean = filter != null

    /**
     * The subset of [slugs] visible to [userId] in [orgId]. All of them when no
     * filter is installed. An empty fleet is answered without asking a filter —
     * there is nothing to narrow, and a filter backed by a query should not be
     * made to run one to say so.
     */
    fun visible(orgId: UUID, userId: UUID, slugs: Collection<String>): Set<String> {
        if (slugs.isEmpty()) return emptySet()
        return filter?.visible(orgId, userId, slugs) ?: slugs.toSet()
    }

    /** Whether one specific agent is visible. */
    fun canSee(orgId: UUID, userId: UUID, slug: String): Boolean =
        filter?.visible(orgId, userId, listOf(slug))?.contains(slug) ?: true
}
