-- Org-wide date format for everything the frontend renders: 'eu' is
-- dd.mm.yyyy, 'us' is mm/dd/yyyy. Applies to every member of the org.
ALTER TABLE organizations ADD COLUMN date_format VARCHAR(8) NOT NULL DEFAULT 'eu';
