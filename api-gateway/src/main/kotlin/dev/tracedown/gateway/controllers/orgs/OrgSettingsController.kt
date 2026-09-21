package dev.tracedown.gateway.controllers.orgs
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.data.orgs.OrgSettings
import dev.tracedown.gateway.data.orgs.UpdateOrgSettingsRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.DeletionCascade
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.ScheduleNudge
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
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
                request.dateFormat?.let { v -> it[Organizations.dateFormat] = v }
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
                    Triple("dateFormat", org[Organizations.dateFormat], request.dateFormat ?: org[Organizations.dateFormat]),
                ),
            )
            RealtimePublisher.publish("org:$orgId", orgId, "settings.updated")

            orgSettingsFrom(orgId)
        }
    }

    /**
     * Transfers ownership to another active member of the organization.
     * Only the current owner can transfer.
     *
     * An external module may intercept this to veto a change of owner — the hook
     * is the only place outside org creation where a user becomes an owner, so a
     * host with its own rules about who may hold an organization needs both or
     * neither. The new owner's id is in `extra["newOwnerId"]`.
     *
     * The hook runs inside the transaction that writes `owner_id`, so a check
     * that reads what the recipient already owns is atomic with the write.
     */
    @Injectable("org.ownership.transfer")
    fun transferOwnership(orgId: UUID, newOwnerId: UUID, requestingUserId: UUID): OrgSettings {
        return Interceptors.injectableInTx(
            "org.ownership.transfer",
            InterceptorContext(
                orgId = orgId,
                userId = requestingUserId,
                extra = mutableMapOf("newOwnerId" to newOwnerId),
            ),
        ) {
        transaction {
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
    }

    /**
     * Soft-deletes an organization and everything under it. Only the owner can
     * delete.
     *
     * The whole subtree — workspaces, projects, services, scrape credentials and
     * memberships — goes down in the same transaction, stamped with the
     * organization's own deletion instant and its purge date, so a restore can
     * tell exactly what this delete took. Clears `selectedOrgId` for affected
     * users and reconciles each account that is left without an organization.
     */
    fun deleteOrg(orgId: UUID, requestingUserId: UUID) {
        // An external module intercepts a successful org deletion to run its own
        // teardown (an after-hook fires only once the soft-delete below commits, so
        // an unauthorized attempt never triggers it). Core defines only the seam.
        val cascaded = Interceptors.injectable(
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

            Organizations.update({ Organizations.id eq orgId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[Organizations.purgeAfter] = DeletionRetention.purgeAfter(now)
            }

            // Members to reconcile once their memberships here are gone — read
            // before the cascade below soft-deletes those memberships.
            val affectedUserIds = OrgUsers.selectAll()
                .where { (OrgUsers.organizationId eq orgId) and (OrgUsers.deleted eq false) }
                .map { it[OrgUsers.userId] }
                .distinct()

            // The subtree goes with it: workspaces, projects, services (so the
            // scheduler drops them rather than firing against a deleted org),
            // scrape credentials and memberships, all on the same stamp.
            val cascaded = DeletionCascade.organization(orgId, now)

            // Clear selectedOrgId for users who had this org selected
            Users.update({ Users.selectedOrgId eq orgId }) {
                it[selectedOrgId] = null
            }

            // Members left with no other org get their account scheduled for deletion.
            affectedUserIds.forEach { AccountLifecycle.reconcile(it, now) }

            AuditService.log(orgId, requestingUserId, "delete.org", "org", orgId.toString(),
                entityDisplayName = org[Organizations.name])
            cascaded
        }
        }

        // Same follow-up the workspace and project deletes do, for the same
        // reason: the resolver cache would otherwise keep answering for a
        // container that is gone, and the scheduler would keep the org's probes
        // until its next consistency sweep.
        cascaded.projectIds.forEach(ResourceResolver::invalidateProject)
        cascaded.serviceIds.forEach(ResourceResolver::invalidateService)
        ScheduleNudge.publishAll(cascaded.serviceIds)
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
            dateFormat = org[Organizations.dateFormat],
        )
    }
}
