-- Per-endpoint rollups of probe_steps, beside the per-service rollups in
-- probe_aggregates.
--
-- One row per (service, bucket, endpoint, status code). Everything stored is a
-- count or a sum, so a row is additive and a daily bucket is the same
-- computation over a wider range — which is what lets both the hourly and the
-- daily job re-derive any bucket at will (ON CONFLICT DO UPDATE) and stay
-- idempotent under the overlapping windows they run.
--
-- The agent is deliberately NOT part of the key. Per-region latency is already
-- served by probe_aggregates; splitting this table by agent as well would
-- multiply it by the size of the fleet for a breakdown nothing asks for.
CREATE TABLE probe_step_aggregates (
    id              UUID            PRIMARY KEY,
    service_id      UUID            NOT NULL REFERENCES services(id),
    bucket_start    TIMESTAMP(0)    NOT NULL,
    bucket_type     VARCHAR(8)      NOT NULL CHECK (bucket_type IN ('hourly', 'daily')),
    -- "<METHOD> <template>" — the call as the script writes it, or the
    -- "* <normalised url>" fallback for steps that carry no key of their own.
    endpoint_key    VARCHAR(210)    NOT NULL,
    -- 0 means the call produced no HTTP response at all: DNS failure, connect
    -- or TLS failure, timeout. A real status code is never 0, so the two do not
    -- collide, and "no response" stays countable instead of vanishing.
    status_code     SMALLINT        NOT NULL,
    call_count      INTEGER         NOT NULL,
    -- Calls that carry phase timings, i.e. the denominator for the sums below.
    -- A call that never reached the server has none, so it counts toward
    -- call_count and toward its status code, but not toward an average.
    timed_count     INTEGER         NOT NULL,
    sum_dns_ms      BIGINT          NOT NULL,
    sum_connect_ms  BIGINT          NOT NULL,
    sum_tls_ms      BIGINT          NOT NULL,
    sum_ttfb_ms     BIGINT          NOT NULL,
    sum_transfer_ms BIGINT          NOT NULL,
    sum_response_ms BIGINT          NOT NULL,
    sum_size_bytes  BIGINT          NOT NULL,
    -- Calls that reported a response size; its own denominator, because a
    -- response can be timed without its size being known.
    sized_count     INTEGER         NOT NULL
);

-- The conflict target both aggregation jobs upsert against.
CREATE UNIQUE INDEX idx_probe_step_aggregates_unique
    ON probe_step_aggregates (service_id, bucket_start, bucket_type, endpoint_key, status_code);

-- How the statistics endpoint reads: one service, one granularity, a window.
CREATE INDEX idx_probe_step_aggregates_service
    ON probe_step_aggregates (service_id, bucket_type, bucket_start);
