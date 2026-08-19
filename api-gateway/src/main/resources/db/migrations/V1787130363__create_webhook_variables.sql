-- Per-webhook variables, referenced as `$h.<key>` in a webhook's URL and
-- config header/query values. They exist so a delivery credential (bot token,
-- signing key) can live encrypted next to the one webhook that uses it,
-- instead of as an org variable that every script author in the org can read
-- and use. They are resolved only by the notification dispatcher — probe
-- scripts cannot reference them.
--
-- Shape mirrors org_variables minus system_type (no system-managed webhook
-- vars). webhook_id cascades so the purge job's hard-deletes of
-- webhook_deliveries (per-webhook and whole-org) take the variables with them.
CREATE TABLE webhook_variables (
    id              UUID            PRIMARY KEY,
    organization_id UUID            NOT NULL REFERENCES organizations(id),
    webhook_id      UUID            NOT NULL REFERENCES webhook_deliveries(id) ON DELETE CASCADE,
    created_by      UUID            REFERENCES users(id) ON DELETE SET NULL,
    key             VARCHAR(64)     NOT NULL,
    value           TEXT            NOT NULL,
    secret          BOOLEAN         NOT NULL,
    encrypted       BOOLEAN         NOT NULL DEFAULT true,
    value_iv        VARCHAR(64),
    deleted         BOOLEAN         NOT NULL DEFAULT false,
    deleted_at      TIMESTAMP(0),
    purge_after     TIMESTAMP(0),
    created_at      TIMESTAMP       NOT NULL DEFAULT now(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT now(),
    CHECK (secret = false OR encrypted = true)
);

-- Soft delete frees the key for re-creation, so uniqueness holds only among
-- live rows.
CREATE UNIQUE INDEX ux_webhook_variables_alive
    ON webhook_variables (webhook_id, key)
    WHERE deleted = false;
