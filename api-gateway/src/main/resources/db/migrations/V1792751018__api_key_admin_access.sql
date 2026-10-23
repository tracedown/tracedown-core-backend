-- A third level a key can be issued at: 3 = administration. It carries write
-- and is reserved for organization administration through keys, which nothing
-- serves yet.
--
-- Every key-authenticated request reads this table: fail fast behind a long
-- transaction rather than queue them all behind the lock.
SET LOCAL lock_timeout = '5s';

ALTER TABLE api_keys DROP CONSTRAINT api_keys_access_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_access_check CHECK (access IN (1, 2, 3));
