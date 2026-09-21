-- Undo migration
--
-- Drops the delivery lease. The claim records nothing the product reads back —
-- a row's fate is still `published`, and the purge keys off that and the cursor
-- floor — so losing it costs no history. It does mean the consumer is
-- single-instance again: roll the service back to one replica BEFORE running
-- this, or two of them will race the same unpublished rows and double-deliver.
--
-- A row that was claimed but not yet published stays unpublished and is picked
-- up as ordinary work by whatever consumer runs next, exactly as it was before
-- the claim existed.
--
-- On a large outbox, prefer DROP INDEX CONCURRENTLY by hand, outside a
-- transaction.
DROP INDEX IF EXISTS idx_outbox_claimable;

ALTER TABLE outbox DROP COLUMN claimed_at;
ALTER TABLE outbox DROP COLUMN claimed_by;
