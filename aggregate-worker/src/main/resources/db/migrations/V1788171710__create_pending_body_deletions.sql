-- A stored response body is deleted from object storage first, then the
-- probe_steps row that points at it is deleted from the database. When the
-- storage call fails, the row was deleted anyway and the URI — the ONLY record
-- that the object exists — went with it. Nothing swept it afterwards and body
-- storage carries no lifecycle rule, so one transient outage during a purge or
-- a retention pass left third-party personal data (bodies are captured from the
-- probed endpoint) in the bucket permanently, outside both retention and
-- erasure.
--
-- This table is where that reference survives instead. A failed delete is
-- recorded here before the rows go, and BodyDeletionRetryJob keeps retrying
-- until the object is actually gone. Rows are never dropped on give-up —
-- dropping one recreates exactly the orphan this exists to prevent; a stuck row
-- is surfaced by attempt count instead.
CREATE TABLE pending_body_deletions (
    id              UUID          PRIMARY KEY,
    -- The storage URI (file:// or s3://) whose object still needs deleting.
    -- UNIQUE so re-recording the same failure is idempotent.
    storage_url     TEXT          NOT NULL UNIQUE,
    attempts        INTEGER       NOT NULL DEFAULT 1,
    last_error      TEXT,
    first_seen_at   TIMESTAMP(0)  NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMP(0)  NOT NULL DEFAULT now()
);

-- The retry job drains oldest-attempt-first.
CREATE INDEX idx_pending_body_deletions_attempt ON pending_body_deletions (last_attempt_at);
