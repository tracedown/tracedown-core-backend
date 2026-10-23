-- What started a run: `schedule` (its cron) or `manual` (somebody asked for it,
-- from the dashboard or the API). Carried on the result envelope by the
-- scheduler and written by the ingestor.
--
-- probe_results is one of the two big tables. A constant default makes this a
-- catalogue change (PostgreSQL 11 and later store the default and do not
-- rewrite the table), so it holds its lock for as long as the catalogue update
-- takes, not for a scan. Every existing row reads as `schedule`, which is what
-- nearly all of them were; manual runs were not told apart before this.
--
-- `run_id` names the run somebody asked for that a result belongs to: the
-- request's id, on every result of it — the first is filed under that id
-- itself, and a run on several agents at once has siblings that carry it here.
-- NULL for every other run, and for every row before this. Adding a nullable
-- column without a default is a catalogue change too.
--
-- No index on either: the results list filters on `trigger` within one
-- service's time range, which idx_probe_results_service already narrows (a
-- two-valued column would not be chosen anyway), and a run's siblings are
-- read within one service and one second of its start, the same index.
-- Every write of a result waits behind the lock while it is held, so give up
-- rather than queue behind a long transaction: a failed migration is retried.
SET LOCAL lock_timeout = '5s';

ALTER TABLE probe_results
    ADD COLUMN trigger VARCHAR(8) NOT NULL DEFAULT 'schedule',
    ADD COLUMN run_id UUID;
