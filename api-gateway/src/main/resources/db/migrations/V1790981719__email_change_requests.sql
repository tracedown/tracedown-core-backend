-- An account's address is changed only once the new address has confirmed it.
--
-- The change used to be written the moment the current password was given.
-- That made `users.email` a claim rather than a fact: an account could put
-- itself on any address nobody else had yet — a typo, or somebody else's — and
-- from then on every mail, every invitation and every reset link went there,
-- and the address's real owner could not sign up with it.
--
-- Now the request is held here, a link is mailed to the new address, and the
-- change is written when that link is followed. One live request per account;
-- a new request supersedes the old one. Only the hash of the link's token is
-- stored, so a copy of the table mints no working links.
CREATE TABLE email_change_requests (
    id           UUID          PRIMARY KEY,
    user_id      UUID          NOT NULL REFERENCES users(id),
    new_email    VARCHAR(256)  NOT NULL,
    -- SHA-256, hex, of the token in the mailed link.
    token_hash   VARCHAR(64)   NOT NULL,
    expires_at   TIMESTAMP     NOT NULL,
    used         BOOLEAN       NOT NULL DEFAULT false,
    created_at   TIMESTAMP     NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_email_change_requests_token ON email_change_requests(token_hash);
CREATE INDEX idx_email_change_requests_user ON email_change_requests(user_id);
