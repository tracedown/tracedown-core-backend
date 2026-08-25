package dev.tracedown.common.pfs

import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.*
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Table

/** Raised when a PFS filter/sort names a table or column not on the allowlist. */
class PfsValidationException(val code: String = ErrorCodes.UNKNOWN_COLUMN) : RuntimeException(code)

/**
 * Maps string table names to Exposed Table objects, and — crucially — gates
 * which columns a client may filter or sort on per table.
 *
 * Without the allowlist, any registered column of any joined table is
 * reachable: because several list endpoints join sensitive tables (e.g. the
 * org member list joins `users`) and apply filters at the SQL level before any
 * per-row permission check, an unprivileged member could filter on
 * `users.password_hash` / `users.totp_secret_encrypted` (or a variable's
 * ciphertext, a session token hash, an invite token, a domain challenge) and
 * boolean-extract those values one predicate at a time from the row counts.
 *
 * So the rule is deny-by-default: only the columns explicitly listed here are
 * filterable/sortable; every secret, credential, and token column is omitted
 * and therefore unreachable from ANY endpoint. This mirrors the per-table
 * column allowlist the admin surface uses, in the one shared path both use.
 */
object TableRegistry {
    private val tables: Map<String, Table> = mapOf(
        "workspaces" to Workspaces,
        "projects" to Projects,
        "services" to Services,
        "probe_results" to ProbeResults,
        "workspace_variables" to WorkspaceVariables,
        "project_variables" to ProjectVariables,
        "service_variables" to ServiceVariables,
        "org_groups" to OrgGroups,
        "org_user_groups" to OrgUserGroups,
        "org_users" to OrgUsers,
        "users" to Users,
        "org_domains" to OrgDomains,
        "webhook_deliveries" to WebhookDeliveries,
        "resource_webhook_access" to ResourceWebhookAccess,
        "org_audit_log" to OrgAuditLog,
        "notification_silences" to NotificationSilences,
        "sessions" to Sessions,
        "notification_templates" to NotificationTemplates,
        "project_notification_templates" to ProjectNotificationTemplates,
    )

    /**
     * Per-table filterable/sortable column allowlist (deny-by-default).
     *
     * Deliberately OMITTED, and thus never filterable/sortable:
     * - users: password_hash, totp_secret_encrypted, totp_secret_iv (+ totp
     *   enrollment timestamps and the enabled flag — no enrollment oracle)
     * - sessions: session_token_hash
     * - *_variables: value, value_iv (variable ciphertext/plaintext)
     * - org_users: invite_token
     * - org_domains: challenge (domain-verification token)
     * - services: script (bulk script-content extraction oracle)
     */
    private val allowedColumns: Map<String, Set<String>> = mapOf(
        "workspaces" to setOf(
            "id", "organization_id", "name", "is_active", "cover_image_url",
            "deleted", "deleted_at", "purge_after", "created_at",
        ),
        "projects" to setOf(
            "id", "workspace_id", "name", "is_active", "cover_image_url",
            "deleted", "deleted_at", "purge_after", "created_at",
        ),
        "services" to setOf(
            "id", "project_id", "name", "label", "schedule", "probe_mode",
            "queue_policy", "service_window", "save_response_bodies",
            "is_active", "last_status", "last_status_since",
            "last_status_consecutive", "last_run_id",
            "version", "deleted", "deleted_at", "purge_after", "created_at",
        ),
        "probe_results" to setOf(
            "id", "service_id", "probe_agent_id", "started_at", "status",
            "run_duration_ms", "total_response_ms", "ingress_bytes",
            "egress_bytes", "agent_egress_bytes", "request_count",
            "project_id", "workspace_id", "organization_id",
        ),
        "workspace_variables" to setOf(
            "id", "workspace_id", "created_by", "key", "secret", "encrypted",
            "system_type", "deleted", "deleted_at", "purge_after", "created_at", "updated_at",
        ),
        "project_variables" to setOf(
            "id", "project_id", "created_by", "key", "secret", "encrypted",
            "system_type", "deleted", "deleted_at", "purge_after", "created_at", "updated_at",
        ),
        "service_variables" to setOf(
            "id", "service_id", "created_by", "key", "secret", "encrypted",
            "system_type", "deleted", "deleted_at", "purge_after", "created_at", "updated_at",
        ),
        "org_groups" to setOf(
            "id", "organization_id", "name", "totp_required", "org_user_list",
            "org_settings", "org_domains", "org_webhooks", "org_notifications",
            "org_admin", "org_workspaces",
        ),
        "org_user_groups" to setOf("id", "org_user_id", "org_group_id"),
        "org_users" to setOf(
            "id", "organization_id", "user_id", "joined_at", "status", "is_active",
            "deleted", "deleted_at", "purge_after", "org_user_list", "org_settings",
            "org_domains", "org_webhooks", "org_notifications", "org_admin",
            "org_workspaces", "invited_at", "invited_by", "invite_expires_at",
            "last_invite_sent_at",
        ),
        "users" to setOf("id", "email", "display_name", "is_active", "created_at"),
        "org_domains" to setOf(
            "id", "organization_id", "domain", "verification_type", "status",
            "verified_at", "wildcard_enabled", "last_checked_at", "lapsed",
            "deleted", "deleted_at", "purge_after",
        ),
        "webhook_deliveries" to setOf(
            "id", "organization_id", "name", "label", "url", "method",
            "attempt_count", "deleted", "deleted_at", "purge_after", "created_at",
        ),
        "resource_webhook_access" to setOf(
            "id", "org_id", "resource_type", "resource_id", "webhook_delivery_id",
            "enabled", "created_at",
        ),
        "org_audit_log" to setOf(
            "id", "organization_id", "user_id", "action", "entity_type",
            "entity_id", "entity_display_name", "comment", "created_at",
        ),
        "notification_silences" to setOf(
            "id", "org_user_id", "workspace_id", "project_id", "service_id",
            "channel", "quiet_hours",
        ),
        "sessions" to setOf(
            "id", "user_id", "organization_id", "status", "totp_attempt_count",
            "ip_address", "user_agent", "expires_at", "last_active_at", "revoked", "created_at",
        ),
        "notification_templates" to setOf(
            "id", "organization_id", "name", "text", "deleted", "deleted_at",
            "purge_after", "created_at",
        ),
        "project_notification_templates" to setOf(
            "id", "notification_template_id", "project_id",
        ),
    )

    /** Resolves a table by name. Throws [PfsValidationException] on unknown table. */
    fun resolve(tableName: String): Table {
        return tables[tableName]
            ?: throw PfsValidationException()
    }

    /**
     * Resolves a column by table and name, enforcing the per-table allowlist.
     * Throws [PfsValidationException] for an unknown table, a column not on the
     * allowlist, or a column that does not exist on the table.
     */
    fun resolveColumn(tableName: String, columnName: String): Column<*> {
        val table = resolve(tableName)
        val allowed = allowedColumns[tableName] ?: throw PfsValidationException()
        if (columnName !in allowed) throw PfsValidationException()
        return table.columns.find { it.name == columnName }
            ?: throw PfsValidationException()
    }
}
