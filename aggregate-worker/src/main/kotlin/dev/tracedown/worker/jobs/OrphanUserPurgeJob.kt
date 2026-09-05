package dev.tracedown.worker.jobs

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.onboarding.AccountLifecycle
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.OrphanUserPurgeJob")

/**
 * Schedules accounts that belong to no organization for deletion.
 *
 * The synchronous path ([AccountLifecycle.reconcile]) handles the common case —
 * a user removed from their last org. This job is the safety net that also
 * catches **onboarding abandonment**: an account created but never provisioned
 * into an org (e.g. a registration flow that was started and never finished).
 * Such accounts are given [AccountLifecycle.ORPHAN_GRACE_SECONDS]
 * before they qualify, so in-flight onboarding is never disturbed.
 *
 * It only soft-deletes (sets purge_after = now); the physical delete + cascade
 * is done by [PurgeJob] on its next run.
 */
class OrphanUserPurgeJob(
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "OrphanUserPurgeJob"

    override suspend fun execute() {
        val marked = ioTransaction {
            AccountLifecycle.markAbandonedOrphans()
        }

        if (marked > 0) {
            log.info("Orphan user purge: {} account(s) with no organization scheduled for deletion", marked)
        }
    }
}
