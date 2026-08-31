-- Restore plain (NO ACTION) foreign keys and the NOT NULL constraints.
--
-- NOTE on the forward file's audit-log comment: it claims the action/entity/diff
-- columns carry no account identity of their own. That is wrong — the writers
-- put the subject's email in entity_display_name, comment and diff, and on an
-- invite entry the actor is the inviter, so anonymizing the actor link is
-- necessary but not sufficient. The correction lives with the code that acts on
-- it (PurgeJob.SCRUB_AUDIT_SUBJECT in aggregate-worker); it cannot be added to
-- the forward file because that file is applied and checksummed — editing it
-- breaks Flyway validation on every existing database. Undo files are not
-- scanned by Flyway, which is why this note can live here.
--
-- Rows whose creator was erased under the forward migration carry NULL in
-- created_by; to restore NOT NULL on api_keys/org_rule_presets those rows are
-- reassigned to the owning organization's owner (the closest thing to a
-- responsible account that is guaranteed to exist).

ALTER TABLE org_audit_log DROP CONSTRAINT org_audit_log_user_id_fkey;
ALTER TABLE org_audit_log ADD CONSTRAINT org_audit_log_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE org_users DROP CONSTRAINT org_users_invited_by_fkey;
ALTER TABLE org_users ADD CONSTRAINT org_users_invited_by_fkey
    FOREIGN KEY (invited_by) REFERENCES users(id);

UPDATE api_keys ak SET created_by = o.owner_id
    FROM organizations o WHERE o.id = ak.organization_id AND ak.created_by IS NULL;
ALTER TABLE api_keys ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE api_keys DROP CONSTRAINT api_keys_created_by_fkey;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

UPDATE org_rule_presets rp SET created_by = o.owner_id
    FROM organizations o WHERE o.id = rp.organization_id AND rp.created_by IS NULL;
ALTER TABLE org_rule_presets ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE org_rule_presets DROP CONSTRAINT org_rule_presets_created_by_fkey;
ALTER TABLE org_rule_presets ADD CONSTRAINT org_rule_presets_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE org_variables DROP CONSTRAINT org_variables_created_by_fkey;
ALTER TABLE org_variables ADD CONSTRAINT org_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE workspace_variables DROP CONSTRAINT workspace_variables_created_by_fkey;
ALTER TABLE workspace_variables ADD CONSTRAINT workspace_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE project_variables DROP CONSTRAINT project_variables_created_by_fkey;
ALTER TABLE project_variables ADD CONSTRAINT project_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE service_variables DROP CONSTRAINT service_variables_created_by_fkey;
ALTER TABLE service_variables ADD CONSTRAINT service_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE agent_bootstrap_tokens DROP CONSTRAINT agent_bootstrap_tokens_created_by_fkey;
ALTER TABLE agent_bootstrap_tokens ADD CONSTRAINT agent_bootstrap_tokens_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id);

ALTER TABLE sessions DROP CONSTRAINT sessions_organization_id_fkey;
ALTER TABLE sessions ADD CONSTRAINT sessions_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations(id);

ALTER TABLE users DROP CONSTRAINT fk_users_selected_org_id;
ALTER TABLE users ADD CONSTRAINT fk_users_selected_org_id
    FOREIGN KEY (selected_org_id) REFERENCES organizations(id);
