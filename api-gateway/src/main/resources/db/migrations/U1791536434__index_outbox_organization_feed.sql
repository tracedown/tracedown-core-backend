-- Undo migration
--
-- The feed reads one organization's rows by a full walk of the outbox without
-- it. On a large outbox, prefer DROP INDEX CONCURRENTLY by hand, outside a
-- transaction.
-- A wait for the table's lock is bounded: behind a busy writer this fails
-- fast and can be run again, rather than queueing every writer behind it.
SET LOCAL lock_timeout = '5s';

DROP INDEX IF EXISTS idx_outbox_organization_feed;
