package dev.tracedown.worker.jobs

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.onboarding.AccountLifecycle
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.ExpiredInviteSweepJob")

/**
 * Cleans up never-accepted invites once their expiry has lapsed for good.
 *
 * An invite pre-creates a stub account plus an `"invited"` membership at invite
 * time (so the invitee can be pre-provisioned into groups). That membership keeps
 * the account from ever looking orphaned, so [OrphanUserPurgeJob] never catches
 * it and the invitee's email would otherwise be retained indefinitely. This
 * sweep soft-deletes the expired memberships and schedules any stub account left
 * with none for deletion — the physical purge is [PurgeJob]'s job.
 *
 * See [AccountLifecycle.sweepExpiredInvites] for the exact rules and grace window.
 */
class ExpiredInviteSweepJob(
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "ExpiredInviteSweepJob"

    override suspend fun execute() {
        val swept = ioTransaction {
            AccountLifecycle.sweepExpiredInvites()
        }
        if (swept > 0) {
            log.info("Expired invite sweep: {} never-accepted invite(s) removed", swept)
        }
    }
}
