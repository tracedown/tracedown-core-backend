-- Reverts 'error' from the service status vocabulary.
--
-- Existing 'error' rows are cleared to NULL first: the old constraint cannot be
-- restored while they are present, and NULL ("no status recorded") is the only
-- honest substitute — rewriting them to 'failure' or 'timeout' would assert
-- something about the monitored target that no probe ever observed. The probe
-- history keeps every errored run either way; only this cached column is reset,
-- and the next run repopulates it.

UPDATE services SET last_status = NULL WHERE last_status = 'error';

ALTER TABLE services
    DROP CONSTRAINT services_last_status_check;

ALTER TABLE services
    ADD CONSTRAINT services_last_status_check
    CHECK (last_status IN ('success', 'failure', 'timeout'));
