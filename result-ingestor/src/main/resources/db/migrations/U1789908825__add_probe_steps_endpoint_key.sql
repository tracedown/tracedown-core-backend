-- Drops the endpoint key from probe_steps.
--
-- Nothing outside the database records it: a key is re-derivable from the
-- script at any time, and the aggregate rows that were built from it
-- (probe_step_aggregates) keep their own copy. So this loses only the
-- attribution of already-ingested steps, and an aggregation run after the
-- rollback falls back to deriving keys from `request_url`, exactly as it does
-- for rows ingested before the column existed.
ALTER TABLE probe_steps DROP COLUMN IF EXISTS endpoint_key;
