-- Forward migration

-- Aggregation used a fixed lookback and no cursor: hourly rebuilt only the last
-- three hours, daily only the last three days. A worker down for longer than
-- that never built the buckets it missed, and once retention removed the raw
-- rows they were gone for good. A watermark makes the rollup resumable — the
-- job records how far it has aggregated and reaches back to that point on the
-- next run instead of assuming it was never away.
CREATE TABLE job_watermarks (
    job_name    VARCHAR(64)     PRIMARY KEY,
    -- Exclusive upper bound of the last window the job completed, minus its
    -- trailing re-check. Everything at or after it is still open for rebuild.
    watermark   TIMESTAMP       NOT NULL,
    updated_at  TIMESTAMP       NOT NULL DEFAULT now()
);

-- The all-agents rollup rows (probe_agent_id IS NULL) had no uniqueness at all:
-- idx_probe_aggregates_unique covers (service_id, probe_agent_id, bucket_start,
-- bucket_type), and Postgres treats NULLs as distinct, so it never constrained
-- them. That is why the rollup was written as delete-then-insert, and why two
-- writers could leave a bucket represented twice — which the weighted
-- percentile query then counts twice.
--
-- Any duplicates already present are collapsed to one row per bucket (keeping
-- the lowest id, arbitrary but deterministic) before the index goes on.
DELETE FROM probe_aggregates a
USING probe_aggregates b
WHERE a.probe_agent_id IS NULL
  AND b.probe_agent_id IS NULL
  AND a.service_id = b.service_id
  AND a.bucket_start = b.bucket_start
  AND a.bucket_type = b.bucket_type
  AND a.id > b.id;

CREATE UNIQUE INDEX idx_probe_aggregates_rollup_unique
    ON probe_aggregates (service_id, bucket_start, bucket_type)
    WHERE probe_agent_id IS NULL;
