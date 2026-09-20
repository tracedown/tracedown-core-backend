-- Undo migration
--
-- Deliberate no-op.
--
-- The forward migration repairs data, not schema: it soft-deletes services,
-- projects and Grafana integrations that were left live under a parent their
-- owner had already deleted. Nothing records which rows it touched, because a
-- row it stamped is indistinguishable afterwards from one the cascade in the
-- application stamped a moment later.
--
-- Undoing it would therefore mean un-deleting rows by the same join — which
-- would resurrect every child of every deleted parent, including the ones the
-- fixed delete paths put there on purpose, and start those probes running
-- again. That is the bug, not its reversal, so this undo does nothing.

SELECT 1;
