-- Drops the agent's own elapsed time from health-check history.

ALTER TABLE agent_health_checks
    DROP COLUMN agent_elapsed_ms;
