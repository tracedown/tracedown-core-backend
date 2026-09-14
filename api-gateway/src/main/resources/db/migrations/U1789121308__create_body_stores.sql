-- Undo: drop body store assignment and the stores themselves.
-- probe_steps.body_store_id (result-ingestor, V1789121310) must be undone first —
-- read its undo script before running this one, it has a data warning.
DROP INDEX IF EXISTS idx_agent_bootstrap_tokens_body_store_id;
ALTER TABLE agent_bootstrap_tokens DROP COLUMN IF EXISTS body_store_id;
DROP INDEX IF EXISTS idx_probe_agents_body_store_id;
ALTER TABLE probe_agents DROP COLUMN IF EXISTS body_store_id;
DROP INDEX IF EXISTS idx_body_stores_organization_id;
DROP INDEX IF EXISTS body_stores_org_name_key;
DROP TABLE IF EXISTS body_stores;
