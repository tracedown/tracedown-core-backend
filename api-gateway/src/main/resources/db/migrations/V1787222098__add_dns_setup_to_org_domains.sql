-- Forward migration
--
-- How a domain's verification TXT record got into the zone. The record itself
-- is still the only thing that proves ownership — the verifier reads DNS, not
-- these columns — so this is provenance for the UI and the audit trail: a DNS
-- provider id (`cloudflare`) when we wrote it with a credential the user
-- supplied for that one call, or whatever method a host extension records when
-- it places the record its own way. NULL means it was placed by hand, which
-- stays the default and the fallback.
--
-- No credential is recorded here, or anywhere else: nothing in this feature
-- stores one.
ALTER TABLE org_domains
    ADD COLUMN dns_setup_method VARCHAR(32),
    ADD COLUMN dns_setup_at     TIMESTAMP(0);
