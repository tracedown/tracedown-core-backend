package dev.tracedown.gateway.controllers.orgs

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import kotlinx.serialization.json.JsonObject
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.update
import java.util.UUID

/**
 * Strips every scrap of access from a membership row.
 *
 * A membership is soft-deleted, never erased: the (org, user) uniqueness
 * constraint is not partial, so a later re-invite RESURRECTS this exact row.
 * Anything still on it comes back with the returning member, and nothing in the
 * UI shows a deleted row's levels — an admin removed for cause would return an
 * admin, invisibly.
 *
 * So access ends at removal, not at resurrection. Clearing here rather than in
 * the re-invite branch means the row is inert the moment the removing
 * transaction commits: a crash, a restore from backup, or a future second
 * resurrect path cannot bring the old authority back, because it is no longer
 * stored anywhere. The re-invite branch calls this too, which costs nothing and
 * covers rows soft-deleted before this rule existed.
 *
 * Four things carry access, and all four go:
 *  - the org section columns (plus the open extension-section map),
 *  - group memberships,
 *  - direct resource grants (they key on this row's id with no FK cascade, so
 *    they would otherwise linger forever — the purge job never clears them),
 *  - **any live session still scoped to this organization** (see [unbindSessions]).
 *
 * The cached permission blob is dropped with them; it is derived state and would
 * otherwise be the fourth copy of the same authority.
 */
internal object MembershipAccess {

    fun revokeAll(orgId: UUID, membershipId: UUID) {
        val userId = OrgUsers.select(OrgUsers.userId)
            .where { OrgUsers.id eq membershipId }
            .firstOrNull()?.get(OrgUsers.userId)

        OrgUserGroups.deleteWhere { orgUserId eq membershipId }
        ResourcePermissions.deleteWhere {
            (ResourcePermissions.orgId eq orgId) and
            (ResourcePermissions.principalType eq "org_user") and
            (ResourcePermissions.principalId eq membershipId)
        }
        OrgUsers.update({ OrgUsers.id eq membershipId }) {
            it[orgUserList] = AccessLevel.NONE
            it[orgSettings] = AccessLevel.NONE
            it[orgDomains] = AccessLevel.NONE
            it[orgWebhooks] = AccessLevel.NONE
            it[orgNotifications] = AccessLevel.NONE
            it[orgAdmin] = AccessLevel.NONE
            it[orgWorkspaces] = AccessLevel.NONE
            it[orgExtraPerms] = JsonObject(emptyMap())
            it[permissionCache] = null
        }

        if (userId != null) unbindSessions(orgId, userId)
    }

    /**
     * Detaches the account's live sessions from an organization it no longer
     * belongs to.
     *
     * Most routes re-resolve the membership on every request, so a stale
     * `sessions.organization_id` is usually inert. Not all of them do: a handler
     * that reads the org straight off the principal and never asks a permission
     * helper keeps answering for the whole remaining lifetime of the token —
     * which is why this is fixed here, at the one place a membership stops
     * existing, rather than route by route. Any future handler that trusts the
     * principal's org is covered by construction.
     *
     * The session itself survives: an account in several organizations is only
     * losing one of them, and killing the token would sign it out of the others
     * too. It comes back pointing at no organization — the same state a fresh
     * sign-in with nothing selected produces, which the API already handles —
     * and the next org selection re-scopes it. The persisted selection on the
     * account is cleared for the same reason.
     */
    private fun unbindSessions(orgId: UUID, userId: UUID) {
        Sessions.update({
            (Sessions.userId eq userId) and (Sessions.organizationId eq orgId)
        }) {
            it[organizationId] = null
        }
        Users.update({ (Users.id eq userId) and (Users.selectedOrgId eq orgId) }) {
            it[selectedOrgId] = null
        }
    }
}
