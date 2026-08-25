-- Per-agent payload sealing on top of mTLS.
--
-- `encrypt_payload` is the operator's choice for this agent; it defaults false,
-- so every existing agent keeps behaving exactly as before. It is per agent and
-- not platform-wide because the exposure is a property of the path: an agent
-- reached through something that terminates TLS needs sealing, one on the same
-- private network gains nothing and pays an RSA wrap on every run.
--
-- `supports_encrypted_payload` is not a setting but an observation, refreshed
-- from the health challenge. The two are separate on purpose: honouring the
-- choice without the observation would seal a payload to an agent whose code
-- cannot open it, and its probes would fail with nothing to point at.
ALTER TABLE probe_agents
    ADD COLUMN encrypt_payload BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN supports_encrypted_payload BOOLEAN NOT NULL DEFAULT FALSE;
