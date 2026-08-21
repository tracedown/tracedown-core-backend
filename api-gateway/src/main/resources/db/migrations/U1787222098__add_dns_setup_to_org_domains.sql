-- Undo migration
--
-- Provenance only; dropping it loses no verification state.
ALTER TABLE org_domains
    DROP COLUMN IF EXISTS dns_setup_method,
    DROP COLUMN IF EXISTS dns_setup_at;
