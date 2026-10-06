-- Puts back uniqueness over every row, deleted or not.
--
-- With uniqueness over live rows only, a key can be held by one live variable
-- and any number of deleted ones; the old constraint allows one row per key in
-- all. So before it is restored, each key keeps one row and the others are
-- erased: the live one if there is one, otherwise the most recently deleted
-- (ties, and rows with no deletion date, settle on the larger id). Every row
-- erased here was already deleted and waiting for the purge job. The rows are
-- ranked once per table — not compared pairwise, which is quadratic in the
-- rows that share a key.
--
-- Agents cannot be erased — results point at them — so a decommissioned agent
-- that shares its slug with a live one, or with a more recently decommissioned
-- one, goes back to the old form instead: `<slug>-deleted-<id>`, the slug cut
-- so the whole fits in 64 characters. The id makes it unique among those rows;
-- a live agent that happens to carry exactly that slug would stop the undo.

SET LOCAL lock_timeout = '5s';

DELETE FROM org_variables
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (
                    PARTITION BY organization_id, key
                    ORDER BY deleted, deleted_at DESC NULLS LAST, id DESC
                ) AS rank
           FROM org_variables
     ) ranked
     WHERE ranked.rank > 1
 );
DROP INDEX IF EXISTS ux_org_variables_live_key;
DROP INDEX IF EXISTS idx_org_variables_organization_id;
ALTER TABLE org_variables ADD CONSTRAINT org_variables_organization_id_key_key UNIQUE (organization_id, key);

DELETE FROM workspace_variables
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (
                    PARTITION BY workspace_id, key
                    ORDER BY deleted, deleted_at DESC NULLS LAST, id DESC
                ) AS rank
           FROM workspace_variables
     ) ranked
     WHERE ranked.rank > 1
 );
DROP INDEX IF EXISTS ux_workspace_variables_live_key;
DROP INDEX IF EXISTS idx_workspace_variables_workspace_id;
ALTER TABLE workspace_variables ADD CONSTRAINT workspace_variables_workspace_id_key_key UNIQUE (workspace_id, key);

DELETE FROM project_variables
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (
                    PARTITION BY project_id, key
                    ORDER BY deleted, deleted_at DESC NULLS LAST, id DESC
                ) AS rank
           FROM project_variables
     ) ranked
     WHERE ranked.rank > 1
 );
DROP INDEX IF EXISTS ux_project_variables_live_key;
DROP INDEX IF EXISTS idx_project_variables_project_id;
ALTER TABLE project_variables ADD CONSTRAINT project_variables_project_id_key_key UNIQUE (project_id, key);

DELETE FROM service_variables
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (
                    PARTITION BY service_id, key
                    ORDER BY deleted, deleted_at DESC NULLS LAST, id DESC
                ) AS rank
           FROM service_variables
     ) ranked
     WHERE ranked.rank > 1
 );
DROP INDEX IF EXISTS ux_service_variables_live_key;
DROP INDEX IF EXISTS idx_service_variables_service_id;
ALTER TABLE service_variables ADD CONSTRAINT service_variables_service_id_key_key UNIQUE (service_id, key);

UPDATE probe_agents
   SET slug = left(slug, 55 - length(id::text)) || '-deleted-' || id::text
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (PARTITION BY slug ORDER BY deleted, id DESC) AS rank
           FROM probe_agents
     ) ranked
     WHERE ranked.rank > 1
 );
DROP INDEX IF EXISTS ux_probe_agents_live_slug;
ALTER TABLE probe_agents ADD CONSTRAINT probe_agents_slug_key UNIQUE (slug);
