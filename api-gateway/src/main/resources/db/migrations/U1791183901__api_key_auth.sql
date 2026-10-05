-- Every key is revoked, whatever it holds. The previous build verifies no key
-- at all, so none of them works after this undo in any case; revoking them
-- makes that permanent, which is what keeps an undo safe:
--
--  - a key whose user is erased on the previous build is left with created_by
--    NULL, and an older undo (U1785507622, in any copy made before this file)
--    fills NULL created_by with the organization's owner — for a working key
--    that would be a credential acting as the owner once this migration is
--    applied again;
--  - the previous build's account revival and member removal do not touch
--    keys, so an account handed to a new person, or a membership that ended
--    and was re-granted, could come back with the old holder's working key.
--
-- Keys minted under this migration therefore do not survive a rollback: an
-- operator who rolls back and forward again has to tell key holders to mint
-- new ones. Their access level and prefix would have been lost below anyway.
UPDATE api_keys SET revoked = true WHERE revoked = false;

DROP INDEX api_keys_created_by_idx;
DROP INDEX api_keys_key_hash_key;

ALTER TABLE api_keys DROP COLUMN access;
ALTER TABLE api_keys DROP COLUMN key_prefix;
