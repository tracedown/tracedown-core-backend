package dev.tracedown.common.auth

import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll

/**
 * Access level for a permission section.
 * Stored as SMALLINT: 0 = none, 1 = read, 2 = write (implies read).
 */
object AccessLevel {
    const val NONE: Short  = 0
    const val READ: Short  = 1
    const val WRITE: Short = 2
}

fun Short.canRead(): Boolean = this >= AccessLevel.READ
fun Short.canWrite(): Boolean = this >= AccessLevel.WRITE

/**
 * Org-level permissions for a member. One access level per section.
 *
 * Built-in sections: users, settings, domains, webhooks, notifications, admin,
 * workspaces. `admin` gates root-level org identity/policy (name, TOTP
 * enforcement, timezone) and the danger zone; the broader `settings` still gates
 * org variables, agents, and other org config.
 *
 * Sections registered by additional modules (see [PermissionSections]) carry
 * their access levels in [extra], keyed by section key. [level] resolves either
 * a built-in field or an extension-section key uniformly.
 *
 * Usage:
 *   val perms = OrgPermissions.from(membershipRow)
 *   if (!perms.users.canWrite()) throw ForbiddenException(...)
 */
data class OrgPermissions(
    val users: Short,
    val settings: Short,
    val domains: Short,
    val webhooks: Short,
    val notifications: Short,
    val admin: Short,
    val workspaces: Short,
    val isOwner: Boolean = false,
    val extra: Map<String, Short> = emptyMap(),
) {
    /**
     * Access level for any section — built-in field or registered extension key.
     *
     * The owner short-circuits to full access. Built-in sections already resolve
     * that way through [FULL], but extension sections are absent from [extra] —
     * nothing grants a section that did not exist when the membership was
     * created — so without this an owner would be locked out of every surface a
     * module registers, contradicting "owner always gets full access".
     */
    fun level(section: String): Short {
        if (isOwner) return AccessLevel.WRITE
        return when (section) {
            "users" -> users
            "settings" -> settings
            "domains" -> domains
            "webhooks" -> webhooks
            "notifications" -> notifications
            "admin" -> admin
            "workspaces" -> workspaces
            else -> extra[section] ?: AccessLevel.NONE
        }
    }

    companion object {
        fun from(row: ResultRow) = OrgPermissions(
            users = row[OrgUsers.orgUserList],
            settings = row[OrgUsers.orgSettings],
            domains = row[OrgUsers.orgDomains],
            webhooks = row[OrgUsers.orgWebhooks],
            notifications = row[OrgUsers.orgNotifications],
            admin = row[OrgUsers.orgAdmin],
            workspaces = row[OrgUsers.orgWorkspaces],
            extra = shortMapFromJson(row[OrgUsers.orgExtraPerms]),
        )

        val FULL = OrgPermissions(
            AccessLevel.WRITE, AccessLevel.WRITE, AccessLevel.WRITE,
            AccessLevel.WRITE, AccessLevel.WRITE, AccessLevel.WRITE, AccessLevel.WRITE,
            isOwner = true,
            extra = emptyMap(),
        )

        /** Parses a JSON object of section key → access level into a map. */
        fun shortMapFromJson(json: JsonObject): Map<String, Short> =
            json.entries.associate { (key, value) -> key to value.jsonPrimitive.int.toShort() }
    }
}

/**
 * Resolves effective org permissions for a user.
 * Owner always gets full access regardless of group/direct permissions.
 * Reads from permission_cache if available, falls back to direct columns.
 */
fun resolveOrgPermissions(orgId: java.util.UUID, userId: java.util.UUID): OrgPermissions? {
    return resolveCachedPermissions(orgId, userId)?.org
}

/**
 * Resolves the full cached permissions (org + resources + totp) for a user in an org.
 * Owner gets FULL with an empty resources map (meaning access to everything).
 */
fun resolveCachedPermissions(orgId: java.util.UUID, userId: java.util.UUID): CachedPermissions? {
    val org = Organizations.selectAll()
        .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
        .firstOrNull() ?: return null

    val membership = OrgUsers.selectAll()
        .where {
            (OrgUsers.organizationId eq orgId) and
            (OrgUsers.userId eq userId) and
            (OrgUsers.status eq "active") and
            (OrgUsers.deleted eq false)
        }
        .firstOrNull()

    if (org[Organizations.ownerId] == userId) {
        // The owner bypasses permission checks, but their explicit grants
        // still matter — notification eligibility and silence bells key off
        // them — so surface the cached grant map instead of dropping it.
        val resources = membership?.get(OrgUsers.permissionCache)
            ?.let { CachedPermissions.fromJsonObject(it).resources }
            ?: emptyMap()
        return CachedPermissions(OrgPermissions.FULL, resources, false)
    }

    if (membership == null) return null

    val cache = membership[OrgUsers.permissionCache]
    if (cache != null) {
        return CachedPermissions.fromJsonObject(cache)
    }

    // Fallback to direct columns (cache not yet computed) — no resource grants
    return CachedPermissions(OrgPermissions.from(membership), emptyMap(), false)
}

/**
 * Checks if a user can access a specific resource, considering downward inheritance.
 *
 * Access is granted if any of these are true:
 * 1. User is org owner
 * 2. Org-level workspaces.read is set
 * 3. Direct resource grant exists in cache
 * 4. A parent resource has a grant (downward inheritance):
 *    - workspace grant → access to all projects/services within
 *    - project grant → access to all services within
 *
 * @param parentChain ancestor resource keys from immediate parent to root,
 *   e.g. for a service: listOf("project::uuid", "workspace::uuid")
 */
fun canAccessResource(
    cached: CachedPermissions,
    resourceType: String,
    resourceId: java.util.UUID,
    parentChain: List<String> = emptyList(),
): Boolean {
    if (cached.org.isOwner) return true
    if (cached.org.workspaces.canRead()) return true
    // Direct grant
    if (cached.resourceAccess(resourceType, resourceId.toString()).canRead()) return true
    // Downward inheritance — any parent with a grant covers this child
    for (parentKey in parentChain) {
        val parentAccess = cached.resources[parentKey] ?: AccessLevel.NONE
        if (parentAccess.canRead()) return true
    }
    return false
}

/**
 * Checks if a user has write access to a specific resource, considering downward inheritance.
 */
fun canWriteResource(
    cached: CachedPermissions,
    resourceType: String,
    resourceId: java.util.UUID,
    parentChain: List<String> = emptyList(),
): Boolean {
    if (cached.org.isOwner) return true
    if (cached.org.workspaces.canWrite()) return true
    if (cached.resourceAccess(resourceType, resourceId.toString()).canWrite()) return true
    for (parentKey in parentChain) {
        val parentAccess = cached.resources[parentKey] ?: AccessLevel.NONE
        if (parentAccess.canWrite()) return true
    }
    return false
}
