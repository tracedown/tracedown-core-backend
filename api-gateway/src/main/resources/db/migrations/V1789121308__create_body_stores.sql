-- Body stores: places other than the default (environment-configured) store
-- where an agent writes the response bodies it captures.
--
--   mode 'import'   — the agent writes here; the ingestor copies each body into
--                     the default store, which then owns it.
--   mode 'in_place' — the body stays here and is read on demand with the
--                     store's credentials; the platform never deletes from it.
--
-- The default store is not a row: a NULL body_store_id means "default"
-- everywhere. The secret access key is encrypted with the platform key
-- (AES-GCM, bound to the row id) and never returned by any API.
CREATE TABLE body_stores (
    id             UUID PRIMARY KEY,
    name           VARCHAR(64)  NOT NULL UNIQUE,
    kind           VARCHAR(16)  NOT NULL CHECK (kind IN ('s3', 'filesystem')),
    mode           VARCHAR(16)  NOT NULL CHECK (mode IN ('import', 'in_place')),
    endpoint       TEXT         NULL,
    region         VARCHAR(64)  NULL,
    bucket         VARCHAR(255) NULL,
    prefix         VARCHAR(255) NULL,
    root_path      TEXT         NULL,
    access_key_id  VARCHAR(255) NULL,
    secret_enc     TEXT         NULL,
    secret_iv      VARCHAR(64)  NULL,
    created_at     TIMESTAMP(0) NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP(0) NOT NULL DEFAULT now(),
    CONSTRAINT body_stores_s3_complete CHECK (
        kind <> 's3' OR (endpoint IS NOT NULL AND bucket IS NOT NULL
                         AND access_key_id IS NOT NULL AND secret_enc IS NOT NULL AND secret_iv IS NOT NULL)
    ),
    CONSTRAINT body_stores_filesystem_complete CHECK (
        kind <> 'filesystem' OR root_path IS NOT NULL
    )
);

-- The store an agent writes its bodies to (NULL = the default store).
ALTER TABLE probe_agents
    ADD COLUMN body_store_id UUID NULL REFERENCES body_stores (id);
CREATE INDEX idx_probe_agents_body_store_id ON probe_agents (body_store_id)
    WHERE body_store_id IS NOT NULL;

-- Registration copies this onto the agent it creates, so an agent enrolled
-- with the token starts on the right store.
ALTER TABLE agent_bootstrap_tokens
    ADD COLUMN body_store_id UUID NULL REFERENCES body_stores (id);
CREATE INDEX idx_agent_bootstrap_tokens_body_store_id ON agent_bootstrap_tokens (body_store_id)
    WHERE body_store_id IS NOT NULL;
