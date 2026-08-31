package dev.tracedown.gateway.controllers.orgs
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import dev.tracedown.common.realtime.RealtimePublisher

import dev.tracedown.common.onboarding.PasswordHasher
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.PermissionCacheService
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.common.auth.OrgPermissions
import dev.tracedown.gateway.util.orgGroupSections
import dev.tracedown.gateway.util.requireGroupGrantable
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import dev.tracedown.common.interceptors.Injectable
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.orgs.AcceptInviteResponse
import dev.tracedown.gateway.data.orgs.AcceptInviteStatus
import dev.tracedown.gateway.data.orgs.InviteInfo
import dev.tracedown.gateway.data.orgs.InviteResponse
import dev.tracedown.gateway.data.orgs.OrgSectionPermissions
import dev.tracedown.gateway.data.orgs.PendingInvite
import dev.tracedown.gateway.util.AppConfig
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.TooManyRequestsException
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.validatePassword
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

object InviteController {

    private val log = LoggerFactory.getLogger(InviteController::class.java)
    private val secureRandom = SecureRandom()
    private const val MIN_RESPONSE_MS = 500L

    fun invite(
        orgId: UUID,
        email: String,
        invitedByUserId: UUID,
        appConfig: AppConfig,
        emailPublisher: EmailPublisher,
        groupIds: List<String> = emptyList(),
    ): InviteResponse {
        val startNanos = System.nanoTime()

        try {
            return doInvite(orgId, email, invitedByUserId, appConfig, emailPublisher, groupIds)
        } finally {
            normalizeResponseTime(startNanos)
        }
    }

    @Injectable("invite.create")
    private fun doInvite(
        orgId: UUID,
        email: String,
        invitedByUserId: UUID,
        appConfig: AppConfig,
        emailPublisher: EmailPublisher,
        groupIds: List<String>,
    ): InviteResponse {
        if (email.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)

        // In-transaction so a before-hook's read (e.g. a member count) is atomic
        // with the membership/invite write it guards.
        return Interceptors.injectableInTx("invite.create", InterceptorContext(orgId = orgId, userId = invitedByUserId)) {
            doInviteInner(orgId, email, invitedByUserId, appConfig, emailPublisher, groupIds)
        }
    }

    private fun doInviteInner(
        orgId: UUID,
        email: String,
        invitedByUserId: UUID,
        appConfig: AppConfig,
        emailPublisher: EmailPublisher,
        groupIds: List<String>,
    ): InviteResponse {
        val inviteTtlSeconds = appConfig.platform.inviteTtlDays * 86400L
        val cooldownSeconds = appConfig.platform.inviteResendCooldownMinutes * 60L

        // Runs inside the caller's injectableInTx transaction.
        return run {
            // Creating a membership is a write on the user surface — the same
            // permission its siblings (list, revoke) read. Without it any member
            // could invite a second address of their own, pre-assign it whatever
            // group they liked, and accept from their own inbox.
            val caller = requireOrgWritePermission(orgId, invitedByUserId)

            val org = Organizations.selectAll()
                .where { (Organizations.id eq orgId) and (Organizations.deleted eq false) }
                .firstOrNull()
                ?: throw NotFoundException()

            val inviterUser = Users.selectAll()
                .where { Users.id eq invitedByUserId }
                .first()

            // Check if a user already exists for this email. Email is globally
            // unique, so an orphaned soft-deleted account must be revived and
            // reused here rather than re-inserted (which would collide).
            val existingUser = Users.selectAll()
                .where { Users.email eq email }
                .firstOrNull()
            if (existingUser != null && existingUser[Users.deleted]) {
                AccountLifecycle.revive(existingUser[Users.id])
            }

            if (existingUser != null) {
                // Check existing org membership — including soft-deleted rows:
                // the (org, user) unique constraint is not partial, so a
                // revoked invite or removed member must be RESURRECTED, not
                // re-inserted.
                val existingMembership = OrgUsers.selectAll()
                    .where {
                        (OrgUsers.organizationId eq orgId) and
                        (OrgUsers.userId eq existingUser[Users.id])
                    }
                    .firstOrNull()

                if (existingMembership != null && existingMembership[OrgUsers.deleted]) {
                    val orgUserId = existingMembership[OrgUsers.id]
                    val token = generateToken()
                    val now = Instant.now()
                    OrgUsers.update({ OrgUsers.id eq orgUserId }) {
                        it[deleted] = false
                        it[deletedAt] = null
                        it[status] = "invited"
                        it[isActive] = false
                        it[joinedAt] = null
                        it[inviteToken] = token
                        it[invitedAt] = now
                        it[invitedBy] = invitedByUserId
                        it[inviteExpiresAt] = now.plusSeconds(inviteTtlSeconds)
                        it[lastInviteSentAt] = now
                    }

                    // A returning member starts from nothing. Removal already
                    // stripped the row (see MembershipAccess); repeating it here
                    // costs one statement and covers rows soft-deleted before
                    // that rule existed, so no re-invite can resurrect old
                    // sections, groups or resource grants.
                    MembershipAccess.revokeAll(orgId, orgUserId)
                    applyPreassignedGroups(orgId, orgUserId, groupIds, caller)

                    sendInviteEmail(
                        email, org[Organizations.name],
                        inviterUser[Users.displayName], token,
                        appConfig, emailPublisher,
                    )
                    AuditService.log(
                        orgId, invitedByUserId, "invite.user", "user", existingUser[Users.id].toString(),
                        entityDisplayName = email,
                    )
                    RealtimePublisher.publish("org:$orgId", orgId, "invite.created")
                    return@run InviteResponse(ok = true)
                }

                if (existingMembership != null) {
                    val status = existingMembership[OrgUsers.status]
                    if (status == "active") {
                        throw ConflictException()
                    }
                    if (status == "invited") {
                        // Check resend cooldown
                        val lastSent = existingMembership[OrgUsers.lastInviteSentAt]
                        if (lastSent != null && lastSent.plusSeconds(cooldownSeconds) > Instant.now()) {
                            throw TooManyRequestsException(ErrorCodes.INVITE_COOLDOWN)
                        }

                        // Resend: refresh token and expiry
                        val newToken = generateToken()
                        val now = Instant.now()
                        OrgUsers.update({ OrgUsers.id eq existingMembership[OrgUsers.id] }) {
                            it[inviteToken] = newToken
                            it[inviteExpiresAt] = now.plusSeconds(inviteTtlSeconds)
                            it[lastInviteSentAt] = now
                        }

                        sendInviteEmail(
                            email, org[Organizations.name],
                            inviterUser[Users.displayName], newToken,
                            appConfig, emailPublisher,
                        )
                        return@run InviteResponse(ok = true)
                    }
                }
            }

            // Create stub user if needed
            val userId = if (existingUser != null) {
                existingUser[Users.id]
            } else {
                val newUserId = UUID.randomUUID()
                Users.insert {
                    it[id] = newUserId
                    it[Users.email] = email
                    it[passwordHash] = ""
                    it[displayName] = email.substringBefore("@")
                    it[isActive] = false
                    it[deleted] = false
                    it[createdAt] = Instant.now()
                }
                newUserId
            }

            // Create org_users invite
            val token = generateToken()
            val now = Instant.now()
            val orgUserId = UUID.randomUUID()
            OrgUsers.insert {
                it[id] = orgUserId
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[joinedAt] = null
                it[status] = "invited"
                it[isActive] = false
                it[deleted] = false
                it[inviteToken] = token
                it[invitedAt] = now
                it[invitedBy] = invitedByUserId
                it[inviteExpiresAt] = now.plusSeconds(inviteTtlSeconds)
                it[lastInviteSentAt] = now
            }

            sendInviteEmail(
                email, org[Organizations.name],
                inviterUser[Users.displayName], token,
                appConfig, emailPublisher,
            )

            // Pre-assign groups so the member lands fully provisioned. The
            // permission cache is recomputed at acceptance.
            applyPreassignedGroups(orgId, orgUserId, groupIds, caller)

            AuditService.log(
                orgId, invitedByUserId, "invite.user", "user", userId.toString(),
                entityDisplayName = email,
                comment = if (groupIds.isEmpty()) "Invited $email" else "Invited $email (${groupIds.size} group(s) pre-assigned)",
            )
            RealtimePublisher.publish("org:$orgId", orgId, "invite.created")

            InviteResponse(ok = true)
        }
    }

    fun getInviteInfo(token: String): InviteInfo {
        return transaction {
            val invite = findValidInvite(token)
            val org = Organizations.selectAll()
                .where { Organizations.id eq invite[OrgUsers.organizationId] }
                .first()
            val user = Users.selectAll()
                .where { Users.id eq invite[OrgUsers.userId] }
                .first()

            InviteInfo(
                orgName = org[Organizations.name],
                email = user[Users.email],
                // A stub invited user is created with an empty password hash; a
                // non-empty one means this email already has a real account.
                userExists = user[Users.passwordHash].isNotBlank(),
            )
        }
    }

    @Injectable("membership.create")
    fun acceptInvite(
        token: String,
        password: String?,
        displayName: String?,
        /** The signed-in user, if any — required to accept for an existing account. */
        authenticatedUserId: UUID?,
        appConfig: AppConfig,
        ipAddress: String?,
        userAgent: String?,
    ): AcceptInviteResponse {
        // Branch on whether the invited email already has a real account: a stub
        // invited user has an empty password hash, a real one does not.
        val target = transaction {
            val invite = findValidInvite(token)
            val userId = invite[OrgUsers.userId]
            val user = Users.selectAll().where { Users.id eq userId }.first()
            InviteTarget(
                userId = userId,
                orgId = invite[OrgUsers.organizationId],
                email = user[Users.email],
                existingAccount = user[Users.passwordHash].isNotBlank(),
            )
        }

        if (target.existingAccount) {
            // Existing account: the token alone must not let anyone join as them —
            // identity is proven by being signed in as the invited user. Their
            // credentials are never touched.
            if (authenticatedUserId != target.userId) {
                return AcceptInviteResponse(AcceptInviteStatus.LOGIN_REQUIRED, email = target.email)
            }
        } else {
            // New account: the invite collects the credentials (confirmed on the
            // client). Password + display name are required only here.
            if (password.isNullOrBlank() || displayName.isNullOrBlank()) {
                throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
            }
            val policyErrors = validatePassword(password, appConfig.auth.passwordPolicy)
            if (policyErrors.isNotEmpty()) throw BadRequestException(ErrorCodes.PASSWORD_TOO_WEAK)
        }

        // Activate the membership and set last_org to the joined org; the session
        // minted below reads last_org, so both a new and an existing user land in
        // the org they just joined.
        val session = Interceptors.injectableInTx("membership.create", InterceptorContext(orgId = target.orgId)) {
            val invite = findValidInvite(token)
            val userId = invite[OrgUsers.userId]
            val orgId = invite[OrgUsers.organizationId]
            val now = Instant.now()

            Users.update({ Users.id eq userId }) {
                it[selectedOrgId] = orgId
                if (!target.existingAccount) {
                    it[Users.passwordHash] = PasswordHasher.hash(password!!)
                    it[Users.displayName] = displayName!!
                    it[isActive] = true
                }
            }
            val orgUserId = invite[OrgUsers.id]
            OrgUsers.update({ OrgUsers.id eq orgUserId }) {
                it[status] = "active"
                it[isActive] = true
                it[joinedAt] = now
                it[inviteToken] = ""
            }
            PermissionCacheService.recomputeForUser(orgUserId)
            emitMembershipCreated(orgUserId, orgId, userId)

            val user = Users.selectAll().where { Users.id eq userId }.first()
            AuthController.createSessionForUser(user, appConfig.jwt.ttlMinutes, ipAddress, userAgent)
        }
        publishUserJoined(target.orgId, target.userId)
        val status =
            if (target.existingAccount) AcceptInviteStatus.ACCEPTED_EXISTING else AcceptInviteStatus.ACCEPTED_NEW
        return AcceptInviteResponse(status, token = session.token)
    }

    /** The invited email resolved to its user + org, before activation. */
    private data class InviteTarget(
        val userId: UUID,
        val orgId: UUID,
        val email: String,
        val existingAccount: Boolean,
    )

    private fun emitMembershipCreated(orgUserId: UUID, orgId: UUID, userId: UUID) {
        OutboxEmit.emitResourceEvent(
            "resource.membership.created", "membership", orgUserId,
            buildJsonObject {
                put("id", orgUserId.toString()); put("orgId", orgId.toString()); put("userId", userId.toString())
            },
        )
    }

    /** Published after the accept transaction commits, so a subscriber's refresh
     *  sees the new active member rather than racing the commit. */
    private fun publishUserJoined(orgId: UUID, userId: UUID) {
        RealtimePublisher.publish(
            "org:$orgId", orgId, "user.joined",
            buildJsonObject { put("userId", userId.toString()) },
        )
    }

    fun listPendingInvites(orgId: UUID, requestingUserId: UUID, pfs: PfsParams): Page<PendingInvite> {
        return transaction {
            requireOrgPermission(orgId, requestingUserId)

            val query = OrgUsers.selectAll()
                .where {
                    (OrgUsers.organizationId eq orgId) and
                    (OrgUsers.status eq "invited") and
                    (OrgUsers.deleted eq false)
                }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { invite ->
                val user = Users.selectAll()
                    .where { Users.id eq invite[OrgUsers.userId] }
                    .first()

                val groupIds = OrgUserGroups.selectAll()
                    .where { OrgUserGroups.orgUserId eq invite[OrgUsers.id] }
                    .map { it[OrgUserGroups.orgGroupId].toString() }

                PendingInvite(
                    id = invite[OrgUsers.id].toString(),
                    userId = invite[OrgUsers.userId].toString(),
                    email = user[Users.email],
                    invitedAt = invite[OrgUsers.invitedAt]?.toString() ?: "",
                    expiresAt = invite[OrgUsers.inviteExpiresAt]?.toString() ?: "",
                    groupIds = groupIds,
                    org = OrgSectionPermissions(
                        users = invite[OrgUsers.orgUserList],
                        settings = invite[OrgUsers.orgSettings],
                        domains = invite[OrgUsers.orgDomains],
                        webhooks = invite[OrgUsers.orgWebhooks],
                        notifications = invite[OrgUsers.orgNotifications],
                        admin = invite[OrgUsers.orgAdmin],
                        workspaces = invite[OrgUsers.orgWorkspaces],
                    ),
                )
            }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    fun revokeInvite(orgId: UUID, inviteId: UUID, requestingUserId: UUID) {
        transaction {
            // Cancelling a membership is a write, not a read — the same
            // permission that creating one takes.
            requireOrgWritePermission(orgId, requestingUserId)

            val invite = OrgUsers.selectAll()
                .where {
                    (OrgUsers.id eq inviteId) and
                    (OrgUsers.organizationId eq orgId) and
                    (OrgUsers.status eq "invited") and
                    (OrgUsers.deleted eq false)
                }
                .firstOrNull()
                ?: throw NotFoundException()

            // A revoked invite is a removed membership: strip it so a later
            // re-invite resurrects an empty row rather than the pre-configured
            // sections and groups this one carried.
            MembershipAccess.revokeAll(orgId, invite[OrgUsers.id])
            OrgUsers.update({ OrgUsers.id eq invite[OrgUsers.id] }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            val inviteEmail = Users.selectAll()
                .where { Users.id eq invite[OrgUsers.userId] }
                .firstOrNull()?.get(Users.email)

            // The entity is the invite, not the invitee — but entityDisplayName
            // is the invitee's email address, which is what erasure matches on
            // to reach this row (PurgeJob.SCRUB_AUDIT_SUBJECT).
            AuditService.log(orgId, requestingUserId, "revoke.invite", "invite", inviteId.toString(),
                entityDisplayName = inviteEmail)
            RealtimePublisher.publish("org:$orgId", orgId, "invite.revoked")
        }
    }

    // ── Internals ──

    /**
     * Validates and inserts group memberships for a (pre-)invited member.
     *
     * Belonging to the org is not enough to make a group assignable: the group
     * carries permission levels, so pre-assigning one is a grant of those levels
     * and goes through the same rule as adding a member to the group by hand.
     */
    private fun applyPreassignedGroups(
        orgId: UUID,
        orgUserId: UUID,
        groupIds: List<String>,
        caller: OrgPermissions,
    ) {
        if (groupIds.isEmpty()) return
        val validGroups = OrgGroups.selectAll()
            .where {
                (OrgGroups.organizationId eq orgId) and
                (OrgGroups.id inList groupIds.map { UUID.fromString(it) })
            }
            .toList()
        if (validGroups.size != groupIds.distinct().size) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }
        validGroups.forEach { group ->
            requireGroupGrantable(caller, orgGroupSections(group))
            OrgUserGroups.insert {
                it[id] = UUID.randomUUID()
                it[OrgUserGroups.orgUserId] = orgUserId
                it[orgGroupId] = group[OrgGroups.id]
            }
        }
    }

    private fun findValidInvite(token: String): org.jetbrains.exposed.sql.ResultRow {
        if (token.isBlank()) throw UnauthorizedException(ErrorCodes.INVALID_INVITE_TOKEN)

        val invite = OrgUsers.selectAll()
            .where {
                (OrgUsers.inviteToken eq token) and
                (OrgUsers.status eq "invited") and
                (OrgUsers.deleted eq false)
            }
            .firstOrNull()
            ?: throw UnauthorizedException(ErrorCodes.INVITE_EXPIRED)

        val expiresAt = invite[OrgUsers.inviteExpiresAt]
        if (expiresAt != null && expiresAt < Instant.now()) {
            throw UnauthorizedException(ErrorCodes.INVITE_EXPIRED)
        }

        return invite
    }

    private fun requireOrgPermission(orgId: UUID, userId: UUID) {
        requireOrgRead(orgId, userId) { it.users }
    }

    /** The write counterpart, for the paths that create or cancel a membership. */
    private fun requireOrgWritePermission(orgId: UUID, userId: UUID): OrgPermissions =
        requireOrgWrite(orgId, userId) { it.users }

    private fun sendInviteEmail(
        recipientEmail: String,
        orgName: String,
        inviterName: String,
        token: String,
        appConfig: AppConfig,
        emailPublisher: EmailPublisher,
    ) {
        val inviteLink = appConfig.platform.uri.inviteUrl(token)
        emailPublisher.publish(
            to = recipientEmail,
            subject = "You've been invited to join $orgName on Tracedown",
            type = "system.invite",
            vars = mapOf(
                "inviterName" to inviterName,
                "orgName" to orgName,
                "inviteLink" to inviteLink,
            ),
            source = "api-gateway",
        )
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun normalizeResponseTime(startNanos: Long) {
        val elapsedMs = (System.nanoTime() - startNanos) / 1_000_000
        val remaining = MIN_RESPONSE_MS - elapsedMs
        if (remaining > 0) {
            try { Thread.sleep(remaining) } catch (_: InterruptedException) {}
        }
    }
}
