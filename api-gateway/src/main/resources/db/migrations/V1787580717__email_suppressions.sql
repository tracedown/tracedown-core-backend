-- Addresses no further mail may be sent to.
--
-- A provider bounce webhook writes here; the email-service reads it before every
-- send. Without it a hard-bounced address is retried on every alert, and repeated
-- delivery to a dead mailbox is exactly what a provider scores as sender abuse —
-- so one bad address quietly degrades delivery for every other recipient.
--
-- Only PERMANENT failures land here. A soft bounce (mailbox full, greylisted,
-- transient 4xx) must not: it resolves on its own, and suppressing on it would
-- silently drop a working recipient.
CREATE TABLE email_suppressions (
    id          UUID PRIMARY KEY,
    -- Stored lowercased; the unique index below is what makes the send-time
    -- lookup a single indexed hit rather than a scan with lower().
    email       VARCHAR(320) NOT NULL,
    -- 'bounce' (permanent delivery failure) | 'complaint' (marked as spam)
    -- | 'manual' (an operator suppressed it by hand).
    reason      VARCHAR(16)  NOT NULL,
    -- Which provider reported it; null for a manual entry.
    provider    VARCHAR(16),
    -- The provider's own description, kept verbatim for diagnosis.
    detail      TEXT,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- One row per address: a repeat webhook for the same address updates it rather
-- than accumulating duplicates, so this is both the constraint and the lookup index.
CREATE UNIQUE INDEX idx_email_suppressions_email ON email_suppressions (email);
