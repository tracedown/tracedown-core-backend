ALTER TABLE probe_agents
    DROP COLUMN IF EXISTS supports_encrypted_payload,
    DROP COLUMN IF EXISTS encrypt_payload;
