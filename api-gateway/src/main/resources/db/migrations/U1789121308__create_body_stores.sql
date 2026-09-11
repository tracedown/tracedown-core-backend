-- Undo: drop body store assignment and the stores themselves.
-- probe_steps.body_store_id (result-ingestor, V1789121310) must be undone first.
DROP INDEX IF EXISTS idx_agent_bootstrap_tokens_body_store_id;
ALTER TABLE agent_bootstrap_tokens DROP COLUMN IF EXISTS body_store_id;
DROP INDEX IF EXISTS idx_probe_agents_body_store_id;
ALTER TABLE probe_agents DROP COLUMN IF EXISTS body_store_id;
DROP TABLE IF EXISTS body_stores;
