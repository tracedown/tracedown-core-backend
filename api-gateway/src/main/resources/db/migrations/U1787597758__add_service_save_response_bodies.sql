-- Undo migration
--
-- Dropping the column returns every service to saving bodies; already-stored
-- bodies are untouched.
ALTER TABLE services
    DROP COLUMN IF EXISTS save_response_bodies;
