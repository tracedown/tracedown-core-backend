-- Adds 'error' to the service status vocabulary.
--
-- A run that did not evaluate — a Lace script that failed to run, an executor
-- that raised, an agent that answered with a diagnostic instead of a result —
-- is now persisted as a probe_results row with status 'error' instead of being
-- dropped. probe_results already allows that value; services.last_status did
-- not, so the service kept showing the last status it happened to have,
-- reporting a check as green that had not actually run.
--
-- 'error' is not a claim about the monitored target: it says the check could
-- not be evaluated. It is deliberately separate from 'failure' and 'timeout',
-- which are things the target did.

ALTER TABLE services
    DROP CONSTRAINT services_last_status_check;

ALTER TABLE services
    ADD CONSTRAINT services_last_status_check
    CHECK (last_status IN ('success', 'failure', 'timeout', 'error'));
