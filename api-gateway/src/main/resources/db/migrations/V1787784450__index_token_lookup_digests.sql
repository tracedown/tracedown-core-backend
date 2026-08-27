-- Presenting a token must cost ONE bcrypt, not one per outstanding row.
--
-- Agent enrolment (POST /internal/agents/register) and password-reset
-- confirmation both located their token by SCANNING every outstanding row and
-- running bcrypt against each stored hash -- cost 12 for bootstrap tokens,
-- cost 10 for reset tokens. Both endpoints are unauthenticated, and the whole
-- of /internal/* is deliberately exempt from rate limiting (it carries agent
-- enrolment and the health-challenge token endpoint, neither of which may be
-- throttled by customer traffic). So the CPU a single junk request burned grew
-- with the number of live tokens, and a handful of concurrent junk requests
-- saturated the gateway.
--
-- The tokens are 32 bytes of SecureRandom rendered as hex with no locator
-- inside them, so nothing in the presented value could be indexed as it stood.
-- This adds one: a SHA-256 digest of the raw token, deterministic and
-- therefore indexable. The bcrypt hash stays and is still what authenticates
-- the token -- the digest only picks the single candidate row to verify. That
-- is the same reasoning session tokens already use (see TokenHasher): a
-- 256-bit random token is not brute-forceable, so a fast digest is the correct
-- locator, and a database or backup read still yields no live token.
--
-- Rows minted before this migration carry no digest and can no longer be
-- located. Outstanding ones are deleted rather than left behind as
-- unmatchable credentials: both kinds live at most an hour and are re-issued
-- on demand -- a password reset is simply re-requested, a bootstrap token
-- re-created with --agent-bootstrap or the API. Used rows keep their audit
-- value and are never lookup candidates, so they are left alone.

ALTER TABLE agent_bootstrap_tokens ADD COLUMN token_lookup VARCHAR(64);
ALTER TABLE password_reset_tokens  ADD COLUMN token_lookup VARCHAR(64);

DELETE FROM agent_bootstrap_tokens WHERE used = false;
DELETE FROM password_reset_tokens  WHERE used = false;

CREATE INDEX ix_agent_bootstrap_tokens_lookup
    ON agent_bootstrap_tokens (token_lookup)
    WHERE used = false;

CREATE INDEX ix_password_reset_tokens_lookup
    ON password_reset_tokens (token_lookup)
    WHERE used = false;
