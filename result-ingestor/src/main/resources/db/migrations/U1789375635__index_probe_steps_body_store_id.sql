-- Undo: drop the body-store index on probe_steps. On a large table, prefer
-- DROP INDEX CONCURRENTLY by hand — run it outside a transaction.
DROP INDEX IF EXISTS idx_probe_steps_body_store_id;
