-- One binding per webhook and resource. The API checked before inserting, but
-- two creates racing each other both passed the check and bound the webhook
-- twice, so it fired twice for every event. The index makes the second insert
-- fail, and the gateway answers it 409 binding_exists.
--
-- A database that already holds duplicates keeps one binding of each set and
-- drops the rest; otherwise the index could not be built. The one kept is an
-- enabled one when there is one — the webhook was firing for that resource,
-- and keeping a paused copy would silently stop it — and among those the
-- oldest. The table is small (one row per binding a person made), so this runs
-- in the migration's own transaction without a concurrent build.
DELETE FROM resource_webhook_access
 WHERE id IN (
     SELECT id FROM (
         SELECT id,
                ROW_NUMBER() OVER (
                    PARTITION BY webhook_delivery_id, resource_type, resource_id
                    ORDER BY enabled DESC, created_at, id
                ) AS rank
           FROM resource_webhook_access
     ) ranked
     WHERE ranked.rank > 1
 );

CREATE UNIQUE INDEX IF NOT EXISTS uq_resource_webhook_access_binding
    ON resource_webhook_access (webhook_delivery_id, resource_type, resource_id);
