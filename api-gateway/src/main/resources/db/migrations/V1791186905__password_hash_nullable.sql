-- Lets an account exist without a password.
--
-- The column has carried two meanings so far: a bcrypt hash for an account that
-- signs in with a password, and the empty string for the stub an invitation
-- creates, which is not an account yet — the invite link may still claim it and
-- set its first password.
--
-- A host application can establish who somebody is by other means and has had no
-- honest value to write here. The empty string would make a real account
-- claimable through an invitation; a hash of random bytes would keep it safe but
-- leave nothing able to tell that the account has no password — not the form
-- that asks for the current one, and not a check that must not strand an account
-- with no way in.
--
-- NULL is that third state: a real account that has no password. Signing in with
-- credentials always fails for it, every place that re-verifies a password
-- answers that there is none to verify, and the emailed reset link is how it
-- gets one. The empty string keeps its meaning.

-- Changes only the catalogue — no rewrite — but it needs a moment of exclusive
-- access to a table every sign-in reads. Give up rather than queue behind a
-- long transaction with every login queued behind this in turn; a failed
-- migration is retried, a stalled login table is an outage.
SET LOCAL lock_timeout = '5s';

ALTER TABLE users
    ALTER COLUMN password_hash DROP NOT NULL;
