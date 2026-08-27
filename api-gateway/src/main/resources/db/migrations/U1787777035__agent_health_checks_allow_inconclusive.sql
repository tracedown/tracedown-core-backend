-- Reverts the 'inconclusive' health-check result.
--
-- Existing 'inconclusive' rows are rewritten to 'fail' first: the old
-- constraint cannot be restored while they are present, and they are history
-- rows only (nothing downstream reads the value except the agent detail view).

UPDATE agent_health_checks SET result = 'fail' WHERE result = 'inconclusive';

ALTER TABLE agent_health_checks
    DROP CONSTRAINT agent_health_checks_result_check;

ALTER TABLE agent_health_checks
    ADD CONSTRAINT agent_health_checks_result_check
    CHECK (result IN ('pass', 'fail', 'wrong_token', 'timeout'));
