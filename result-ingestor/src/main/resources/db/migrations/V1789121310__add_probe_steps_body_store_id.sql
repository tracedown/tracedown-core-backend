-- Where a stored response body lives: NULL = the default store (unchanged),
-- set only for a body kept in an 'in_place' body store. The platform reads
-- such a body through that store and never deletes it. The foreign key has no
-- ON DELETE action, so a store is refused deletion while any step names it.
ALTER TABLE probe_steps
    ADD COLUMN body_store_id UUID NULL REFERENCES body_stores (id);
CREATE INDEX idx_probe_steps_body_store_id ON probe_steps (body_store_id)
    WHERE body_store_id IS NOT NULL;
