package dev.tracedown.gateway.util

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.common.auth.canWrite
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUsers
import org.jetbrains.exposed.sql.ResultRow

/**
 * Who may hand out which level of access.
 *
 * The section guards ([requireOrgWrite]) decide who reaches the user and group
 * management surface at all. On their own they do not decide what a caller may
 * write into it, and without a second rule the two collapse: `users` write was
 * enough to reach the surface, so it was enough to write any level into any
 * row — the caller's own row included, the `admin` section included.
 *
 * Two rules close that:
 *
 * 1. **Nobody hands out what they do not hold.** A section may only be RAISED to
 *    a level the caller themselves has. Lowering is always allowed — taking
 *    access away grants nothing, whatever level is left standing.
 * 2. **The admin section is admin-gated.** `admin` carries the org danger zone
 *    and org-wide TOTP enforcement, so any change to it — in either direction,
 *    on a user or on a group — needs `admin` write on top of the `users` write
 *    that admits the caller to the surface. Group TOTP enforcement rides the
 *    same gate ([mayGovernOrgPolicy]), for the same reason the org-level toggle
 *    in OrgSettingsController already does.
 *
 * The org owner resolves to [OrgPermissions.FULL], so none of this constrains
 * them.
 */
object GrantPolicy {

    /** The section key that gates org-admin-level operations. */
    const val ADMIN_SECTION = "admin"

    /**
     * The first section [caller] may not move from [current] to [requested], or
     * null when the whole change is within their reach.
     *
     * Sections absent from [requested] are untouched. A requested level equal to
     * the current one is not a grant and passes, so a caller holding `users`
     * write can still edit an admin's unrelated sections without being able to
     * move the admin level itself.
     */
    fun deniedSection(
        caller: OrgPermissions,
        current: Map<String, Short>,
        requested: Map<String, Short>,
    ): String? {
        for ((section, level) in requested) {
            val now = current[section] ?: AccessLevel.NONE
            if (level == now) continue
            if (section == ADMIN_SECTION && !mayGovernOrgPolicy(caller)) return section
            // A reduction hands out nothing, even when what is left standing is
            // above the caller's own level: the principal already held it.
            if (level < now) continue
            if (level > caller.level(section)) return section
        }
        return null
    }

    /**
     * Whether [caller] may change org-wide policy: the admin section itself, and
     * TOTP enrolment enforcement.
     */
    fun mayGovernOrgPolicy(caller: OrgPermissions): Boolean =
        caller.level(ADMIN_SECTION).canWrite()
}

/** Throws when [caller] may not move a principal's sections to [requested]. */
fun requireGrantable(caller: OrgPermissions, current: Map<String, Short>, requested: Map<String, Short>) {
    if (GrantPolicy.deniedSection(caller, current, requested) != null) {
        throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
    }
}

/**
 * Throws when [caller] may not put a principal into a group carrying
 * [groupSections]. Joining a group grants everything the group holds, so it goes
 * through the same rule as writing those levels onto the member directly —
 * otherwise the group is simply a second route to the same escalation.
 */
fun requireGroupGrantable(caller: OrgPermissions, groupSections: Map<String, Short>) {
    requireGrantable(caller, emptyMap(), groupSections)
}

/** Throws when [caller] may not switch TOTP enrolment enforcement on or off. */
fun requireOrgPolicyWrite(caller: OrgPermissions) {
    if (!GrantPolicy.mayGovernOrgPolicy(caller)) {
        throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
    }
}

/** Section levels a membership row holds directly, keyed the way [GrantPolicy] wants them. */
fun orgUserSections(row: ResultRow): Map<String, Short> = buildMap {
    put("users", row[OrgUsers.orgUserList])
    put("settings", row[OrgUsers.orgSettings])
    put("domains", row[OrgUsers.orgDomains])
    put("webhooks", row[OrgUsers.orgWebhooks])
    put("notifications", row[OrgUsers.orgNotifications])
    put("admin", row[OrgUsers.orgAdmin])
    put("workspaces", row[OrgUsers.orgWorkspaces])
    putAll(OrgPermissions.shortMapFromJson(row[OrgUsers.orgExtraPerms]))
}

/** Section levels a group carries, keyed the way [GrantPolicy] wants them. */
fun orgGroupSections(row: ResultRow): Map<String, Short> = buildMap {
    put("users", row[OrgGroups.orgUserList])
    put("settings", row[OrgGroups.orgSettings])
    put("domains", row[OrgGroups.orgDomains])
    put("webhooks", row[OrgGroups.orgWebhooks])
    put("notifications", row[OrgGroups.orgNotifications])
    put("admin", row[OrgGroups.orgAdmin])
    put("workspaces", row[OrgGroups.orgWorkspaces])
    putAll(OrgPermissions.shortMapFromJson(row[OrgGroups.orgExtraPerms]))
}
