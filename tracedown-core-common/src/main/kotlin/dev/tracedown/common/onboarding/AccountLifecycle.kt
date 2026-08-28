package dev.tracedown.common.onboarding

import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.TotpRecoveryCodes
import dev.tracedown.common.models.Users
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.notExists
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID

/**
 * Account/membership lifecycle policy shared across the platform.
 *
 * The rules (see the org-membership states on [OrgUsers]):
 *  - **active** membership  = `status = "active" AND deleted = false`
 *  - **inactive** membership = `deleted = false` but not active (e.g. a pending
 *    `"invited"` row, or a suspended member)
 *  - **removed** membership = `deleted = true` (three-tier, awaiting purge)
 *
 * An account may sign in only while it holds at least one *active* membership.
 * An account is kept as long as it holds any *non-deleted* membership (active or
 * inactive); once it holds none it is scheduled for deletion. If a deleted
 * account regains a membership (e.g. re-invited within the grace window) it is
 * revived.
 *
 * All methods here operate on the current Exposed transaction — call them from
 * within a `transaction { }` block (they never open their own).
 */
object AccountLifecycle {

    /**
     * Grace window before an *orphaned* (0-membership) account becomes purgeable.
     *
     * This is the window for an account that lost its last membership without
     * asking to be closed — the last org it belonged to was deleted, or an admin
     * removed it. Nobody at that keyboard requested anything, and a re-invite
     * within the window revives the row, so the account is held rather than
     * dropped.
     *
     * It is deliberately NOT the window for a deliberate self-service closure:
     * that is a request, and the operator's own retention setting governs how
     * long a deleted thing is kept. See the [reconcile] `graceSeconds` override.
     */
    const val ORPHAN_GRACE_SECONDS: Long = 7 * 86_400L

    /**
     * Extra grace after an invite's own expiry before the never-accepted invite
     * is swept. The invite is already dead to the recipient; this only defers the
     * data cleanup, so a modest window is enough (and keeps a late re-invite —
     * which resurrects the same row — from racing the sweep).
     */
    const val EXPIRED_INVITE_GRACE_SECONDS: Long = 7 * 86_400L

    /** True when [userId] holds at least one active (sign-in-capable) membership. */
    fun hasActiveMembership(userId: UUID): Boolean =
        OrgUsers.selectAll()
            .where {
                (OrgUsers.userId eq userId) and
                (OrgUsers.status eq "active") and
                (OrgUsers.deleted eq false)
            }
            .limit(1)
            .any()

    /** True when [userId] holds any non-deleted membership (active or inactive). */
    fun hasAnyMembership(userId: UUID): Boolean =
        OrgUsers.selectAll()
            .where { (OrgUsers.userId eq userId) and (OrgUsers.deleted eq false) }
            .limit(1)
            .any()

    /**
     * Reconciles an account against its current memberships after a membership
     * gain or loss:
     *  - no non-deleted membership  → soft-delete the account (three-tier), with
     *    [graceSeconds] before it becomes purgeable.
     *  - has a membership again, but the account was soft-deleted → revive it.
     *
     * [graceSeconds] defaults to [ORPHAN_GRACE_SECONDS], which is right for every
     * caller that reaches here because a membership went away underneath the
     * account. A caller acting on the account holder's own explicit request
     * passes the operator's configured retention instead — see
     * `systemLimits.purgeRetentionDays`, whose whole point is that zero means
     * "gone means gone". A grace window nobody asked for would quietly defeat it.
     *
     * No-op when the account is already in the correct state, or does not exist.
     */
    fun reconcile(
        userId: UUID,
        now: Instant = Instant.now(),
        graceSeconds: Long = ORPHAN_GRACE_SECONDS,
    ) {
        val user = Users.selectAll().where { Users.id eq userId }.firstOrNull() ?: return
        val alreadyDeleted = user[Users.deleted]

        if (hasAnyMembership(userId)) {
            if (alreadyDeleted) revive(userId)
        } else if (!alreadyDeleted) {
            purgePersonalData(userId)
            Users.update({ Users.id eq userId }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = now.plusSeconds(graceSeconds)
                it[isActive] = false
            }
        }
    }

    /**
     * Hard-deletes personal data that must not outlive the account:
     *  - **Sessions** — a removed account is denied sign-in, but any live bearer
     *    tokens would otherwise keep authenticating API calls until they expire.
     *    Killing them here revokes access immediately, at removal time.
     *  - **Notification silences** — keyed on the org membership, so once the
     *    account is soft-deleted they are dead weight; clearing them means a later
     *    revive (re-invite) or account reclaim (signup) never inherits the
     *    previous owner's silences.
     *
     * Runs in the caller's transaction. Abandoned-orphan signups never had a
     * session or a membership, so [markAbandonedOrphans] needs no equivalent.
     */
    private fun purgePersonalData(userId: UUID) {
        Sessions.deleteWhere { Sessions.userId eq userId }

        val orgUserIds = OrgUsers.selectAll()
            .where { OrgUsers.userId eq userId }
            .map { it[OrgUsers.id] }
        if (orgUserIds.isNotEmpty()) {
            NotificationSilences.deleteWhere { orgUserId inList orgUserIds }
        }
    }

    /**
     * Schedules for deletion every account that holds no non-deleted membership
     * and was created longer ago than [ORPHAN_GRACE_SECONDS] — the sign-up
     * abandonment sweep. Runs in the current transaction; returns the count
     * marked. The physical delete is left to the purge job.
     */
    fun markAbandonedOrphans(now: Instant = Instant.now()): Int {
        val cutoff = now.minusSeconds(ORPHAN_GRACE_SECONDS)
        return Users.update({
            (Users.deleted eq false) and
            (Users.createdAt less cutoff) and
            notExists(
                OrgUsers.selectAll().where {
                    (OrgUsers.userId eq Users.id) and (OrgUsers.deleted eq false)
                }
            )
        }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = now
            it[isActive] = false
        }
    }

    /**
     * Sweeps never-accepted invites whose expiry lapsed more than
     * [EXPIRED_INVITE_GRACE_SECONDS] ago.
     *
     * An invite pre-creates a stub `users` row plus an `"invited"` membership at
     * invite time. That membership makes the account look "belonging to an org",
     * so [markAbandonedOrphans] never catches it — the invitee's email would sit
     * in the stub row forever. This soft-deletes each such expired membership and
     * then [reconcile]s its account: a stub whose only tie was this invite is left
     * holding no membership and is scheduled for deletion (its email purged with
     * it); an already-real account that merely had a stale invite to another org
     * keeps its account, only the dead membership goes.
     *
     * Runs in the current transaction; returns the number of memberships swept.
     * The physical delete is left to the purge job.
     */
    fun sweepExpiredInvites(now: Instant = Instant.now()): Int {
        val cutoff = now.minusSeconds(EXPIRED_INVITE_GRACE_SECONDS)
        val expired = OrgUsers.selectAll()
            .where {
                (OrgUsers.status eq "invited") and
                (OrgUsers.deleted eq false) and
                (OrgUsers.inviteExpiresAt.isNotNull()) and
                (OrgUsers.inviteExpiresAt less cutoff)
            }
            .map { it[OrgUsers.id] to it[OrgUsers.userId] }
        if (expired.isEmpty()) return 0

        val membershipIds = expired.map { it.first }
        OrgUsers.update({ OrgUsers.id inList membershipIds }) {
            it[deleted] = true
            it[deletedAt] = now
            it[isActive] = false
        }
        // Reconcile each affected account: a stub left with no membership is
        // scheduled for deletion; a real account elsewhere is untouched.
        expired.map { it.second }.distinct().forEach { reconcile(it, now) }
        return membershipIds.size
    }

    /**
     * Brings a soft-deleted account back as a **brand-new account**, reusing the
     * row (its id and email — email is globally unique so a fresh row would
     * collide) but wiping everything of the prior owner. It is reset to exactly
     * the clean stub a first-time invite creates: no password, no 2FA, no
     * preferences, inactive until acceptance. The credential + display name are
     * set later by the invite-accept flow; the prior owner's 2FA recovery codes
     * and any live sessions are hard-deleted here.
     *
     * Runs in the caller's transaction. No-op if the account no longer exists.
     */
    fun revive(userId: UUID) {
        val email = Users.selectAll().where { Users.id eq userId }.firstOrNull()?.get(Users.email) ?: return
        Users.update({ Users.id eq userId }) {
            it[passwordHash] = ""
            it[displayName] = email.substringBefore("@")
            it[totpSecretEncrypted] = null
            it[totpSecretIv] = null
            it[totpEnrolledAt] = null
            it[totpLastUsedAt] = null
            it[totpLastStep] = null
            it[totpFailedAttempts] = 0
            it[totpLockedUntil] = null
            it[totpEnabled] = false
            it[selectedOrgId] = null
            it[isActive] = false
            it[deleted] = false
            it[deletedAt] = null
            it[purgeAfter] = null
            it[createdAt] = Instant.now()
        }
        TotpRecoveryCodes.deleteWhere { TotpRecoveryCodes.userId eq userId }
        Sessions.deleteWhere { Sessions.userId eq userId }
    }
}
