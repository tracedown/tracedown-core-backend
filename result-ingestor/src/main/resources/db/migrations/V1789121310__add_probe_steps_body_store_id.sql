-- Where a stored response body lives: NULL = the default store (unchanged),
-- set only for a body kept in an 'in_place' body store. The platform reads
-- such a body through that store and never deletes it. The foreign key has no
-- ON DELETE action, so a store is refused deletion while any step names it
-- (the API's forgetBodies form clears these rows first).
--
-- The index on the column is built separately, without a lock, in
-- V1789375635__index_probe_steps_body_store_id — probe_steps is the largest
-- table in the schema and this migration must not hold an exclusive lock on it.
ALTER TABLE probe_steps
    ADD COLUMN body_store_id UUID NULL REFERENCES body_stores (id);
