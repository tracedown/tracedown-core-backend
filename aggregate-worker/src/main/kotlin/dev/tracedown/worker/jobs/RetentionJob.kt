package dev.tracedown.worker.jobs

import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.RetentionJob")

/**
 * Purges raw probe data (results, steps, snapshots) older than the retention period.
 *
 * Runs every 1 hour. Per-org retention is resolved via [PlatformDefaults.retentionConfig],
 * which defaults to the global value from config unless an external module overrides it.
 * Deletes stored response bodies via [BodyStorageClient], then removes
 * DB rows in leaf-first order to respect FK constraints.
 *
 * **Bounded by construction.** Every query here either projects a single column
 * or is capped by [RetentionBatching.DEFAULT_BATCH_SIZE]; nothing reads the raw
 * result payloads, and nothing materialises a set whose size is a function of
 * the backlog. See [RetentionBatching] for why that matters — this job shares a
 * process with aggregation, purge and session cleanup.
 */
class RetentionJob(
    private val defaultRetentionDays: Int,
    private val storageClient: BodyStorageClient,
    override val intervalSeconds: Long = 3600L,
    private val batchSize: Int = RetentionBatching.DEFAULT_BATCH_SIZE,
    private val tickBudget: Duration = RetentionBatching.DEFAULT_TICK_BUDGET,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "RetentionJob"

    override suspend fun execute() {
        if (defaultRetentionDays <= 0) {
            log.debug("Retention disabled (resultRetentionDays={})", defaultRetentionDays)
            return
        }

        val tickStart = clock()

        // Distinct orgs with results older than the shortest possible retention
        // (1 day). One projected column, deduplicated by the database — this
        // used to select every column of every expired row, raw payloads
        // included, and deduplicate them in the JVM.
        val cutoffScan = tickStart.minus(1, ChronoUnit.DAYS)
        val orgIds = newSuspendedTransaction(Dispatchers.IO) {
            ProbeResults.select(ProbeResults.organizationId)
                .where { ProbeResults.startedAt less cutoffScan }
                .withDistinct()
                .map { it[ProbeResults.organizationId] }
        }

        if (orgIds.isEmpty()) return

        var totalDeleted = 0L
        var budgetSpent = false

        for (orgId in orgIds) {
            if (RetentionBatching.budgetSpent(Duration.between(tickStart, clock()), tickBudget)) {
                budgetSpent = true
                break
            }

            val retentionDays = PlatformDefaults.retentionConfig.resultRetentionDays(orgId)
                .let { if (it <= 0) defaultRetentionDays else it }
            val cutoff = clock().minus(retentionDays.toLong(), ChronoUnit.DAYS)

            val (deleted, stoppedOnBudget) = purgeOrg(orgId, cutoff, tickStart)
            totalDeleted += deleted

            if (deleted > 0) {
                log.info("Retention: deleted {} results for org {} (retention={}d)", deleted, orgId, retentionDays)
            }
            if (stoppedOnBudget) {
                budgetSpent = true
                break
            }
        }

        if (totalDeleted > 0) {
            log.info("Retention job completed: {} total results purged across {} orgs", totalDeleted, orgIds.size)
        }
        if (budgetSpent) {
            // Not an error: the remainder is still expired next tick. Logged so
            // a permanently-behind backlog is visible rather than silent.
            log.warn(
                "Retention tick hit its {}s budget after {} deletions — resuming next tick",
                tickBudget.seconds, totalDeleted,
            )
        }
    }

    /**
     * Deletes one org's expired results in batches.
     *
     * @return rows deleted, and whether the pass stopped because the tick
     *   budget ran out rather than because the org was drained
     */
    private suspend fun purgeOrg(orgId: UUID, cutoff: Instant, tickStart: Instant): Pair<Long, Boolean> {
        var deleted = 0L

        while (true) {
            // One bounded page of ids. `LIMIT` is what keeps this off the heap:
            // the page is the same size whether the org has a thousand expired
            // results or ten million.
            val resultIds = newSuspendedTransaction(Dispatchers.IO) {
                ProbeResults.select(ProbeResults.id)
                    .where { (ProbeResults.organizationId eq orgId) and (ProbeResults.startedAt less cutoff) }
                    .limit(batchSize)
                    .map { it[ProbeResults.id] }
            }

            if (resultIds.isEmpty()) return deleted to false

            // Body URIs for exactly this page, projected on their own — the old
            // query read whole step rows to reach one nullable column.
            val bodyUris = newSuspendedTransaction(Dispatchers.IO) {
                ProbeSteps.select(ProbeSteps.responseBodyStorageUrl)
                    .where { ProbeSteps.probeResultId inList resultIds }
                    .mapNotNull { it[ProbeSteps.responseBodyStorageUrl] }
            }

            // Object-store round trips happen between transactions, not inside
            // one: a slow or unreachable store must not hold a pooled
            // connection open for the length of the page.
            for (uri in bodyUris) {
                try {
                    storageClient.delete(uri)
                } catch (e: Exception) {
                    log.warn("Failed to delete body at {}: {}", uri, e.message)
                }
            }

            // Leaf-first: steps, then results.
            val removed = newSuspendedTransaction(Dispatchers.IO) {
                ProbeSteps.deleteWhere { probeResultId inList resultIds }
                ProbeResults.deleteWhere { id inList resultIds }
            }
            deleted += removed.toLong()

            when (RetentionBatching.verdict(
                rowsInRound = resultIds.size,
                batchSize = batchSize,
                elapsed = Duration.between(tickStart, clock()),
                budget = tickBudget,
            )) {
                RetentionBatching.Verdict.ORG_DRAINED -> return deleted to false
                RetentionBatching.Verdict.BUDGET_SPENT -> return deleted to true
                RetentionBatching.Verdict.CONTINUE -> Unit
            }
        }
    }
}
