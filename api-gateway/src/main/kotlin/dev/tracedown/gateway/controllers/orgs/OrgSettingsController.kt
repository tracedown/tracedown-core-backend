package dev.tracedown.gateway.controllers.orgs
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.gateway.data.orgs.OrgSettings
import dev.tracedown.gateway.data.orgs.UpdateOrgSettingsRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

object OrgSettingsController {

    /** Returns the organization's settings. */
    fun getSettings(orgId: UUID, requestingUserId: UUID): OrgSettings {
        return transaction {
            // General tab (org identity/policy) is the high-trust admin surface.
            requireOrgRead(orgId, requestingUserId) { it.admin }
            orgSettingsFrom(orgId)
        }
    }

    /**
     * Updates organization settings. Only non-null fields are applied.
     * When totpRequired changes, recomputes permission cache for all org members.
     */
    fun updateSettings(orgId: UUID, request: UpdateOrgSettingsRequest, requestingUserId: UUID): OrgSettings {
        return transaction {
            requireOrgWrite(orgId, requestingUserId) { it.admin }

            val org = Organizations.selectAll()
                .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
                .firstOrNull()
                ?: throw NotFoundException()

            if (request.name != null) {
                if (request.name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
                if (request.name.length > 128) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
            }

            if (request.defaultTimezone != null &&
                request.defaultTimezone !in java.time.ZoneId.getAvailableZoneIds()
            ) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }

            val totpChanged = request.totpRequired != null && request.totpRequired != org[Organizations.totpRequired]

            Organizations.update({ Organizations.id eq orgId }) {
                request.name?.let { v -> it[name] = v }
                request.totpRequired?.let { v -> it[Organizations.totpRequired] = v }
                request.defaultTimezone?.let { v -> it[Organizations.defaultTimezone] = v }
            }

            if (totpChanged) {
                PermissionCacheService.recomputeForOrg(orgId)
            }

            AuditService.log(
                orgId, requestingUserId, "update.settings", "org", orgId.toString(),
                entityDisplayName = org[Organizations.name],
                diff = auditDiff(
                    Triple("name", org[Organizations.name], request.name ?: org[Organizations.name]),
                    Triple("totpRequired", org[Organizations.totpRequired], request.totpRequired ?: org[Organizations.totpRequired]),
                    Triple("defaultTimezone", org[Organizations.defaultTimezone], request.defaultTimezone ?: org[Organizations.defaultTimezone]),
                ),
            )
            RealtimePublisher.publish("org:$orgId", orgId, "settings.updated")

            orgSettingsFrom(orgId)
        }
    }

    /**
     * Transfers ownership to another active member of the organization.
     * Only the current owner can transfer.
     */
    fun transferOwnership(orgId: UUID, newOwnerId: UUID, requestingUserId: UUID): OrgSettings {
        return transaction {
            val org = Organizations.selectAll()
                .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
                .firstOrNull()
                ?: throw NotFoundException()

            if (org[Organizations.ownerId] != requestingUserId) {
                throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
            }

            if (newOwnerId == requestingUserId) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }

            // Verify new owner is an active member
            val newOwnerMembership = OrgUsers.selectAll()
                .where {
                    (OrgUsers.organizationId eq orgId) and
                    (OrgUsers.userId eq newOwnerId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
                ?: throw BadRequestException(ErrorCodes.NOT_ORG_MEMBER)

            Organizations.update({ Organizations.id eq orgId }) {
                it[ownerId] = newOwnerId
            }

            RealtimePublisher.publish("org:$orgId", orgId, "ownership.transferred", buildJsonObject {
                put("fromUserId", requestingUserId.toString())
                put("toUserId", newOwnerId.toString())
            })

            AuditService.log(orgId, requestingUserId, "transfer.ownership", "org", orgId.toString(),
                entityDisplayName = org[Organizations.name],
                comment = "Transferred to user $newOwnerId")
                RealtimePublisher.publish("org:$orgId", orgId, "settings.updated")

            orgSettingsFrom(orgId)
        }
    }

    /**
     * Soft-deletes an organization. Only the owner can delete.
     * Sets deleted/deletedAt on the org and all its memberships.
     * Clears selectedOrgId for affected users.
     */
    fun deleteOrg(orgId: UUID, requestingUserId: UUID, purgeRetentionDays: Int) {
        // An external module intercepts a successful org deletion to run its own
        // teardown (an after-hook fires only once the soft-delete below commits, so
        // an unauthorized attempt never triggers it). Core defines only the seam.
        Interceptors.injectable(
            "org.delete",
            InterceptorContext(orgId = orgId, userId = requestingUserId),
        ) {
        transaction {
            val org = Organizations.selectAll()
                .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
                .firstOrNull()
                ?: throw NotFoundException()

            if (org[Organizations.ownerId] != requestingUserId) {
                throw ForbiddenException(ErrorCodes.INSUFFICIENT_PERMISSIONS)
            }

            val now = Instant.now()
            val purgeAfter = now.plusSeconds(purgeRetentionDays * 86400L)

            Organizations.update({ Organizations.id eq orgId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[Organizations.purgeAfter] = purgeAfter
            }

            // Stop the org's probes: soft-delete its services so the scheduler
            // drops them on its next consistency sweep instead of firing against a
            // deleted org.
            val serviceIds = (Services innerJoin Projects innerJoin Workspaces).selectAll()
                .where { (Workspaces.organizationId eq orgId) and (Services.deleted eq false) }
                .map { it[Services.id] }
            if (serviceIds.isNotEmpty()) {
                Services.update({ Services.id inList serviceIds }) {
                    it[Services.deleted] = true
                    it[Services.deletedAt] = now
                }
            }

            // Members to reconcile once their memberships here are gone.
            val affectedUserIds = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.deleted eq false) }
                .map { it[OrgUsers.userId] }
                .distinct()

            // Soft-delete all memberships
            OrgUsers.update({
                (OrgUsers.organizationId eq orgId) and (OrgUsers.deleted eq false)
            }) {
                it[deleted] = true
                it[deletedAt] = now
            }

            // Clear selectedOrgId for users who had this org selected
            Users.update({ Users.selectedOrgId eq orgId }) {
                it[selectedOrgId] = null
            }

            // Members left with no other org get their account scheduled for deletion.
            affectedUserIds.forEach { AccountLifecycle.reconcile(it, now) }

            AuditService.log(orgId, requestingUserId, "delete.org", "org", orgId.toString(),
                entityDisplayName = org[Organizations.name])
        }
        }
    }

    private fun orgSettingsFrom(orgId: UUID): OrgSettings {
        val org = Organizations.selectAll()
            .where { Organizations.id eq orgId }
            .first()

        return OrgSettings(
            id = org[Organizations.id].toString(),
            name = org[Organizations.name],
            ownerId = org[Organizations.ownerId].toString(),
            totpRequired = org[Organizations.totpRequired],
            defaultTimezone = org[Organizations.defaultTimezone],
        )
    }
}
