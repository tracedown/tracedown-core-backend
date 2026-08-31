package dev.tracedown.worker.jobs

import dev.tracedown.common.models.PendingBodyDeletions
import dev.tracedown.common.storage.BodyStorageClient
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.BodyDeletionRetryJob")

/**
 * Finishes the object-storage deletions that [PurgeJob] and [RetentionJob] could
 * not complete.
 *
 * Those jobs delete a stored response body from the bucket and then delete the
 * `probe_steps` row naming it, and they delete the row even when the storage
 * call failed — otherwise one unreachable bucket would stall erasure for
 * everyone. Before this job existed that made the failure permanent: the URI
 * lived only in the row that had just been dropped, so a transient outage
 * during a purge left personal data in the bucket forever, outside both
 * retention and erasure.
 *
 * They now write the URI to `pending_body_deletions` first. This job drains that
 * table, oldest attempt first, until each object is actually gone.
 *
 * **Nothing is given up on.** A row that keeps failing is retried indefinitely
 * and reported by attempt count, because deleting the row would recreate exactly
 * the orphan the table exists to prevent. A URI that no longer exists in storage
 * counts as deleted — [BodyStorageClient.delete] does not treat a missing object
 * as an error — so a bucket cleaned out by other means drains this table too.
 */
class BodyDeletionRetryJob(
    private val storageClient: BodyStorageClient,
    override val intervalSeconds: Long = 900L,
    private val batchSize: Int = 200,
    /** Attempts after which a row is reported as stuck on every pass. */
    private val loudAfterAttempts: Int = 10,
) : ScheduledJob {

    override val name = "BodyDeletionRetryJob"

    override suspend fun execute() {
        val pending = newSuspendedTransaction(Dispatchers.IO) {
            PendingBodyDeletions
                .select(PendingBodyDeletions.storageUrl, PendingBodyDeletions.attempts)
                .orderBy(PendingBodyDeletions.lastAttemptAt, SortOrder.ASC)
                .limit(batchSize)
                .map { it[PendingBodyDeletions.storageUrl] to it[PendingBodyDeletions.attempts] }
        }
        if (pending.isEmpty()) return

        // Object-store round trips stay outside the transaction: a slow or
        // unreachable store must not hold a pooled connection for the batch.
        val settled = mutableListOf<String>()
        val stillFailing = mutableListOf<Pair<String, String?>>()

        for ((uri, attempts) in pending) {
            try {
                storageClient.delete(uri)
                settled.add(uri)
            } catch (e: Exception) {
                stillFailing.add(uri to e.message)
                if (attempts + 1 >= loudAfterAttempts) {
                    log.error(
                        "Stored body {} has failed to delete {} times — the object is still in storage " +
                            "and outside retention; investigate the backend",
                        uri, attempts + 1,
                    )
                }
            }
        }

        newSuspendedTransaction(Dispatchers.IO) {
            PendingBodyDeletion.clear(settled)
            stillFailing.forEach { (uri, error) -> PendingBodyDeletion.record(listOf(uri), error) }
        }

        if (settled.isNotEmpty()) {
            log.info("Body deletion retry: {} previously-orphaned object(s) deleted", settled.size)
        }
        if (stillFailing.isNotEmpty()) {
            log.warn("Body deletion retry: {} object(s) still pending deletion", stillFailing.size)
        }
    }
}
