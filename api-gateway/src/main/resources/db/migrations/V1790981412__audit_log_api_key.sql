-- Which API key an audited action came through, when it came through one. The
-- actor stays the user; this records the credential beside them. Deliberately
-- not a foreign key: the entry has to outlive the key it names.
--
-- Its own migration, apart from the api_keys changes: the purge job touches
-- api_keys and org_audit_log in one transaction in one order, and the user
-- purge in the other, so a migration that locked both would deadlock with
-- whichever ran at the same time. Flyway runs each migration in its own
-- transaction, so this one holds a lock on one table only.
ALTER TABLE org_audit_log ADD COLUMN api_key_id UUID;
