-- Restores the columns with their original definition. The per-row values are
-- NOT restored, because there were none worth restoring: every row carried the
-- `true` default for the whole life of the column, so the default reproduces the
-- exact prior state rather than approximating it.
ALTER TABLE workspaces ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE projects   ADD COLUMN is_active BOOLEAN NOT NULL DEFAULT true;
