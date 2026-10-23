-- Undo only after the gateway is back on 0.4.62, which cannot describe an
-- administration key.
--
-- Administration keys come back as write keys: the level they carried until
-- now, so no key gains or loses anything it could do.
UPDATE api_keys SET access = 2 WHERE access = 3;
ALTER TABLE api_keys DROP CONSTRAINT api_keys_access_check;
ALTER TABLE api_keys ADD CONSTRAINT api_keys_access_check CHECK (access IN (1, 2));
