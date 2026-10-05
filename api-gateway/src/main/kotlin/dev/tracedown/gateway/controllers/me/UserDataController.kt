package dev.tracedown.gateway.controllers.me

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.EmailChangeRequests
import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.OrgGroups
import dev.tracedown.common.models.OrgUserGroups
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.me.ChangeEmailRequest
import dev.tracedown.gateway.data.me.EmailChangeRequested
import dev.tracedown.gateway.data.me.EmailChanged
import dev.tracedown.gateway.data.me.ExportApiKey
import dev.tracedown.gateway.data.me.ExportAuditEntry
import dev.tracedown.gateway.data.me.ExportNotificationLogEntry
import dev.tracedown.gateway.data.me.ExportNotificationSilence
import dev.tracedown.gateway.data.me.ExportOrgMembership
import dev.tracedown.gateway.data.me.ExportProfile
import dev.tracedown.gateway.data.me.ExportResourceGrant
import dev.tracedown.gateway.data.me.ExportSentInvite
import dev.tracedown.gateway.data.me.ExportSession
import dev.tracedown.gateway.data.me.ExportVariable
import dev.tracedown.gateway.data.me.UserDataExport
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.TooManyRequestsException
import dev.tracedown.gateway.util.UnauthorizedException
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.stringParam
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

private val log = LoggerFactory.getLogger("dev.tracedown.gateway.controllers.me.UserDataController")

/**
 * Account-scoped data operations for the calling user: a full personal data
 * export and self-service email change.
 */
object UserDataController {

    /**
     * Assembles a single JSON document of everything stored about [userId].
     * Secrets are excluded by construction: every section is an explicit
     * field allowlist (no password hash, TOTP secret, session tokens, API key
     * material, invite tokens, or variable values).
     */
    fun export(userId: UUID, currentSessionId: UUID): UserDataExport = transaction {
        val user = Users.selectAll()
            .where { (Users.id eq userId) and (Users.deleted eq false) }
            .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)

        UserDataExport(
            generatedAt = Instant.now().toString(),
            profile = ExportProfile(
                id = user[Users.id].toString(),
                email = user[Users.email],
                displayName = user[Users.displayName],
                totpEnabled = user[Users.totpEnabled],
                totpEnrolledAt = user[Users.totpEnrolledAt]?.toString(),
                selectedOrgId = user[Users.selectedOrgId]?.toString(),
                isActive = user[Users.isActive],
                createdAt = user[Users.createdAt].toString(),
            ),
            sessions = exportSessions(userId, currentSessionId),
            orgMemberships = exportMemberships(userId),
            resourceGrants = exportResourceGrants(userId),
            auditLog = exportAuditLog(userId, user[Users.email]),
            apiKeys = exportApiKeys(userId),
            notificationSilences = exportSilences(userId),
            variables = exportVariables(userId),
            sentInvites = exportSentInvites(userId),
            notificationLog = exportNotificationLog(user[Users.email]),
        )
    }

    /** How long the link mailed to the new address stays good. */
    private const val EMAIL_CHANGE_TTL_MINUTES = 60L

    /**
     * How soon an account may ask again. The request mails an address the
     * caller chose, so the caller — password in hand — must not be able to
     * turn this into a stream of mail at somebody; one a minute is enough for
     * a person who mistyped.
     */
    private const val EMAIL_CHANGE_COOLDOWN_SECONDS = 60L

    /**
     * How many requests may be mailed to one address in an hour, across all
     * accounts: the per-account cooldown alone lets many accounts take turns
     * at one inbox. Three covers a person correcting a typo twice.
     */
    private const val EMAIL_CHANGE_PER_ADDRESS_PER_HOUR = 3L

    /**
     * Asks to change the account email. Re-verifies identity (password, plus a
     * TOTP code when enrolled), checks that the address is free, and mails a
     * confirmation link to the NEW address. **Nothing on the account changes
     * here**: the address is the account's identity for sign-in, invitations
     * and alerts, and it moves only once [confirmEmailChange] proves somebody
     * receives mail at the new one. The old address is told as well, so a
     * change the holder did not ask for is visible where they still are — and
     * told how to stop it: changing the password voids the request.
     *
     * One live request per account; asking again supersedes the earlier link.
     */
    fun requestEmailChange(
        userId: UUID,
        request: ChangeEmailRequest,
        emailPublisher: EmailPublisher,
        confirmUrlBuilder: (String) -> String,
    ): EmailChangeRequested {
        val newEmail = request.newEmail.trim()
        val now = Instant.now()

        // The cheap refusals first, so a TOTP code is not spent on a request
        // that was never going to be accepted.
        transaction { checkCooldown(userId, newEmail, now) }
        AuthController.verifyIdentity(userId, request.currentPassword, request.code)

        val token = generateToken()
        val expiresAt = now.plusSeconds(EMAIL_CHANGE_TTL_MINUTES * 60)
        val (oldEmail, displayName, requestId) = transaction {
            // The row lock serialises concurrent requests of one account, so
            // "one live request" holds: the second waits, then sees the first.
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) }
                .forUpdate()
                .firstOrNull() ?: throw UnauthorizedException(ErrorCodes.INVALID_CREDENTIALS)
            if (user[Users.email].equals(newEmail, ignoreCase = true)) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
            if (emailTaken(newEmail, userId)) throw BadRequestException(ErrorCodes.EMAIL_TAKEN)
            checkCooldown(userId, newEmail, now)

            // The earlier link, if any, stops working: one live request.
            EmailChangeRequests.update({ (EmailChangeRequests.userId eq userId) and (EmailChangeRequests.used eq false) }) {
                it[used] = true
            }
            val requestId = UUID.randomUUID()
            EmailChangeRequests.insert {
                it[id] = requestId
                it[EmailChangeRequests.userId] = userId
                it[EmailChangeRequests.newEmail] = newEmail
                it[tokenHash] = TokenHasher.sha256Hex(token)
                it[EmailChangeRequests.expiresAt] = expiresAt
                it[createdAt] = now
            }
            Triple(user[Users.email], user[Users.displayName], requestId)
        }
        log.info("Email change requested: user={} request={}", userId, requestId)

        // To the new address: the link, and nothing the account chose — the
        // recipient may be a stranger, and this mail is signed by the platform.
        // To the old: a heads-up, with nothing in it that acts — if this was
        // not the holder, the link must not be where they can reach it.
        emailPublisher.publish(
            to = newEmail,
            subject = "Confirm your new email address",
            type = "system.email-change",
            vars = mapOf(
                "newEmail" to newEmail,
                "expiryMinutes" to EMAIL_CHANGE_TTL_MINUTES.toString(),
                "confirmLink" to confirmUrlBuilder(token),
            ),
            source = "api-gateway",
        )
        emailPublisher.publish(
            to = oldEmail,
            subject = "Your email address is being changed",
            type = "system.email-change-notice",
            vars = mapOf(
                "userName" to displayName,
                "newEmail" to newEmail,
            ),
            source = "api-gateway",
        )
        return EmailChangeRequested(newEmail = newEmail, expiresAt = expiresAt.toString())
    }

    /**
     * Too soon for this account, or too often for that address. Runs in the
     * caller's transaction — once before the password check, once under the
     * row lock, where it is the one that counts.
     */
    private fun checkCooldown(userId: UUID, newEmail: String, now: Instant) {
        val latest = EmailChangeRequests.selectAll()
            .where { EmailChangeRequests.userId eq userId }
            .orderBy(EmailChangeRequests.createdAt, SortOrder.DESC)
            .limit(1)
            .firstOrNull()
        if (latest != null && latest[EmailChangeRequests.createdAt].plusSeconds(EMAIL_CHANGE_COOLDOWN_SECONDS) > now) {
            throw TooManyRequestsException(ErrorCodes.EMAIL_CHANGE_COOLDOWN)
        }
        val toThatAddress = EmailChangeRequests.selectAll()
            .where {
                (EmailChangeRequests.newEmail.lowerCase() eq newEmail.lowercase()) and
                    (EmailChangeRequests.createdAt greater now.minusSeconds(3600))
            }
            .count()
        if (toThatAddress >= EMAIL_CHANGE_PER_ADDRESS_PER_HOUR) {
            throw TooManyRequestsException(ErrorCodes.EMAIL_CHANGE_COOLDOWN)
        }
    }

    /**
     * Writes the change the link mailed to the new address asked for.
     *
     * Unauthenticated: the link is the credential, and the person following
     * it may well be in a different browser from the one that asked. The
     * token is single-use and short-lived, the address is checked to be free
     * again (it may have been taken meanwhile), the change is audited in every
     * organization the account is active in, and — as for a password reset —
     * every session is signed out: the account's identity has changed, and
     * whoever holds a session now signs in again under the new address. The
     * old address is told the change went through, so a holder who missed the
     * first notice still learns where their account went.
     */
    fun confirmEmailChange(token: String, emailPublisher: EmailPublisher): EmailChanged {
        val tokenHash = TokenHasher.sha256Hex(token)
        val (oldEmail, displayName, newEmail) = transaction {
            val pending = EmailChangeRequests.selectAll()
                .where {
                    (EmailChangeRequests.tokenHash eq tokenHash) and
                        (EmailChangeRequests.used eq false) and
                        (EmailChangeRequests.expiresAt greater Instant.now())
                }
                .firstOrNull() ?: throw BadRequestException(ErrorCodes.INVALID_TOKEN)
            val userId = pending[EmailChangeRequests.userId]
            val newEmail = pending[EmailChangeRequests.newEmail]
            // Closed or switched off since the request: the link is as dead as
            // the sessions those paths dropped.
            val user = Users.selectAll()
                .where { (Users.id eq userId) and (Users.deleted eq false) and (Users.isActive eq true) }
                .firstOrNull() ?: throw BadRequestException(ErrorCodes.INVALID_TOKEN)
            // The claim: of two follows of one link, one flips the row.
            val claimed = EmailChangeRequests.update({
                (EmailChangeRequests.id eq pending[EmailChangeRequests.id]) and (EmailChangeRequests.used eq false)
            }) { it[used] = true }
            if (claimed == 0) throw BadRequestException(ErrorCodes.INVALID_TOKEN)
            if (emailTaken(newEmail, userId)) throw BadRequestException(ErrorCodes.EMAIL_TAKEN)

            val oldEmail = user[Users.email]
            Users.update({ Users.id eq userId }) { it[email] = newEmail }
            auditEmailChange(userId, user[Users.displayName], oldEmail, newEmail)

            Sessions.update({ (Sessions.userId eq userId) and (Sessions.revoked eq false) }) {
                it[revoked] = true
            }
            log.info("Email change confirmed: user={} request={}", userId, pending[EmailChangeRequests.id])
            Triple(oldEmail, user[Users.displayName], newEmail)
        }
        emailPublisher.publish(
            to = oldEmail,
            subject = "Your email address has been changed",
            type = "system.email-changed",
            vars = mapOf(
                "userName" to displayName,
                "newEmail" to newEmail,
            ),
            source = "api-gateway",
        )
        return EmailChanged(email = newEmail)
    }

    /**
     * True when any other row holds [email], case-insensitively. Deleted rows
     * count too: `users.email` is unique across all of them, and a closed
     * account's address goes back into circulation only through the signup
     * that reclaims the row.
     */
    private fun emailTaken(email: String, userId: UUID): Boolean =
        Users.selectAll()
            .where { (Users.email.lowerCase() eq email.lowercase()) and (Users.id neq userId) }
            .limit(1)
            .any()

    /**
     * Records the rectification (GDPR Art. 16). The audit log is org-scoped
     * (OrgAuditLog.organizationId is NOT NULL) and there is no user-scoped
     * audit table, so the change is logged into EVERY org the user is an
     * active member of — an account email change is never invisible in the
     * audit trail just because no org happened to be selected. An account with
     * no membership at all has no audit home by construction — accepted.
     */
    private fun auditEmailChange(userId: UUID, displayName: String, oldEmail: String, newEmail: String) {
        val emailDiff = buildJsonObject {
            putJsonObject("email") {
                put("old", oldEmail)
                put("new", newEmail)
            }
        }.toString()
        OrgUsers.selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                    (OrgUsers.status eq "active") and
                    (OrgUsers.deleted eq false)
            }
            .map { it[OrgUsers.organizationId] }
            .distinct()
            .forEach { orgId ->
                AuditService.log(
                    orgId, userId, "update.email", "user", userId.toString(),
                    entityDisplayName = displayName,
                    diff = emailDiff,
                )
            }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        java.security.SecureRandom().nextBytes(bytes)
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ── Export sections ──

    private fun exportSessions(userId: UUID, currentSessionId: UUID): List<ExportSession> =
        Sessions.selectAll()
            .where { Sessions.userId eq userId }
            .orderBy(Sessions.createdAt, SortOrder.DESC)
            .map { row ->
                ExportSession(
                    ipAddress = row[Sessions.ipAddress],
                    userAgent = row[Sessions.userAgent],
                    createdAt = row[Sessions.createdAt].toString(),
                    lastActiveAt = row[Sessions.lastActiveAt].toString(),
                    expiresAt = row[Sessions.expiresAt].toString(),
                    revoked = row[Sessions.revoked],
                    current = row[Sessions.id] == currentSessionId,
                )
            }

    private fun exportMemberships(userId: UUID): List<ExportOrgMembership> =
        (OrgUsers innerJoin Organizations).selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                (OrgUsers.deleted eq false) and
                (Organizations.deleted eq false)
            }
            .map { row ->
                val groups = (OrgUserGroups innerJoin OrgGroups)
                    .select(OrgGroups.name)
                    .where { OrgUserGroups.orgUserId eq row[OrgUsers.id] }
                    .map { it[OrgGroups.name] }
                ExportOrgMembership(
                    organizationId = row[Organizations.id].toString(),
                    organizationName = row[Organizations.name],
                    status = row[OrgUsers.status],
                    joinedAt = row[OrgUsers.joinedAt]?.toString(),
                    isOwner = row[Organizations.ownerId] == userId,
                    groups = groups,
                )
            }

    /**
     * Direct per-resource grants held by the subject.
     *
     * `resource_permissions` never keys on an account: the only person-shaped
     * principal is `'org_user'` and `principal_id` holds the **membership** id
     * (the column's CHECK constraint permits only `'org_user'` and
     * `'org_group'`, so a `'user'` principal cannot exist — matching on one
     * returned an empty section for every data subject, which read as "you hold
     * no grants" rather than as the bug it was). So the subject's memberships
     * are resolved first and the grants are looked up by those ids.
     *
     * Soft-deleted memberships are included deliberately: a grant that is still
     * stored is still personal data, whatever the state of the row it hangs off.
     */
    private fun exportResourceGrants(userId: UUID): List<ExportResourceGrant> {
        val membershipIds = OrgUsers.select(OrgUsers.id)
            .where { OrgUsers.userId eq userId }
            .map { it[OrgUsers.id] }
        if (membershipIds.isEmpty()) return emptyList()

        return ResourcePermissions.selectAll()
            .where {
                (ResourcePermissions.principalType eq "org_user") and
                (ResourcePermissions.principalId inList membershipIds)
            }
            .map { row ->
                ExportResourceGrant(
                    organizationId = row[ResourcePermissions.orgId].toString(),
                    resourceType = row[ResourcePermissions.resourceType],
                    resourceId = row[ResourcePermissions.resourceId].toString(),
                    permissions = row[ResourcePermissions.permissions],
                )
            }
    }

    /**
     * Audit entries the caller appears in, on **either** side.
     *
     * `user_id` is the actor column. Filtering on it alone disclosed only what
     * the caller did, never what was done to them — being invited, removed,
     * enabled, added to a group all carry someone else's actor id, and those are
     * exactly the entries most likely to hold the caller's own email in
     * `entity_display_name` or the comment.
     *
     * The subject side is resolved from what the row already carries, with no
     * second link column:
     *
     *  - `entity_type = 'user'` makes `entity_id` the subject's account id — an
     *    exact match, covering every entry whose entity IS a person;
     *  - anything else that names them does so by spelling out their **email
     *    address**, so the address itself is the handle (invite rows name the
     *    invite as the entity, group membership rows the group).
     *
     * This is the same pair of rules the purge job scrubs on, deliberately: the
     * export must disclose exactly the set erasure would later reach. It shares
     * the same blind spot — a row that names the caller by display name alone,
     * without their address and without a user entity, is matched by neither.
     */
    private fun exportAuditLog(userId: UUID, email: String): List<ExportAuditEntry> =
        OrgAuditLog.selectAll()
            .where { (OrgAuditLog.userId eq userId) or subjectPredicate(userId, email) }
            .orderBy(OrgAuditLog.createdAt, SortOrder.DESC)
            .map { row ->
                ExportAuditEntry(
                    organizationId = row[OrgAuditLog.organizationId].toString(),
                    action = row[OrgAuditLog.action],
                    role = auditRole(row[OrgAuditLog.userId] == userId, row.isAboutSubject(userId, email)),
                    entityType = row[OrgAuditLog.entityType],
                    entityId = row[OrgAuditLog.entityId],
                    entityDisplayName = row[OrgAuditLog.entityDisplayName],
                    diff = row[OrgAuditLog.diff],
                    comment = row[OrgAuditLog.comment],
                    createdAt = row[OrgAuditLog.createdAt].toString(),
                )
            }

    /** SQL half of the subject rules documented on [exportAuditLog]. */
    private fun subjectPredicate(userId: UUID, email: String): Op<Boolean> =
        ((OrgAuditLog.entityType eq "user") and (OrgAuditLog.entityId eq userId.toString())) or
            containsIgnoreCase(OrgAuditLog.entityDisplayName, email) or
            containsIgnoreCase(OrgAuditLog.comment, email) or
            containsIgnoreCase(OrgAuditLog.diff, email)

    /** In-Kotlin half of the same rules, for labelling the row's [ExportAuditEntry.role]. */
    private fun ResultRow.isAboutSubject(userId: UUID, email: String): Boolean {
        if (this[OrgAuditLog.entityType] == "user" && this[OrgAuditLog.entityId] == userId.toString()) return true
        val haystacks = listOf(
            this[OrgAuditLog.entityDisplayName],
            this[OrgAuditLog.comment],
            this[OrgAuditLog.diff]?.toString(),
        )
        return haystacks.any { it != null && it.contains(email, ignoreCase = true) }
    }

    /**
     * `strpos(lower(col::text), lower(?)) > 0` — a case-insensitive substring
     * test that reads varchar, text and jsonb alike. Deliberately not LIKE:
     * `_` and `%` are legal in an email local part and would be wildcards.
     */
    private fun containsIgnoreCase(column: Expression<*>, needle: String): Op<Boolean> =
        object : Op<Boolean>() {
            override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
                +"strpos(lower(coalesce(cast("
                +column
                +" as text), '')), lower("
                +stringParam(needle)
                +")) > 0"
            }
        }

    /** Which side of the entry the caller is on — see [ExportAuditEntry.role]. */
    private fun auditRole(isActor: Boolean, isSubject: Boolean): String = when {
        isActor && isSubject -> "both"
        isActor -> "actor"
        else -> "subject"
    }

    private fun exportApiKeys(userId: UUID): List<ExportApiKey> =
        ApiKeys.selectAll()
            .where { (ApiKeys.createdBy eq userId) and (ApiKeys.deleted eq false) }
            .map { row ->
                ExportApiKey(
                    organizationId = row[ApiKeys.organizationId].toString(),
                    name = row[ApiKeys.name],
                    lastUsedAt = row[ApiKeys.lastUsedAt]?.toString(),
                    expiresAt = row[ApiKeys.expiresAt]?.toString(),
                    revoked = row[ApiKeys.revoked],
                    createdAt = row[ApiKeys.createdAt].toString(),
                )
            }

    private fun exportSilences(userId: UUID): List<ExportNotificationSilence> =
        (NotificationSilences innerJoin OrgUsers).selectAll()
            .where { OrgUsers.userId eq userId }
            .map { row ->
                ExportNotificationSilence(
                    organizationId = row[OrgUsers.organizationId].toString(),
                    channel = row[NotificationSilences.channel],
                    workspaceId = row[NotificationSilences.workspaceId]?.toString(),
                    projectId = row[NotificationSilences.projectId]?.toString(),
                    serviceId = row[NotificationSilences.serviceId]?.toString(),
                    quietHours = row[NotificationSilences.quietHours],
                    config = row[NotificationSilences.config],
                )
            }

    /**
     * Variables the user created, across all four scopes — metadata only.
     * Values are write-only through the API and stay out of the export.
     */
    private fun exportVariables(userId: UUID): List<ExportVariable> {
        fun collect(
            table: Table,
            createdBy: Column<UUID?>,
            deleted: Column<Boolean>,
            scope: String,
            scopeId: Column<UUID>,
            key: Column<String>,
            secret: Column<Boolean>,
            createdAt: Column<Instant>,
            updatedAt: Column<Instant>,
        ): List<ExportVariable> = table.selectAll()
            .where { (createdBy eq userId) and (deleted eq false) }
            .map { row: ResultRow ->
                ExportVariable(
                    scope = scope,
                    scopeId = row[scopeId].toString(),
                    key = row[key],
                    secret = row[secret],
                    createdAt = row[createdAt].toString(),
                    updatedAt = row[updatedAt].toString(),
                )
            }

        return collect(
            OrgVariables, OrgVariables.createdBy, OrgVariables.deleted, "org",
            OrgVariables.organizationId, OrgVariables.key, OrgVariables.secret,
            OrgVariables.createdAt, OrgVariables.updatedAt,
        ) + collect(
            WorkspaceVariables, WorkspaceVariables.createdBy, WorkspaceVariables.deleted, "workspace",
            WorkspaceVariables.workspaceId, WorkspaceVariables.key, WorkspaceVariables.secret,
            WorkspaceVariables.createdAt, WorkspaceVariables.updatedAt,
        ) + collect(
            ProjectVariables, ProjectVariables.createdBy, ProjectVariables.deleted, "project",
            ProjectVariables.projectId, ProjectVariables.key, ProjectVariables.secret,
            ProjectVariables.createdAt, ProjectVariables.updatedAt,
        ) + collect(
            ServiceVariables, ServiceVariables.createdBy, ServiceVariables.deleted, "service",
            ServiceVariables.serviceId, ServiceVariables.key, ServiceVariables.secret,
            ServiceVariables.createdAt, ServiceVariables.updatedAt,
        )
    }

    /**
     * Notification-delivery history addressed to the subject's email address.
     *
     * Matched the same way the purge reaches these rows — case-insensitively on
     * `recipient` (the purge runs `DELETE FROM notification_log WHERE
     * lower(recipient) IN (…emails…)`) — so what Art. 15 access discloses is
     * exactly what Art. 17 erasure deletes. Webhook rows carry a URL in
     * `recipient`, not an email, so they do not match.
     */
    private fun exportNotificationLog(email: String): List<ExportNotificationLogEntry> =
        NotificationLog.selectAll()
            .where { NotificationLog.recipient.lowerCase() eq email.lowercase() }
            .orderBy(NotificationLog.createdAt, SortOrder.DESC)
            .map { row ->
                ExportNotificationLogEntry(
                    organizationId = row[NotificationLog.organizationId].toString(),
                    channel = row[NotificationLog.channel],
                    recipient = row[NotificationLog.recipient],
                    status = row[NotificationLog.status],
                    error = row[NotificationLog.error],
                    createdAt = row[NotificationLog.createdAt].toString(),
                )
            }

    /** Pending invites the user sent. The invite token itself is never exported. */
    private fun exportSentInvites(userId: UUID): List<ExportSentInvite> =
        OrgUsers.join(Users, JoinType.INNER, onColumn = OrgUsers.userId, otherColumn = Users.id)
            .selectAll()
            .where {
                (OrgUsers.invitedBy eq userId) and
                (OrgUsers.status eq "invited") and
                (OrgUsers.deleted eq false)
            }
            .map { row ->
                ExportSentInvite(
                    organizationId = row[OrgUsers.organizationId].toString(),
                    email = row[Users.email],
                    invitedAt = row[OrgUsers.invitedAt]?.toString(),
                    inviteExpiresAt = row[OrgUsers.inviteExpiresAt]?.toString(),
                )
            }
}
