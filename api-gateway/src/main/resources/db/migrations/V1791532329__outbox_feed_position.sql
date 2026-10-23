-- Forward migration
--
-- What the key-authenticated API's event feed needs to read the outbox by
-- position without ever losing a row, and what the purge needs to say how far
-- it has gone. Every change is additive and rewrites no row: the columns are
-- added without a default and given one afterwards, so existing rows stay
-- NULL. A lock wait is bounded, so a busy outbox makes this fail fast rather
-- than queue every writer behind it.
SET LOCAL lock_timeout = '5s';

-- xid: the transaction that wrote the row. `seq` is handed out at INSERT and
-- becomes visible at COMMIT, so a reader can see seq 106 while 105 sits in a
-- transaction still open, and a reader that moved past 105 would never see it.
-- The feed instead reads in (xid, seq) order and only rows written below the
-- oldest transaction still open (pg_snapshot_xmin): every such transaction has
-- finished, and no later one can be given a lower xid, so nothing can appear
-- behind a reader's position afterwards.
ALTER TABLE outbox ADD COLUMN xid BIGINT;
ALTER TABLE outbox ALTER COLUMN xid SET DEFAULT pg_current_xact_id()::text::bigint;

-- organization_id: whose row it is, so the feed reads one organization's rows
-- instead of everyone's. Set by the emitters; NULL for platform rows and for
-- rows older than the column (the feed is new, so nothing needs them).
ALTER TABLE outbox ADD COLUMN organization_id UUID;

-- inserted_at: when the row was written, by the database's clock. created_at
-- is not that — a probe result's row carries the run's start — so a result
-- recorded long after its run looked old to the purge the moment it landed.
-- A timestamptz, so its age is the same whichever process's session (and time
-- zone) wrote it.
ALTER TABLE outbox ADD COLUMN inserted_at TIMESTAMPTZ;
ALTER TABLE outbox ALTER COLUMN inserted_at SET DEFAULT clock_timestamp();

-- outbox_retention: the last row the purge deleted, in the feed's order. The
-- purge does not trim a prefix — an unpublished probe result outlives newer
-- rows — so the first row left says nothing about what is gone; this does. A
-- feed cursor before it may have missed a row and is refused rather than
-- served with a silent hole. One row, only ever moved forward, by the purge in
-- its own DELETE statement.
CREATE TABLE outbox_retention (
    id          SMALLINT    PRIMARY KEY CHECK (id = 1),
    purged_xid  BIGINT      NOT NULL DEFAULT 0,
    purged_seq  BIGINT      NOT NULL DEFAULT 0,
    updated_at  TIMESTAMP   NOT NULL DEFAULT now()
);

-- No cursor exists yet — the feed is new with this migration — so nothing
-- handed out can lie before what earlier purges took; the mark starts at the
-- beginning and the next purge moves it.
INSERT INTO outbox_retention (id, purged_xid, purged_seq, updated_at) VALUES (1, 0, 0, now());
