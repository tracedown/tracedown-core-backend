-- Drops the one-binding-per-resource index. The duplicates the forward
-- migration removed are not restored: they were the same binding twice.
DROP INDEX IF EXISTS uq_resource_webhook_access_binding;
