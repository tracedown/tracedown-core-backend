-- Records how long the agent itself spent on a health challenge.
--
-- A challenge round trip is two legs: the scheduler's cold mTLS handshake to
-- the agent, and the Lace fetch the agent then makes to the gateway. The agent
-- has always reported the second leg (`elapsed_ms`) and the scheduler has
-- always discarded it, so a slow round could not say whether the path to the
-- agent or the agent's own egress was the slow part. Null for rounds recorded
-- before this column existed, and for rounds the agent never answered.

ALTER TABLE agent_health_checks
    ADD COLUMN agent_elapsed_ms INTEGER NULL;
