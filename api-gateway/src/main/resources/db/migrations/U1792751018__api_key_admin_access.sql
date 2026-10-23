-- Undo only after the gateway is back on 0.4.62, which cannot describe an
-- administration key.
--
-- Administration keys become write keys. Nothing served the administration
-- level, so no key loses anything it could do.
SET LOCAL lock_timeout = '5s';

UPDATE api_keys SET access = 2 WHERE access = 3;
ALTER TABLE api_keys DROP CONSTRAINT api_keys_access_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_access_check CHECK (access IN (1, 2));
