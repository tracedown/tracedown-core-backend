-- Forward migration
--
-- The event feed's read: one organization's rows after a position, in (xid,
-- seq) order, below the oldest open transaction —
--
--   WHERE organization_id = ? AND (xid, seq) > (?, ?) AND xid < ?
--   ORDER BY xid, seq LIMIT ?
--
-- — answered as a range scan of this index, so a quiet organization reads its
-- own few rows and never walks everyone else's. Partial: platform rows and the
-- rows written before the column carry no organization and are never read by
-- the feed.
--
-- On its own, as the big-table rule asks: the outbox takes a row per probe
-- result. A plain CREATE INDEX holds a SHARE lock for the scan; the outbox is
-- trimmed to the retention window (7 days by default) so this is short, but an
-- operator with a large one can build it by hand before deploying, which makes
-- this statement a no-op:
--
--   CREATE INDEX CONCURRENTLY idx_outbox_organization_feed
--       ON outbox (organization_id, xid, seq)
--       WHERE organization_id IS NOT NULL;
--
-- The migration cannot say CONCURRENTLY itself: Flyway holds an advisory lock
-- inside a transaction across the whole run, and CREATE INDEX CONCURRENTLY
-- waits for every transaction that can see the table — including Flyway's own.
-- It does not fail, it hangs. See V1789461261.
-- A wait for the table's lock is bounded: behind a busy writer this fails
-- fast and can be run again, rather than queueing every writer behind it.
SET LOCAL lock_timeout = '5s';

CREATE INDEX IF NOT EXISTS idx_outbox_organization_feed
    ON outbox (organization_id, xid, seq)
    WHERE organization_id IS NOT NULL;
