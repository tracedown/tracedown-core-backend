-- Forward migration
--
-- Whether a probe run may store the response bodies it received. Off, the
-- agent is dispatched with body saving disabled and a failing run keeps only
-- its status, timings and assertions — there is no stored body to open
-- afterwards. On by default, which is what every existing service already did.
--
-- This is a permission, not a guarantee: dispatch also withholds body saving
-- for services whose targets are unverified domains, regardless of this
-- column.
ALTER TABLE services
    ADD COLUMN save_response_bodies BOOLEAN NOT NULL DEFAULT true;
