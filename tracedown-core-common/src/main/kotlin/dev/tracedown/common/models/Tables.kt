package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table

/**
 * Every table object this module defines, and the one way to initialise them.
 *
 * Exposed tables are Kotlin objects, so each is a JVM class initialised on
 * first touch, and their foreign keys make that initialisation recursive:
 * `Users` references `Organizations`, which references `Users`; `Services`
 * references `ProbeResults`, which references `Services`. A single thread
 * walks such a cycle fine — the JVM lets a thread re-enter a class it is
 * already initialising. Two threads do not: when one starts on `Users` while
 * another starts on `Organizations`, each blocks on the other's
 * initialisation monitor forever. Nothing is logged, no exception is thrown,
 * and every later thread that needs either class queues up behind them.
 *
 * That is exactly what a service does at startup: it launches its jobs (or
 * serves its first requests) concurrently, and each touches a different
 * table first. On a deployment it left every maintenance job in the
 * aggregate-worker parked on those monitors for the life of the process, so
 * retention, session cleanup and token expiry silently never ran.
 *
 * [preload] touches every table from one thread before any of that
 * concurrency exists. [DatabaseFactory.init] calls it, so no service has to
 * remember. A module with tables of its own (a worker's watermark table, a
 * host overlay's tables) that reference each other in a cycle needs the same
 * treatment for its own list; tables that only reference these are safe once
 * these are initialised.
 */
object Tables {

    /** Every table object in this package. `Users` and `Organizations` lead: they are the cycle. */
    val all: List<Table> = listOf(
        Users,
        Organizations,
        AgentBootstrapTokens,
        AgentCertificates,
        AgentHealthChecks,
        ApiKeys,
        CaRoot,
        GrafanaIntegrations,
        NotificationLog,
        NotificationSilences,
        NotificationTemplates,
        OrgAuditLog,
        OrgDomains,
        OrgEncryptionKeys,
        OrgGroups,
        OrgRulePresets,
        OrgUserGroups,
        OrgUsers,
        OrgVariables,
        Outbox,
        OutboxCursors,
        PasswordResetTokens,
        PendingBodyDeletions,
        ProbeAgents,
        ProbeAggregates,
        ProbeResults,
        ProbeSteps,
        ProjectNotificationTemplates,
        ProjectVariables,
        Projects,
        ResourcePermissions,
        ResourceWebhookAccess,
        ServiceAllowedAgents,
        ServiceVariables,
        Services,
        Sessions,
        SystemAlertDismissals,
        SystemAlerts,
        TotpRecoveryCodes,
        WebhookDeliveries,
        WebhookVariables,
        WorkspaceVariables,
        Workspaces,
    )

    /**
     * Forces class initialisation of every table on the calling thread.
     * Building [all] already did that when this object was first touched;
     * this is the explicit, readable call sites use.
     */
    fun preload(): Int = all.size
}
