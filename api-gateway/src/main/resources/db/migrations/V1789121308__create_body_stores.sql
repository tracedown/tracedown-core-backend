-- Body stores: places other than the default (environment-configured) store
-- where an organization's agents write the response bodies they capture.
--
--   mode 'import'   — the agent writes here; the ingestor copies each body into
--                     the default store, which then owns it.
--   mode 'in_place' — the body stays here and is read on demand with the
--                     store's credentials; the platform never deletes from it.
--
-- A store belongs to one organization: only that organization's settings
-- writers manage it, only its agents may be assigned it, and only its results
-- may record a body in it. Deleting the organization takes its stores with it —
-- the agents and steps that reference them are cleared first by the API, so the
-- cascade only ever runs on rows nothing points at.
--
-- The default store is not a row: a NULL body_store_id means "default"
-- everywhere. The secret access key is encrypted with BODY_STORE_AES_KEY
-- (AES-GCM, bound to the row id) and never returned by any API.
CREATE TABLE body_stores (
    id                UUID PRIMARY KEY,
    organization_id   UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    name              VARCHAR(64)  NOT NULL,
    kind              VARCHAR(16)  NOT NULL CHECK (kind IN ('s3', 'filesystem')),
    mode              VARCHAR(16)  NOT NULL CHECK (mode IN ('import', 'in_place')),
    endpoint          TEXT         NULL,
    region            VARCHAR(64)  NULL,
    bucket            VARCHAR(255) NULL,
    prefix            VARCHAR(255) NULL,
    root_path         TEXT         NULL,
    access_key_id     VARCHAR(255) NULL,
    secret_enc        TEXT         NULL,
    secret_iv         VARCHAR(64)  NULL,
    -- Why the store last refused or failed a call, cleared on the next success.
    last_failure_code VARCHAR(64)  NULL,
    last_failure_at   TIMESTAMP(0) NULL,
    created_at        TIMESTAMP(0) NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP(0) NOT NULL DEFAULT now(),
    CONSTRAINT body_stores_s3_complete CHECK (
        kind <> 's3' OR (endpoint IS NOT NULL AND bucket IS NOT NULL
                         AND access_key_id IS NOT NULL AND secret_enc IS NOT NULL AND secret_iv IS NOT NULL)
    ),
    CONSTRAINT body_stores_filesystem_complete CHECK (
        kind <> 'filesystem' OR root_path IS NOT NULL
    )
);

-- One name per organization; two organizations may each call a store "eu".
CREATE UNIQUE INDEX body_stores_org_name_key ON body_stores (organization_id, name);
CREATE INDEX idx_body_stores_organization_id ON body_stores (organization_id);

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
