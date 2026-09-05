package dev.tracedown.notifications.recipients

import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Users
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.time.Instant
import java.util.UUID

/**
 * Resolved recipient with email address for delivery.
 */
data class Recipient(
    val orgUserId: UUID,
    val userId: UUID,
    val email: String,
)

/**
 * Resolves eligible notification recipients for a given service.
 *
 * Eligibility:
 * 1. Active org user with status = 'active'
 * 2. Holds a grant (read or write, direct or via a group) on the service
 *    or its parent project/workspace
 * 3. Not silenced for the channel at the matching scope. Silences only
 *    apply at or below the user's most specific grant: an explicit service
 *    grant is not muted by a project/workspace silence ("most specific
 *    grant wins"); scopeless silences always apply
 * 4. Not in quiet hours
 */
object RecipientResolver {

    private const val READ_PERMISSION: Short = 1

    /**
     * Finds all eligible email recipients for a notification.
     *
     * @param orgId the organization ID
     * @param workspaceId the workspace containing the service
     * @param projectId the project containing the service
     * @param serviceId the service that triggered the notification
     * @param channel the delivery channel ("email" or "webhook")
     * @param now current instant for quiet hours evaluation
     */
    fun resolve(
        orgId: UUID,
        workspaceId: UUID,
        projectId: UUID,
        serviceId: UUID,
        channel: String,
        now: Instant = Instant.now(),
    ): List<Recipient> {
        return transaction {
            // 1. Find all active org users
            val orgUsers = OrgUsers.join(Users, JoinType.INNER, OrgUsers.userId, Users.id)
                .selectAll()
                .where {
                    (OrgUsers.organizationId eq orgId) and
                        (OrgUsers.status eq "active") and
                        (OrgUsers.isActive eq true) and
                        (OrgUsers.deleted eq false)
                }
                .map { row ->
                    Triple(
                        row[OrgUsers.id],
                        row[OrgUsers.userId],
                        row[Users.email],
                    )
                }

            // 2. Filter to users with explicit resource access.
            //    Org-level workspaces permission is intentionally NOT checked here:
            //    only users with an explicit grant (their own, or through one of
            //    their groups) on the workspace, project, or service receive
            //    notifications. Grants are stored against org_user/org_group ids.
            val grants = ResourcePermissions.selectAll()
                .where {
                    (ResourcePermissions.orgId eq orgId) and
                        (ResourcePermissions.permissions greaterEq READ_PERMISSION) and
                        (
                            ((ResourcePermissions.resourceType eq "service") and (ResourcePermissions.resourceId eq serviceId)) or
                                ((ResourcePermissions.resourceType eq "project") and (ResourcePermissions.resourceId eq projectId)) or
                                ((ResourcePermissions.resourceType eq "workspace") and (ResourcePermissions.resourceId eq workspaceId))
                            )
                }
                .toList()

            val grantedGroupIds = grants
                .filter { it[ResourcePermissions.principalType] == "org_group" }
                .map { it[ResourcePermissions.principalId] }
                .toSet()
            val membersByGroup: Map<UUID, List<UUID>> = if (grantedGroupIds.isEmpty()) emptyMap() else {
                OrgUserGroups.selectAll()
                    .where { OrgUserGroups.orgGroupId inList grantedGroupIds }
                    .groupBy({ it[OrgUserGroups.orgGroupId] }, { it[OrgUserGroups.orgUserId] })
            }

            /** org_user ids granted (directly or via a group) at one resource level. */
            fun grantedAt(resourceType: String): Set<UUID> {
                val direct = grants
                    .filter { it[ResourcePermissions.principalType] == "org_user" && it[ResourcePermissions.resourceType] == resourceType }
                    .map { it[ResourcePermissions.principalId] }
                val viaGroups = grants
                    .filter { it[ResourcePermissions.principalType] == "org_group" && it[ResourcePermissions.resourceType] == resourceType }
                    .flatMap { membersByGroup[it[ResourcePermissions.principalId]] ?: emptyList() }
                return (direct + viaGroups).toSet()
            }

            val grantedAtService = grantedAt("service")
            val grantedAtProject = grantedAt("project")
            val grantedAtWorkspace = grantedAt("workspace")

            val usersWithAccess = orgUsers.filter { (orgUserId, _, _) ->
                orgUserId in grantedAtService || orgUserId in grantedAtProject || orgUserId in grantedAtWorkspace
            }

            // 3. Filter out silenced users
            val silences = NotificationSilences.selectAll()
                .where {
                    NotificationSilences.orgUserId inList usersWithAccess.map { it.first }
                }
                .toList()

            val silencesByUser = silences.groupBy { it[NotificationSilences.orgUserId] }

            val silencedUserIds = usersWithAccess
                .filter { (orgUserId, _, _) ->
                    // Most specific grant wins: a broader silence does not mute
                    // a subscription held through a more specific grant.
                    val grantSpecificity = when (orgUserId) {
                        in grantedAtService -> 3
                        in grantedAtProject -> 2
                        else -> 1
                    }
                    (silencesByUser[orgUserId] ?: emptyList()).any { silence ->
                        val silenceChannel = silence[NotificationSilences.channel]
                        if (silenceChannel != "all" && silenceChannel != channel) return@any false

                        val silenceServiceId = silence[NotificationSilences.serviceId]
                        val silenceProjectId = silence[NotificationSilences.projectId]
                        val silenceWorkspaceId = silence[NotificationSilences.workspaceId]

                        when {
                            silenceServiceId != null -> silenceServiceId == serviceId
                            silenceProjectId != null -> silenceProjectId == projectId && grantSpecificity <= 2
                            silenceWorkspaceId != null -> silenceWorkspaceId == workspaceId && grantSpecificity <= 1
                            else -> true // No scope = explicit mute-everything
                        }
                    }
                }
                .map { it.first }
                .toSet()

            // 4. Filter out users in quiet hours
            val quietHoursMap = silences
                .filter { it[NotificationSilences.quietHours] != null }
                .associate { it[NotificationSilences.orgUserId] to it[NotificationSilences.quietHours] }

            usersWithAccess
                .filter { (orgUserId, _, _) ->
                    orgUserId !in silencedUserIds
                }
                .filter { (orgUserId, _, _) ->
                    val quietHours = quietHoursMap[orgUserId]
                    !QuietHoursEvaluator.isInQuietHours(quietHours, now)
                }
                .map { (orgUserId, userId, email) ->
                    Recipient(
                        orgUserId = orgUserId,
                        userId = userId,
                        email = email,
                    )
                }
        }
    }

}
