package dev.tracedown.common.alerts

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * A platform-raised alert being routed to its audience.
 *
 * [orgScoped] separates an alert about a single org's own resources (a probe
 * skipped for that org) from one about shared platform infrastructure (a
 * globally-shared agent's health) that no single org owns. A router uses it to
 * decide which alerts belong to a customer and which are the operator's concern.
 */
data class AlertContext(
    val alertType: String,
    val subject: String,
    /** The org an org-scoped alert concerns; null for a platform-infra alert. */
    val orgId: UUID?,
    val orgScoped: Boolean,
    val severity: String,
    val data: JsonObject?,
    /**
     * True when this raise re-observes a condition that was already alerted
     * on and has not cleared since — the agent is still down, still slow. The
     * org banner path wants every observation (it refreshes the episode a
     * banner is showing); a router keeping an operator feed wants only the
     * first, or the feed fills with one row per minute of the same outage.
     */
    val recurring: Boolean = false,
)

/**
 * Seam for redirecting platform alerts away from the org banners.
 *
 * By default nothing intercepts and alerts deliver as usual — self-hosted shows
 * every alert to its operator, who owns the agents. A host that operates shared
 * infrastructure for many tenants (whose agents' health is its own operational
 * concern, not each customer's) registers a router that claims those alerts, so
 * they are handled elsewhere instead of being written to customer banners.
 *
 * The registry is a process-wide singleton, populated once at startup by
 * whichever entrypoint runs the alert-raising services.
 */
object SystemAlertRouting {

    fun interface Router {
        /**
         * Returns true if the host has taken responsibility for this alert; the
         * default org delivery is then skipped. Returns false to let it deliver
         * as normal.
         */
        fun handle(ctx: AlertContext): Boolean
    }

    @Volatile
    private var router: Router? = null

    /** Installs the host router. Call once, at startup, before any alert is raised. */
    fun register(router: Router) {
        this.router = router
    }

    /** Whether a router claimed this alert, meaning it should not go to org banners. */
    fun handled(ctx: AlertContext): Boolean = router?.handle(ctx) ?: false
}
