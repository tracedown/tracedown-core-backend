-- Undo only after the gateway, scheduler, ingestor and worker are back on
-- 0.4.58: a newer gateway writes these rows on every run it is asked for, and
-- a newer ingestor settles them.
--
-- Back to runs without a handle. The results the handles named stay in
-- probe_results under the same ids; only the requests themselves go, and the
-- handle route goes with the release that is rolled back to.
SET LOCAL lock_timeout = '5s';

DROP TABLE run_requests;
