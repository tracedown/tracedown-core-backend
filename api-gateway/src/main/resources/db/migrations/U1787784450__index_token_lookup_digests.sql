-- Reverting drops the locator; verification would have to go back to scanning
-- every outstanding row. Tokens minted while the column existed keep working
-- (their bcrypt hash is untouched); only the single-row lookup is lost.
DROP INDEX IF EXISTS ix_password_reset_tokens_lookup;
DROP INDEX IF EXISTS ix_agent_bootstrap_tokens_lookup;

ALTER TABLE password_reset_tokens  DROP COLUMN IF EXISTS token_lookup;
ALTER TABLE agent_bootstrap_tokens DROP COLUMN IF EXISTS token_lookup;
