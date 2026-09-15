package dev.tracedown.common.config

import java.util.UUID

/**
 * Platform-wide configurable defaults.
 *
 * Sensible defaults are provided via the [Default] objects. External modules
 * may replace these at startup to apply their own limits, filtering, etc.
 */
object PlatformDefaults {
    var orgConfig: OrgConfig = OrgConfig.Default
    var retentionConfig: RetentionConfig = RetentionConfig.Default
    var deliveryConfig: DeliveryConfig = DeliveryConfig.Default
}

/** Organization configuration — controls default group creation and similar org-level behavior. */
interface OrgConfig {

    /** Filters which default groups should be created for a new org. */
    fun filterDefaultGroups(groups: List<GroupDef>): List<GroupDef>

    data class GroupDef(
        val name: String,
        val users: Short,
        val settings: Short,
        val domains: Short,
        val webhooks: Short,
        val notifications: Short,
        val admin: Short,
        val workspaces: Short,
        /** Access levels for extension permission sections, keyed by section key. */
        val extraPerms: Map<String, Short> = emptyMap(),
    )

    /** Default: returns all groups unchanged. */
    object Default : OrgConfig {
        override fun filterDefaultGroups(groups: List<GroupDef>): List<GroupDef> = groups
    }
}

/**
 * Data retention configuration — how long probe results, and the response
 * bodies hanging off them, are kept.
 *
 * The two windows are independent settings, but not independent lifetimes: a
 * stored body is reachable only through its `probe_steps` row, so it can never
 * outlive the result that owns it. With both windows positive the effective
 * body lifetime is `min(body, result)`; with the body window off the body lives
 * exactly as long as its result.
 *
 * **How a value reads.** A retention value is a number of days:
 *
 *  - `> 0` — expire after that many days.
 *  - `< 0` — never expire by age.
 *  - `0` — not a value. It is refused where it is entered (the worker fails to
 *    start on `RESULT_RETENTION_DAYS=0` or `BODY_RETENTION_DAYS=0`), because
 *    "expire immediately" and "keep forever" are both plausible readings of it
 *    and one of them silently deletes everything.
 *  - `null` — no opinion for this organization: the global config value stands.
 *    `null` is an answer this seam gives, never a value an operator enters.
 *
 * A host that sets windows per organization (a plan, a contract) returns the
 * organization's own value and `null` only when nothing answers for it. The
 * job then resolves `organization value ?: global value`.
 */
interface RetentionConfig {

    /**
     * The result retention period in days for an organization, or `null` to use
     * the global config value. Negative means the organization's results never
     * expire by age.
     */
    fun resultRetentionDays(orgId: UUID): Int? = null

    /**
     * The response-body retention period in days for an organization, or `null`
     * to use the global config value. Negative means the organization's bodies
     * never expire by age — they still go when their result does.
     *
     * A host that only distinguishes result windows need not override this: the
     * default sends every organization to the global body window, which is what
     * a deployment that has never configured one wants.
     */
    fun bodyRetentionDays(orgId: UUID): Int? = null

    /** Default: no per-organization opinion — both windows come from config. */
    object Default : RetentionConfig
}

/** External delivery configuration — controls which delivery channels are available. */
interface DeliveryConfig {

    /** Whether the org can use external delivery channels (mobile push, etc.). */
    fun canUseExternalDelivery(orgId: UUID): Boolean

    /** Default: external delivery disabled. */
    object Default : DeliveryConfig {
        override fun canUseExternalDelivery(orgId: UUID): Boolean = false
    }
}
