-- At most ONE outstanding (unused) bootstrap token per slug. The slug is the
-- agent's identity (probe_agents.slug is UNIQUE), and enrolment consumes a
-- token by slug — several live tokens for one slug make it ambiguous which
-- credential an enrolling agent presents, and let an unrelated token be
-- captured by whoever guesses the slug first. Token creation now replaces any
-- outstanding token for the slug (a fresh token supersedes — it is shown once
-- and a lost one is re-issued, never resurrected), which this index enforces
-- against races.
--
-- Existing duplicates: keep the newest unused token per slug, drop the rest.
DELETE FROM agent_bootstrap_tokens t
    USING agent_bootstrap_tokens newer
    WHERE t.slug = newer.slug
      AND t.used = false AND newer.used = false
      AND (newer.created_at > t.created_at
           OR (newer.created_at = t.created_at AND newer.id > t.id));

CREATE UNIQUE INDEX ux_agent_bootstrap_tokens_outstanding
    ON agent_bootstrap_tokens (slug)
    WHERE used = false;
