-- Undo migration
--
-- Rows collapsed by the forward migration's de-duplication are not restored:
-- they were duplicate representations of the same bucket, and re-creating them
-- would re-introduce the double-weighting the index exists to prevent.

DROP INDEX IF EXISTS idx_probe_aggregates_rollup_unique;

DROP TABLE IF EXISTS job_watermarks;
