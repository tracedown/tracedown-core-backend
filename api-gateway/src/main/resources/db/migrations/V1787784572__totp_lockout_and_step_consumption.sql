-- Two holes in the second factor, both fixed by moving state off the pending
-- session and onto the user.
--
-- 1. Lockout was counted in sessions.totp_attempt_count, i.e. per pending
--    session. A pending session is created by POST /auth/login, so an attacker
--    holding the password could reset the counter at will simply by starting a
--    new login -- five guesses per login, unlimited logins, which is not a
--    limit. The counter belongs to the account being guessed, so it lives on
--    the user now, and trips a time-boxed lock that self-heals.
--
-- 2. A code that had been accepted could be presented again while its 30s
--    window (plus the one-step drift tolerance) still stood -- a TOTP code is
--    single-use by definition, and totp_last_used_at was written but never
--    read, so nothing enforced it. totp_last_step records the time-step index
--    actually consumed; a later verification must present a STRICTLY newer
--    step, which burns the code used and every earlier one with it.
--
-- The session column stays: it is still a per-attempt-chain counter and the
-- pending session is where a single login's attempts are counted. The user
-- columns are what a lockout decision is now made from.

ALTER TABLE users ADD COLUMN totp_last_step BIGINT;
ALTER TABLE users ADD COLUMN totp_failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN totp_locked_until TIMESTAMP(0);
