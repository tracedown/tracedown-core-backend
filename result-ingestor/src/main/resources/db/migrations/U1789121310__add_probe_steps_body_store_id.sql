-- Undo: forget which steps' bodies live in a body store.
--
-- DATA WARNING. A body in an 'in_place' store is not in platform storage, and
-- the column is the only record of that. Dropping it would leave steps holding
-- a URL the platform believes it owns — retention and purge would then try to
-- delete another party's objects, and the dashboard would read them through the
-- default store's client (and fail). So the rows are cleared first: those
-- bodies are forgotten, exactly as DELETE /body-stores/{id}?forgetBodies=true
-- forgets them, with the same reason.
--
-- Once in_place stores are in use, do NOT roll the aggregate-worker back past
-- the release that introduced this column.
UPDATE probe_steps
   SET response_body_storage_url = NULL,
       body_not_stored_reason    = 'storeRemoved'
 WHERE body_store_id IS NOT NULL;

DROP INDEX IF EXISTS idx_probe_steps_body_store_id;
ALTER TABLE probe_steps DROP COLUMN IF EXISTS body_store_id;
