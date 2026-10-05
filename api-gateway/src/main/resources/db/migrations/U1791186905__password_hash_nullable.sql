-- Restores NOT NULL on the password hash.
--
-- An account without a password is given a value no password can match. It must
-- not become the empty string: that marks an invitation stub, and would let an
-- invite link claim a real account.

UPDATE users SET password_hash = '!' WHERE password_hash IS NULL;

ALTER TABLE users
    ALTER COLUMN password_hash SET NOT NULL;
