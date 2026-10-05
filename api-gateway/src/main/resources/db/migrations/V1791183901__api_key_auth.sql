-- API keys become a working credential.
--
-- A key is 256 bits of random output, so it is stored the way a session token
-- is: as a SHA-256 digest that the presented key is hashed and matched against.
-- The bcrypt hashes written so far cannot be looked up at all (a salted hash
-- has no equality), which is why no request was ever authenticated by one.
--
-- Every existing row is revoked. The ones that exist on a database this is
-- first applied to hold bcrypt hashes that can never match again, and a list
-- that showed them as usable would be lying. Any other row — one that survived
-- an undo of this migration, say — belongs to a build that could not verify it
-- either: an undo revokes every key (see the U file), so after an undo and a
-- redo nobody holds a working key from before, and nothing here has to tell
-- the two kinds of row apart.
UPDATE api_keys SET revoked = true WHERE revoked = false;

-- The first characters of the key, kept so a person can tell their keys apart
-- in a list and so a log line can name one without holding the credential.
ALTER TABLE api_keys ADD COLUMN key_prefix VARCHAR(16);

-- The most a key may do, on top of what its user may do: 1 = read, 2 = write.
-- Defaults to read so a row that somehow arrives without a level is the
-- narrower of the two, and admits nothing else.
ALTER TABLE api_keys ADD COLUMN access SMALLINT NOT NULL DEFAULT 1
    CONSTRAINT api_keys_access_check CHECK (access IN (1, 2));

-- The lookup every key-authenticated request makes.
CREATE UNIQUE INDEX api_keys_key_hash_key ON api_keys (key_hash);

-- created_by is now the user a key acts as, and a user's own keys are listed
-- and counted by it.
CREATE INDEX api_keys_created_by_idx ON api_keys (created_by);
