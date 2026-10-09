-- Undo only after the gateway, scheduler, ingestor and worker are back on
-- 0.4.58: a newer ingestor writes this column on every result, and a newer
-- gateway reads it on every results list.
--
-- Runs are no longer told apart by what started them, nor tied to the run that
-- was asked for; the record of both goes with the columns.
SET LOCAL lock_timeout = '5s';

ALTER TABLE probe_results
    DROP COLUMN run_id,
    DROP COLUMN trigger;
