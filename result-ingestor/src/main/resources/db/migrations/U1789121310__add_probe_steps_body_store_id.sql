-- Undo: forget which steps' bodies live in a body store.
DROP INDEX IF EXISTS idx_probe_steps_body_store_id;
ALTER TABLE probe_steps DROP COLUMN IF EXISTS body_store_id;
