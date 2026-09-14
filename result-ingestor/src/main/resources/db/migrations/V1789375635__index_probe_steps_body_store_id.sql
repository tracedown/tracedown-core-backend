-- The partial index behind "is this store still holding bodies?" — the question
-- DELETE /body-stores/{id} asks on every call, and the one the forgetBodies form
-- and retention drive off.
--
-- It is alone in its own migration on purpose. probe_steps is the largest table
-- in the schema, and a plain CREATE INDEX holds a SHARE lock on it for the whole
-- scan, which blocks ingestion. An operator with a big enough table can build it
-- by hand before deploying this release:
--
--   CREATE INDEX CONCURRENTLY idx_probe_steps_body_store_id
--       ON probe_steps (body_store_id) WHERE body_store_id IS NOT NULL;
--
-- and IF NOT EXISTS then makes this migration a no-op. The column itself is
-- added by the migration before this one, so the statement above is valid to run
-- against a database that has had V1789121310 applied and this one not yet.
--
-- Why the migration cannot simply say CONCURRENTLY itself: Flyway takes a
-- PostgreSQL advisory lock inside a transaction and holds it across the whole
-- migration run, and CREATE INDEX CONCURRENTLY waits for every transaction that
-- can see the table to finish — including Flyway's own. It does not fail; it
-- hangs forever. (Verified against the version in gradle/libs.versions.toml;
-- `postgresql.transactional.lock=false` is the setting that would change it, and
-- it would change the locking of every other migration too.)
CREATE INDEX IF NOT EXISTS idx_probe_steps_body_store_id
    ON probe_steps (body_store_id)
    WHERE body_store_id IS NOT NULL;
