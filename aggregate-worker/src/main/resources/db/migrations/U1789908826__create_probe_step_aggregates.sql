-- Drops the per-endpoint rollups.
--
-- Safe to lose: every row here is derived from probe_steps, which is still
-- there, so re-applying the forward migration and letting the hourly and daily
-- jobs run rebuilds whatever is still inside the raw-result retention window.
-- What does not come back is any bucket whose raw steps have since been pruned
-- — the same trade the aggregate rows themselves exist to make.
--
-- Nothing else in the schema points at this table, so the indexes go with it.
DROP TABLE IF EXISTS probe_step_aggregates;
