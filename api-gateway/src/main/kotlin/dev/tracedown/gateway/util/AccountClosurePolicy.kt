package dev.tracedown.gateway.util

import dev.tracedown.gateway.data.auth.OwnedOrgSummary

/**
 * Which organizations stand in the way of an account closing itself.
 *
 * An organization outlives whoever created it. Its owner walking away must not
 * take a team's monitoring with it, so ownership blocks closure — the same rule
 * the endpoint has always had, stated here rather than buried in a query.
 *
 * The one case that is not a hand-off is an organization the account is the
 * only member of: nobody is left to hand it to, and refusing would leave the
 * person with an account they cannot close and an organization they are the
 * last member of. So the request may opt to take those along, and only those:
 * the opt-in never reaches an organization somebody else is still a member of
 * (a pending invitee included — see [OwnedOrgSummary.soleMember]).
 *
 * Returns the blockers, in the order given, so the refusal can name them. Empty
 * means the closure may proceed, and every organization in [owned] then goes
 * with it.
 */
object AccountClosurePolicy {

    fun blocking(owned: List<OwnedOrgSummary>, deleteOwnedOrgs: Boolean): List<OwnedOrgSummary> =
        owned.filter { !it.soleMember || !deleteOwnedOrgs }
}
