-- Forward migration
--
-- Data repair, no schema change.
--
-- Deleting a project or a workspace used to flip that one row only. Everything
-- underneath kept `deleted = false`, and the scheduler selects on `services`
-- alone, so those services went on probing: firing on schedule, writing results
-- and sending notifications for a project or workspace the owner had deleted
-- and could no longer see. Deployed databases are carrying that residue now.
--
-- Bring it in line with what a delete does from this version on: the children
-- inherit the parent's own `deleted_at`, so the history reads as though the
-- cascade had run at the time the user asked for it.
--
-- `purge_after` is deliberately NOT stamped here. Setting it would make this
-- backlog — including every probe result and stored response body under it —
-- physically erasable within one purge cycle of the upgrade, and that is an
-- irreversible decision an upgrade should not take on the operator's behalf.
-- Deletes made from this version on set it themselves. An operator who wants
-- the old backlog gone too can opt in afterwards with:
--
--   UPDATE services   SET purge_after = deleted_at WHERE deleted AND purge_after IS NULL;
--   UPDATE projects   SET purge_after = deleted_at WHERE deleted AND purge_after IS NULL;
--   UPDATE workspaces SET purge_after = deleted_at WHERE deleted AND purge_after IS NULL;
--
-- To see what this migration will touch before applying it:
--
--   SELECT count(*) FILTER (WHERE p.deleted OR w.deleted)      AS services_under_deleted_parent,
--          count(*) FILTER (WHERE o.deleted)                   AS services_under_deleted_org
--   FROM services s
--   JOIN projects p     ON s.project_id = p.id
--   JOIN workspaces w   ON p.workspace_id = w.id
--   JOIN organizations o ON w.organization_id = o.id
--   WHERE NOT s.deleted;

-- 1. Projects under a deleted workspace. First, so that step 2 sees the stamp
--    they inherit here and services pick up the same instant as their project.
UPDATE projects p
SET deleted = true,
    deleted_at = COALESCE(w.deleted_at, now())
FROM workspaces w
WHERE p.workspace_id = w.id
  AND w.deleted
  AND NOT p.deleted;

-- 2. Services under a deleted project or workspace — the probes that would
--    otherwise still be running.
UPDATE services s
SET deleted = true,
    deleted_at = COALESCE(p.deleted_at, w.deleted_at, now())
FROM projects p
JOIN workspaces w ON p.workspace_id = w.id
WHERE s.project_id = p.id
  AND (p.deleted OR w.deleted)
  AND NOT s.deleted;

-- 3. Services under a deleted organization. The organization delete path has
--    carried its services down for a while, but a database whose organization
--    was deleted before it did still has them running.
UPDATE services s
SET deleted = true,
    deleted_at = COALESCE(o.deleted_at, now())
FROM projects p
JOIN workspaces w ON p.workspace_id = w.id
JOIN organizations o ON w.organization_id = o.id
WHERE s.project_id = p.id
  AND o.deleted
  AND NOT s.deleted;

-- 4. Grafana scrape credentials for a deleted project. The integration
--    authenticates on its own `deleted` flag without consulting its project,
--    so the token kept working (against an empty scope) after the project went.
UPDATE grafana_integrations g
SET deleted = true,
    deleted_at = COALESCE(p.deleted_at, w.deleted_at, now())
FROM projects p
JOIN workspaces w ON p.workspace_id = w.id
WHERE g.project_id = p.id
  AND (p.deleted OR w.deleted)
  AND NOT g.deleted;
