-- A deleted variable, or a decommissioned agent, no longer holds its name.
--
-- Deletes are soft: the row is flagged and kept — a variable until the purge
-- job erases it after the operator's retention, an agent for good, because
-- results point at it. But the four variable tables held the key unique over
-- every row, UNIQUE (<scope>_id, key), so a deleted variable kept its key taken
-- for the whole retention window: creating a variable with that key again
-- failed on the constraint and answered 500, and a script writing back a
-- metric under that key failed the insert and with it the whole result.
--
-- Uniqueness now holds among live rows only, the way `webhook_variables` has
-- always had it (V1787130363): the constraint goes, and a partial unique index
-- over `deleted = false` takes its place. A deleted row keeps its key exactly
-- as it was. Nothing that reads variables changes: every reader already
-- selects live rows, and a secret's ciphertext stays bound to the key it was
-- created under.
--
-- `probe_agents.slug` gets the same treatment. A decommissioned agent used to
-- give its slug up by being renamed to `<slug>-deleted-<epoch>`, which could
-- collide with itself (the same slug decommissioned twice in one second) and
-- with a live slug of that shape, and left history naming a slug nobody chose.
-- It now keeps its slug, and the slug is unique among live agents. Agents
-- decommissioned under the old form keep that form; where the slug was cut to
-- fit, the original cannot be recovered from it.
--
-- The constraint was also the only index on each variable table's scope
-- column, and a partial index serves only queries that ask for live rows. The
-- purge job's cascades and a parent row's foreign-key check look rows up by
-- scope regardless of `deleted`, so each table gets a plain index on it.
--
-- These tables are small and every probe dispatch reads them, so the indexes
-- are built in the migration's own transaction rather than concurrently, and
-- the migration gives up rather than queue behind a long transaction.

SET LOCAL lock_timeout = '5s';

ALTER TABLE org_variables DROP CONSTRAINT org_variables_organization_id_key_key;
CREATE UNIQUE INDEX ux_org_variables_live_key
    ON org_variables (organization_id, key) WHERE deleted = false;
CREATE INDEX idx_org_variables_organization_id ON org_variables (organization_id);

ALTER TABLE workspace_variables DROP CONSTRAINT workspace_variables_workspace_id_key_key;
CREATE UNIQUE INDEX ux_workspace_variables_live_key
    ON workspace_variables (workspace_id, key) WHERE deleted = false;
CREATE INDEX idx_workspace_variables_workspace_id ON workspace_variables (workspace_id);

ALTER TABLE project_variables DROP CONSTRAINT project_variables_project_id_key_key;
CREATE UNIQUE INDEX ux_project_variables_live_key
    ON project_variables (project_id, key) WHERE deleted = false;
CREATE INDEX idx_project_variables_project_id ON project_variables (project_id);

ALTER TABLE service_variables DROP CONSTRAINT service_variables_service_id_key_key;
CREATE UNIQUE INDEX ux_service_variables_live_key
    ON service_variables (service_id, key) WHERE deleted = false;
CREATE INDEX idx_service_variables_service_id ON service_variables (service_id);

ALTER TABLE probe_agents DROP CONSTRAINT probe_agents_slug_key;
CREATE UNIQUE INDEX ux_probe_agents_live_slug
    ON probe_agents (slug) WHERE deleted = false;
