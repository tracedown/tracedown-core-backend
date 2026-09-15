-- Undo: drop the expirable-body index on probe_steps. Nothing outside the
-- retention body pass depends on it and it records nothing, so the drop is
-- safe on its own. On a large table, prefer DROP INDEX CONCURRENTLY by hand —
-- run it outside a transaction.
DROP INDEX IF EXISTS idx_probe_steps_expirable_body;
