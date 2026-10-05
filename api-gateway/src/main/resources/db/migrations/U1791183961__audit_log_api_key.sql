-- The column is the record of which key an action came through, and a redo
-- cannot bring it back. It is folded into the entry's comment first, so the
-- audit trail keeps saying what it said.
UPDATE org_audit_log
SET comment = concat_ws(' ', comment, '(via API key ' || api_key_id || ')')
WHERE api_key_id IS NOT NULL;

ALTER TABLE org_audit_log DROP COLUMN api_key_id;
