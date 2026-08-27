-- Adds the 'inconclusive' health-check result.
--
-- A challenge fails for two very different reasons: the agent is unwell, or
-- the token endpoint the agent has to reach was unavailable. The second says
-- nothing about the agent, and recording it as 'fail' both misreports the
-- agent's history and feeds the consecutive-failure count that takes the agent
-- out of rotation. 'inconclusive' keeps the observation without the verdict.

ALTER TABLE agent_health_checks
    DROP CONSTRAINT agent_health_checks_result_check;

ALTER TABLE agent_health_checks
    ADD CONSTRAINT agent_health_checks_result_check
    CHECK (result IN ('pass', 'fail', 'wrong_token', 'timeout', 'inconclusive'));
