-- Undo migration
--
-- Drops what the event feed and the purge's mark read. Roll the
-- aggregate-worker back first — its purge writes the mark and ages rows by
-- inserted_at in the same statement as its DELETE, and fails without them —
-- and the gateway too, whose feed answers 500 without them. Every feed cursor
-- handed out before is worthless afterwards: the order it names is gone, and
-- if this is applied again the mark starts over at the beginning, so an old
-- cursor would be accepted without anything to say whether rows were purged
-- past it. Tell feed readers to start again from a fresh cursor.
SET LOCAL lock_timeout = '5s';

DROP TABLE IF EXISTS outbox_retention;

ALTER TABLE outbox DROP COLUMN IF EXISTS inserted_at;
ALTER TABLE outbox DROP COLUMN IF EXISTS organization_id;
ALTER TABLE outbox DROP COLUMN IF EXISTS xid;
