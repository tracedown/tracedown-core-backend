package dev.tracedown.worker.jobs

import dev.tracedown.common.storage.BodyStorageClient
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Transaction
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.PurgeJob")

/** Executes raw SQL and returns the update count. */
private fun Transaction.execCount(sql: String): Long =
    connection.prepareStatement(sql, false).executeUpdate().toLong()

/**
 * Physically deletes soft-deleted rows whose purge_after timestamp has passed.
 *
 * Runs every 5 minutes. Each entity group (services, projects, workspaces,
 * organizations, users, and the individual leaf tables) is purged in its **own
 * transaction**: a failure in one group is logged and skipped, the remaining
 * groups still purge, and the failed group is retried on the next run. One bad
 * row must never stall erasure platform-wide.
 *
 * Within a group, deletes cascade leaf-first so FK constraints hold even where
 * children carry no purge_after of their own. Data-preserving links (audit-log
 * actor, created_by provenance, session/user org selection, last-run pointer,
 * notification-log sources) are cleared automatically by ON DELETE SET NULL
 * actions declared in the schema.
 *
 * Stored response bodies referenced by probe_steps rows are deleted from body
 * storage *before* the rows are purged, mirroring [RetentionJob]. A failing
 * storage backend never blocks the database purge; the URI it could not delete
 * is written to `pending_body_deletions` first, so the object stays referenced
 * after the row naming it is gone and [BodyDeletionRetryJob] can finish the
 * deletion later.
 *
 * Erasing an account reaches beyond its own rows: the audit entries *about* it
 * are kept but stripped of its identifiers ([SCRUB_AUDIT_SUBJECT], which finds
 * them by entity id and by the erased address itself), and the delivery log
 * addressed to it is deleted — neither is FK-linked to the account, so neither
 * was reached by the cascade alone.
 */
class PurgeJob(
    private val storageClient: BodyStorageClient = BodyStorageClient(),
    override val intervalSeconds: Long = 300L,
) : ScheduledJob {

    override val name = "PurgeJob"

    override suspend fun execute() {
        var totalDeleted = 0L
        var failedGroups = 0

        for (unit in purgeUnits) {
            try {
                totalDeleted += newSuspendedTransaction(Dispatchers.IO) { unit.purge(this) }
            } catch (e: Exception) {
                failedGroups++
                log.error(
                    "Purge of {} failed — other entities are unaffected; this group is retried next run",
                    unit.entity, e,
                )
            }
        }

        if (totalDeleted > 0 || failedGroups > 0) {
            log.info("Purge job completed: {} rows deleted, {} entity group(s) failed", totalDeleted, failedGroups)
        }
    }

    /** One independently-purged entity group. Runs inside its own transaction. */
    private class PurgeUnit(val entity: String, val purge: Transaction.() -> Long)

    private val purgeUnits: List<PurgeUnit> = buildList {
        // ── Crypto-shredding: purging orgs lose their data-encryption key FIRST ──
        // Deleting the org_encryption_keys row destroys the only copy of the
        // org's DEK, which renders every secret-variable ciphertext of the org
        // permanently undecryptable. Doing it in its own transaction, before
        // any data rows are touched, means the secrets are already unreadable
        // even if a later cascade step fails and leaves rows behind until the
        // next run. (The FK's ON DELETE CASCADE would remove the row anyway
        // when the organization hard-deletes — the explicit early delete is
        // the point.)
        add(PurgeUnit("org_encryption_keys") {
            execCount("DELETE FROM org_encryption_keys WHERE org_id IN ($PURGING_ORGS)")
        })

        // ── Leaf tables with their own purge_after (no dependents) ──
        for (table in listOf(
            "service_variables", "project_variables", "workspace_variables", "org_variables",
            "org_domains", "api_keys", "org_rule_presets", "grafana_integrations",
        )) {
            add(PurgeUnit(table) { execCount(PURGE_OWN.format(table)) })
        }

        // Webhook delivery configs: resource bindings die with the delivery.
        add(PurgeUnit("webhook_deliveries") {
            execCount(
                "DELETE FROM resource_webhook_access WHERE webhook_delivery_id IN " +
                    "(SELECT id FROM webhook_deliveries WHERE $PURGE_DUE)"
            ) + execCount(PURGE_OWN.format("webhook_deliveries"))
        })

        // Notification templates: project bindings die with the template.
        add(PurgeUnit("notification_templates") {
            execCount(
                "DELETE FROM project_notification_templates WHERE notification_template_id IN " +
                    "(SELECT id FROM notification_templates WHERE $PURGE_DUE)"
            ) + execCount(PURGE_OWN.format("notification_templates"))
        })

        // ── Container entities, each cascading its dependents ──
        add(PurgeUnit("services") {
            deleteStoredBodies(RESULTS_OF_PURGING_SERVICES)
            CASCADE_SERVICES.sumOf { execCount(it) }
        })
        add(PurgeUnit("projects") {
            deleteStoredBodies(RESULTS_OF_PURGING_PROJECTS)
            CASCADE_PROJECTS.sumOf { execCount(it) }
        })
        add(PurgeUnit("workspaces") {
            deleteStoredBodies(RESULTS_OF_PURGING_WORKSPACES)
            CASCADE_WORKSPACES.sumOf { execCount(it) }
        })
        add(PurgeUnit("organizations") {
            deleteStoredBodies(RESULTS_OF_PURGING_ORGS)
            CASCADE_ORGANIZATIONS.sumOf { execCount(it) }
        })

        // Users last: an organization purging in the same run is gone by now,
        // so its (also-purging) owner no longer trips the ownership guard.
        add(PurgeUnit("users") { purgeUsers() })
    }

    /**
     * Deletes the stored response bodies of every probe_steps row about to be
     * purged (steps of [purgingResults]), so storage objects never outlive the
     * rows pointing at them. Storage failures are logged and tolerated — a
     * broken bucket must not stop the database purge.
     */
    private fun Transaction.deleteStoredBodies(purgingResults: String) {
        val uris = mutableListOf<String>()
        exec(
            "SELECT response_body_storage_url FROM probe_steps " +
                "WHERE response_body_storage_url IS NOT NULL AND probe_result_id IN ($purgingResults)"
        ) { rs ->
            while (rs.next()) uris.add(rs.getString(1))
        }

        val failed = mutableListOf<Pair<String, String?>>()
        for (uri in uris) {
            try {
                storageClient.delete(uri)
            } catch (e: Exception) {
                failed.add(uri to e.message)
                log.error("Failed to delete stored response body {}: {}", uri, e.message)
            }
        }

        // The rows naming these objects are about to go, so a failure here used
        // to destroy the only reference to a live object — permanently, since
        // nothing sweeps body storage. Hand the URI to [PendingBodyDeletion]
        // first and [BodyDeletionRetryJob] finishes the job later.
        failed.forEach { (uri, error) -> PendingBodyDeletion.record(listOf(uri), error) }

        if (failed.isNotEmpty()) {
            log.error(
                "Purge: {} of {} stored bodies could not be deleted and were queued for retry",
                failed.size, uris.size,
            )
        }
    }

    /**
     * Purges soft-deleted user accounts, skipping (and loudly reporting) any
     * account still recorded as an organization's owner — erasing it would
     * orphan the organization. Ownership must be transferred or the
     * organization deleted first; the account stays until then.
     */
    private fun Transaction.purgeUsers(): Long {
        val blockedOwners = mutableListOf<String>()
        exec("SELECT id FROM users WHERE $PURGE_DUE AND id IN (SELECT owner_id FROM organizations)") { rs ->
            while (rs.next()) blockedOwners.add(rs.getString(1))
        }
        if (blockedOwners.isNotEmpty()) {
            log.error(
                "User purge skipped for {} account(s) that still own an organization: {} — " +
                    "transfer ownership or delete the organization first",
                blockedOwners.size, blockedOwners,
            )
        }

        val scrubbed = execCount(SCRUB_AUDIT_SUBJECT)
        if (scrubbed > 0) {
            log.info("User purge: scrubbed identifiers from {} audit entr(ies) about erased accounts", scrubbed)
        }

        return CASCADE_USERS.sumOf { execCount(it) }
    }

    companion object {
        private const val PURGE_DUE = "purge_after IS NOT NULL AND purge_after < now()"
        private const val PURGE_OWN = "DELETE FROM %s WHERE $PURGE_DUE"

        // ── Purge-scope subqueries ──
        private const val PURGING_SERVICES = "SELECT id FROM services WHERE $PURGE_DUE"
        private const val PURGING_PROJECTS = "SELECT id FROM projects WHERE $PURGE_DUE"
        private const val PURGING_WORKSPACES = "SELECT id FROM workspaces WHERE $PURGE_DUE"
        private const val PURGING_ORGS = "SELECT id FROM organizations WHERE $PURGE_DUE"

        private const val SERVICES_OF_PURGING_PROJECTS =
            "SELECT id FROM services WHERE project_id IN ($PURGING_PROJECTS)"
        private const val SERVICES_OF_PURGING_WORKSPACES =
            "SELECT s.id FROM services s JOIN projects p ON s.project_id = p.id " +
                "WHERE p.workspace_id IN ($PURGING_WORKSPACES)"
        private const val SERVICES_OF_PURGING_ORGS =
            "SELECT s.id FROM services s JOIN projects p ON s.project_id = p.id " +
                "JOIN workspaces w ON p.workspace_id = w.id WHERE w.organization_id IN ($PURGING_ORGS)"

        private const val RESULTS_OF_PURGING_SERVICES =
            "SELECT id FROM probe_results WHERE service_id IN ($PURGING_SERVICES)"
        private const val RESULTS_OF_PURGING_PROJECTS =
            "SELECT id FROM probe_results WHERE service_id IN ($SERVICES_OF_PURGING_PROJECTS)"
        private const val RESULTS_OF_PURGING_WORKSPACES =
            "SELECT id FROM probe_results WHERE service_id IN ($SERVICES_OF_PURGING_WORKSPACES)"
        private const val RESULTS_OF_PURGING_ORGS =
            "SELECT id FROM probe_results WHERE organization_id IN ($PURGING_ORGS)"

        /** Service-level dependents, leaf-first, for the given service/result scope. */
        private fun serviceLevelCascade(services: String, results: String) = listOf(
            "DELETE FROM probe_steps WHERE probe_result_id IN ($results)",
            "DELETE FROM probe_results WHERE id IN ($results)",
            "DELETE FROM probe_aggregates WHERE service_id IN ($services)",
            "DELETE FROM service_allowed_agents WHERE service_id IN ($services)",
            "DELETE FROM notification_silences WHERE service_id IN ($services)",
            "DELETE FROM service_variables WHERE service_id IN ($services)",
            "DELETE FROM services WHERE id IN ($services)",
        )

        // ── Service cascade ──
        private val CASCADE_SERVICES =
            serviceLevelCascade(PURGING_SERVICES, RESULTS_OF_PURGING_SERVICES)

        // ── Project cascade ──
        private val CASCADE_PROJECTS =
            serviceLevelCascade(SERVICES_OF_PURGING_PROJECTS, RESULTS_OF_PURGING_PROJECTS) + listOf(
                "DELETE FROM notification_silences WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM project_variables WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM grafana_integrations WHERE project_id IN ($PURGING_PROJECTS)",
                "DELETE FROM projects WHERE id IN ($PURGING_PROJECTS)",
            )

        // ── Workspace cascade ──
        private val CASCADE_WORKSPACES =
            serviceLevelCascade(SERVICES_OF_PURGING_WORKSPACES, RESULTS_OF_PURGING_WORKSPACES) + listOf(
                """DELETE FROM notification_silences WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                """DELETE FROM project_variables WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                """DELETE FROM grafana_integrations WHERE project_id IN (
                    SELECT id FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)
                )""",
                "DELETE FROM projects WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM notification_silences WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM workspace_variables WHERE workspace_id IN ($PURGING_WORKSPACES)",
                "DELETE FROM workspaces WHERE id IN ($PURGING_WORKSPACES)",
            )

        // ── Organization cascade ──
        // sessions.organization_id and users.selected_org_id are cleared by
        // ON DELETE SET NULL when the organization row goes — sessions and
        // accounts belong to users, not to the organization.
        private val CASCADE_ORGANIZATIONS = listOf(
            // Delivery history is org-scoped and dies with the org. Removed
            // before results/services so their FK SET NULL actions don't churn
            // rows that are about to disappear anyway.
            "DELETE FROM notification_log WHERE organization_id IN ($PURGING_ORGS)",
            // Every silence hangs off a membership of the org; removing them
            // here also clears the ones scoped to the org's services, projects
            // and workspaces before those rows are deleted below.
            """DELETE FROM notification_silences WHERE org_user_id IN (
                SELECT id FROM org_users WHERE organization_id IN ($PURGING_ORGS)
            )""",
        ) + serviceLevelCascade(SERVICES_OF_PURGING_ORGS, RESULTS_OF_PURGING_ORGS) + listOf(
            """DELETE FROM project_variables WHERE project_id IN (
                SELECT p.id FROM projects p JOIN workspaces w ON p.workspace_id = w.id
                WHERE w.organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM grafana_integrations WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM projects WHERE workspace_id IN (
                SELECT id FROM workspaces WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM workspace_variables WHERE workspace_id IN (SELECT id FROM workspaces WHERE organization_id IN ($PURGING_ORGS))",
            "DELETE FROM workspaces WHERE organization_id IN ($PURGING_ORGS)",
            // Resource bindings before the delivery configs they point at.
            "DELETE FROM resource_webhook_access WHERE org_id IN ($PURGING_ORGS)",
            "DELETE FROM webhook_deliveries WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_variables WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_domains WHERE organization_id IN ($PURGING_ORGS)",
            // Group assignments before memberships and groups (either side blocks).
            """DELETE FROM org_user_groups WHERE
                org_user_id IN (SELECT id FROM org_users WHERE organization_id IN ($PURGING_ORGS))
                OR org_group_id IN (SELECT id FROM org_groups WHERE organization_id IN ($PURGING_ORGS))""",
            "DELETE FROM org_users WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_groups WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM resource_permissions WHERE org_id IN ($PURGING_ORGS)",
            "DELETE FROM api_keys WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM org_rule_presets WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM system_alert_dismissals WHERE alert_id IN (
                SELECT id FROM system_alerts WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM system_alerts WHERE organization_id IN ($PURGING_ORGS)",
            """DELETE FROM project_notification_templates WHERE notification_template_id IN (
                SELECT id FROM notification_templates WHERE organization_id IN ($PURGING_ORGS)
            )""",
            "DELETE FROM notification_templates WHERE organization_id IN ($PURGING_ORGS)",
            // Org-scoped audit history dies with the org.
            "DELETE FROM org_audit_log WHERE organization_id IN ($PURGING_ORGS)",
            "DELETE FROM organizations WHERE id IN ($PURGING_ORGS)",
        )

        // ── User cascade ──
        // Accounts still owning an organization are excluded (see purgeUsers).
        // Data-preserving links — org_audit_log.user_id (audit history is kept,
        // actor anonymized), org_users.invited_by and the created_by provenance
        // columns (resources outlive their creator) — are cleared by
        // ON DELETE SET NULL declared in the schema.
        private const val PURGEABLE_USERS =
            "SELECT id FROM users WHERE $PURGE_DUE AND id NOT IN (SELECT owner_id FROM organizations)"
        private const val MEMBERSHIPS_OF_PURGEABLE_USERS =
            "SELECT id FROM org_users WHERE user_id IN ($PURGEABLE_USERS)"
        private const val EMAILS_OF_PURGEABLE_USERS =
            "SELECT lower(email) FROM users WHERE $PURGE_DUE AND id NOT IN (SELECT owner_id FROM organizations)"

        /** The same accounts as [PURGEABLE_USERS], as text, to compare against entity_id. */
        private const val PURGEABLE_USER_IDS_TEXT =
            "SELECT id::text FROM users WHERE $PURGE_DUE AND id NOT IN (SELECT owner_id FROM organizations)"

        /**
         * A single regex alternation of every purging account's email address —
         * `alice@x\.dev|bob@y\.dev` — with every non-alphanumeric character
         * backslash-escaped so each address matches literally and nothing else.
         * Aggregated rather than joined so one statement strips *all* purging
         * addresses out of a comment that happens to name several. Yields one
         * row always; `pattern` is NULL when no account is purging.
         */
        private const val PURGEABLE_EMAIL_PATTERN =
            """SELECT string_agg(regexp_replace(email, '([^a-zA-Z0-9])', '\\\1', 'g'), '|') AS pattern """ +
                "FROM users WHERE $PURGE_DUE AND id NOT IN (SELECT owner_id FROM organizations)"

        /**
         * Erasure reaching *into* the kept audit rows.
         *
         * Anonymizing the actor is not enough, because the actor is often
         * someone else: an invite entry is written by the INVITER and carries the
         * invitee's email in `entity_display_name` and again in the comment, so
         * clearing `user_id` clears nothing about the person being erased. The
         * account email change records both addresses in the diff. All of that
         * outlived erasure, bounded only by an audit retention window an operator
         * can switch off entirely.
         *
         * The subject is resolved from what the row already carries — no second
         * link column, because the row already answers the question two ways:
         *
         *  1. `entity_type = 'user'` makes `entity_id` the subject's account id.
         *     Exact, and true of every entry whose entity IS a person.
         *  2. Everything else that names the person does so by spelling out
         *     their **email address** (the invite entry's entity is the invite;
         *     a group membership entry's entity is the group). The address is an
         *     exact string, still resolvable here because this runs before
         *     [CASCADE_USERS] deletes the `users` rows.
         *
         * What survives is the audit trail proper — organization, action, entity
         * type, the entity id, the timestamp and the actor — so "who did what,
         * when" is intact while the erased person's identifiers are gone. The
         * comment keeps its wording minus the address ("Invited alice@x.dev"
         * becomes "Invited"), and the diff keeps any non-identity field it
         * carried (an `isActive` flip, say), nulled outright once removing the
         * identity keys empties it.
         *
         * Not covered, deliberately: a row that names the person by display name
         * alone, with no address anywhere and a non-user entity, matches neither
         * rule. Group membership comments are of that shape — they carry the
         * account's UUID, which after this pass resolves to nothing.
         */
        private const val SCRUB_AUDIT_SUBJECT = """
            UPDATE org_audit_log a SET
                entity_display_name = NULL,
                -- Strip the address out rather than dropping the note: what the
                -- entry says happened is audit, the address in it is not.
                comment = CASE
                    WHEN a.comment IS NULL OR p.pattern IS NULL THEN a.comment
                    ELSE NULLIF(btrim(regexp_replace(a.comment, p.pattern, '', 'gi')), '')
                END,
                -- The `-` operator is only defined on a jsonb object; anything
                -- else here is payload we cannot inspect, so it goes entirely
                -- rather than raising and stalling the whole user purge.
                diff = CASE jsonb_typeof(a.diff)
                    WHEN 'object' THEN NULLIF(a.diff - 'email' - 'displayName' - 'name', '{}'::jsonb)
                    ELSE NULL
                END
            FROM ($PURGEABLE_EMAIL_PATTERN) p
            WHERE (a.entity_display_name IS NOT NULL OR a.comment IS NOT NULL OR a.diff IS NOT NULL)
              AND (
                    (a.entity_type = 'user' AND a.entity_id IN ($PURGEABLE_USER_IDS_TEXT))
                 OR (p.pattern IS NOT NULL AND (
                        a.entity_display_name ~* p.pattern
                     OR a.comment ~* p.pattern
                     OR a.diff::text ~* p.pattern
                    ))
              )
        """

        private val CASCADE_USERS = listOf(
            // Strictly-owned children of the account's memberships.
            "DELETE FROM notification_silences WHERE org_user_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            "DELETE FROM org_user_groups WHERE org_user_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            // Direct resource grants keyed to the account's memberships. The only
            // person-shaped principal is 'org_user' (principal_id = membership id);
            // these are not FK-linked, so they would otherwise dangle when only the
            // user purges but its orgs live on. Runs before org_users so
            // MEMBERSHIPS_OF_PURGEABLE_USERS still resolves. Org-scoped purges clear
            // these via the org cascade instead.
            "DELETE FROM resource_permissions WHERE principal_type = 'org_user' AND principal_id IN ($MEMBERSHIPS_OF_PURGEABLE_USERS)",
            // Delivery history addressed to the erased account. notification_log
            // carries the recipient's email address and has no user FK, so
            // nothing brought it into the account's erasure — it was cleared
            // only when the whole ORGANIZATION purged. An erased person's
            // address therefore survived in every org they had ever been
            // alerted in, bounded by a retention window that a `<= 0` setting
            // disables outright. The address is the join key here precisely
            // because the address is the personal datum being erased; this must
            // run while the users rows still exist to resolve it.
            "DELETE FROM notification_log WHERE lower(recipient) IN ($EMAILS_OF_PURGEABLE_USERS)",
            "DELETE FROM org_users WHERE user_id IN ($PURGEABLE_USERS)",
            // Strictly-owned credential and session material.
            "DELETE FROM sessions WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM password_reset_tokens WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM totp_recovery_codes WHERE user_id IN ($PURGEABLE_USERS)",
            "DELETE FROM users WHERE id IN ($PURGEABLE_USERS)",
        )
    }
}
