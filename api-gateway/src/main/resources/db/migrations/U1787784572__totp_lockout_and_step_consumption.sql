-- Reverting restores per-pending-session lockout counting and drops
-- single-use enforcement of accepted codes.
ALTER TABLE users DROP COLUMN IF EXISTS totp_locked_until;
ALTER TABLE users DROP COLUMN IF EXISTS totp_failed_attempts;
ALTER TABLE users DROP COLUMN IF EXISTS totp_last_step;
