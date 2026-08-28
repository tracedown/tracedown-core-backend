package dev.tracedown.gateway.controllers.orgs

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.ResourcePermissions
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
 * Three things carry access, and all three go:
 *  - the org section columns (plus the open extension-section map),
 *  - group memberships,
 *  - direct resource grants (they key on this row's id with no FK cascade, so
 *    they would otherwise linger forever — the purge job never clears them).
 *
 * The cached permission blob is dropped with them; it is derived state and would
 * otherwise be the fourth copy of the same authority.
 */
internal object MembershipAccess {

    fun revokeAll(orgId: UUID, membershipId: UUID) {
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
    }
}
