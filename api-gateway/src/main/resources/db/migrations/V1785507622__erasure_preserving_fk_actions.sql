-- Erasing a user account (or purging an organization) must neither destroy
-- records that legitimately outlive it nor be blocked by them. The links below
-- are data-preserving: the referenced row disappears, the referencing row stays
-- with the link cleared. Declaring ON DELETE SET NULL at the schema level
-- protects every deletion path, not just the background purge job.
--
-- Strictly-owned children (sessions, reset tokens, recovery codes, memberships,
-- group assignments) are deliberately NOT handled here — the purge job deletes
-- those explicitly, leaf-first, so their removal stays visible in one place.

-- Audit history is kept on user erasure and the actor link is anonymized here.
--
-- CORRECTION: the rest of the row is NOT identity-free. The writers put the
-- subject's email in entity_display_name and repeat it in the comment
-- (InviteController), and the account email change records both addresses in
-- diff. Worse, on an invite entry the actor is the INVITER, so clearing user_id
-- clears nothing about the invitee. Anonymizing the actor is therefore
-- necessary but not sufficient: erasure also has to reach the payload columns
-- of the rows *about* the erased person. The purge job does that, resolving the
-- subject from what the row already carries — entity_type/entity_id when the
-- entity IS the user, and the erased address itself everywhere else (see
-- PurgeJob.SCRUB_AUDIT_SUBJECT). Do not read the line below as "the rest of the
-- row is safe".
ALTER TABLE org_audit_log DROP CONSTRAINT org_audit_log_user_id_fkey;
ALTER TABLE org_audit_log ADD CONSTRAINT org_audit_log_user_id_fkey
    FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE SET NULL;

-- A membership survives the erasure of whoever issued the invite.
ALTER TABLE org_users DROP CONSTRAINT org_users_invited_by_fkey;
ALTER TABLE org_users ADD CONSTRAINT org_users_invited_by_fkey
    FOREIGN KEY (invited_by) REFERENCES users(id) ON DELETE SET NULL;

-- created_by is provenance, not ownership: keys, presets, variables and
-- bootstrap tokens belong to the organization and outlive their creator.
-- api_keys and org_rule_presets additionally drop NOT NULL so the link can
-- actually be cleared.
ALTER TABLE api_keys ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE api_keys DROP CONSTRAINT api_keys_created_by_fkey;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE org_rule_presets ALTER COLUMN created_by DROP NOT NULL;
ALTER TABLE org_rule_presets DROP CONSTRAINT org_rule_presets_created_by_fkey;
ALTER TABLE org_rule_presets ADD CONSTRAINT org_rule_presets_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE org_variables DROP CONSTRAINT org_variables_created_by_fkey;
ALTER TABLE org_variables ADD CONSTRAINT org_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE workspace_variables DROP CONSTRAINT workspace_variables_created_by_fkey;
ALTER TABLE workspace_variables ADD CONSTRAINT workspace_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE project_variables DROP CONSTRAINT project_variables_created_by_fkey;
ALTER TABLE project_variables ADD CONSTRAINT project_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE service_variables DROP CONSTRAINT service_variables_created_by_fkey;
ALTER TABLE service_variables ADD CONSTRAINT service_variables_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

ALTER TABLE agent_bootstrap_tokens DROP CONSTRAINT agent_bootstrap_tokens_created_by_fkey;
ALTER TABLE agent_bootstrap_tokens ADD CONSTRAINT agent_bootstrap_tokens_created_by_fkey
    FOREIGN KEY (created_by) REFERENCES users(id) ON DELETE SET NULL;

-- A session belongs to the user, not to the organization it currently has
-- selected. Purging an organization clears the selection; the session (and the
-- user's access to their other organizations) survives.
ALTER TABLE sessions DROP CONSTRAINT sessions_organization_id_fkey;
ALTER TABLE sessions ADD CONSTRAINT sessions_organization_id_fkey
    FOREIGN KEY (organization_id) REFERENCES organizations(id) ON DELETE SET NULL;

-- Same reasoning for the persisted org selection on the account itself.
ALTER TABLE users DROP CONSTRAINT fk_users_selected_org_id;
ALTER TABLE users ADD CONSTRAINT fk_users_selected_org_id
    FOREIGN KEY (selected_org_id) REFERENCES organizations(id) ON DELETE SET NULL;
