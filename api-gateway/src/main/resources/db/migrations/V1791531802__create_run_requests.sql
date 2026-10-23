-- A run somebody asked for, by the id they were handed for it.
--
-- `POST /services/{id}/run` used to answer with a time and nothing else, and a
-- caller waiting for its run had to guess which result in the history was
-- theirs. The gateway now mints the run's id, records the request here, and
-- the scheduler files the run — its result, or the skipped row that says why
-- it did not happen — under that same id as `probe_results.id`. This row is
-- what makes an id that has no result yet answer "pending" rather than "not
-- found", and what a run whose trigger was lost is judged "expired" from.
--
-- `state` is pending until the result-ingestor records the run, which sets it
-- to done or skipped in the same transaction as the last of its results (`expected_results` of them: more than one when the
-- service runs on several agents at once). The gateway settles it as skipped
-- itself, with `reason`, when no scheduler heard the request at all.
-- `expired` is never stored: it is a pending row older than the gateway's
-- bound, and a result that arrives after that still settles it.
--
-- `api_key_id` is deliberately not a foreign key, as on org_audit_log: the
-- record of which key asked has to outlive the key. The results themselves
-- are found by the request's id (`probe_results.id`, `probe_results.run_id`).
--
-- `purge_after` is when the row may go: the request time plus the
-- organization's result retention window, set by the gateway; NULL while
-- results are kept forever. The rows also go with their service and
-- organization (ON DELETE CASCADE), and outlive the user who asked (SET NULL).
-- The foreign keys take a lock on services, organizations and users while the
-- table is created. Give up rather than queue behind a long transaction with
-- every request to those tables queued behind this in turn; a failed
-- migration is retried, a stalled services table is an outage.
SET LOCAL lock_timeout = '5s';

CREATE TABLE run_requests (
    id               UUID        PRIMARY KEY,
    service_id       UUID        NOT NULL REFERENCES services(id) ON DELETE CASCADE,
    organization_id  UUID        NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    requested_by     UUID        REFERENCES users(id) ON DELETE SET NULL,
    api_key_id       UUID,
    requested_at     TIMESTAMP   NOT NULL,
    state            VARCHAR(8)  NOT NULL DEFAULT 'pending',
    expected_results SMALLINT,
    reason           VARCHAR(64),
    purge_after      TIMESTAMP
);
-- The read is by id (the primary key) and then checked against the service.
-- The purge reads by purge_after; a service or organization delete cascades
-- through these two.
CREATE INDEX idx_run_requests_purge_after ON run_requests(purge_after) WHERE purge_after IS NOT NULL;
CREATE INDEX idx_run_requests_service ON run_requests(service_id);
CREATE INDEX idx_run_requests_org ON run_requests(organization_id);
CREATE INDEX idx_run_requests_requested_by ON run_requests(requested_by);
