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
 * Both accept -1 (or any value <= 0) to mean "use the global default", and a
 * global default of <= 0 means "never expire by age".
 */
interface RetentionConfig {

    /** Returns the result retention period in days for an organization, or -1 to use the global default. */
    fun resultRetentionDays(orgId: UUID): Int

    /**
     * Returns the response-body retention period in days for an organization,
     * or -1 to use the global default.
     *
     * A host that only distinguishes result windows need not override this: the
     * default sends every organization to the global body window, which is what
     * a deployment that has never configured one wants.
     */
    fun bodyRetentionDays(orgId: UUID): Int = -1

    /** Default: -1 (use global config value). */
    object Default : RetentionConfig {
        override fun resultRetentionDays(orgId: UUID): Int = -1
    }
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
