package dev.tracedown.worker.jobs

import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.BodyNotStoredReasons
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.RetentionJob")

/**
 * Ages out raw probe data. Two windows, two passes, one tick.
 *
 * Runs every 1 hour. Per-org windows are resolved via
 * [PlatformDefaults.retentionConfig], which defaults to the global values from
 * config unless an external module overrides them.
 *
 *  - **Body pass** (first) — for results past the *body* window, deletes the
 *    stored response body via [BodyStorageClient] and blanks
 *    `probe_steps.response_body_storage_url`, recording
 *    [BodyNotStoredReasons.BODY_EXPIRED] in its place. The result, its steps and
 *    their timings all stay.
 *  - **Result pass** (second) — for results past the *result* window, deletes
 *    the bodies and then the rows themselves, leaf-first to respect FK
 *    constraints.
 *
 * The two windows are independent settings but not independent lifetimes: a
 * body is reachable only through its `probe_steps` row, so the result pass takes
 * the body with the row whether or not the body window has passed. Effective
 * body lifetime is therefore `min(body, result)` when both windows are set, and
 * "as long as the result" when the body window is off. Turning the *result*
 * window off does not turn the body window off — bodies still expire on their
 * own schedule.
 *
 * Both windows treat `<= 0` as "never expire by age"; with both off the job does
 * nothing at all.
 *
 * Bodies in an `in_place` body store (`probe_steps.body_store_id` set) are the
 * store owner's: neither pass ever deletes one. The result pass still drops the
 * rows.
 *
 * **Bounded by construction.** Every query here either projects a single column
 * or is capped by [RetentionBatching.DEFAULT_BATCH_SIZE]; nothing reads the raw
 * result payloads, and nothing materialises a set whose size is a function of
 * the backlog. Both passes share one tick budget. See [RetentionBatching] for
 * why that matters — this job shares a process with aggregation, purge and
 * session cleanup.
 */
class RetentionJob(
    private val defaultRetentionDays: Int,
    private val storageClient: BodyStorageClient,
    /**
     * Global body window in days; `<= 0` never expires a body by age. Defaults
     * to the result window's own default so a caller that predates the split
     * keeps today's behaviour exactly.
     */
    private val defaultBodyRetentionDays: Int = 90,
    override val intervalSeconds: Long = 3600L,
    private val batchSize: Int = RetentionBatching.DEFAULT_BATCH_SIZE,
    private val tickBudget: Duration = RetentionBatching.DEFAULT_TICK_BUDGET,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "RetentionJob"

    override suspend fun execute() {
        if (defaultRetentionDays <= 0 && defaultBodyRetentionDays <= 0) {
            log.debug(
                "Retention disabled (resultRetentionDays={}, bodyRetentionDays={})",
                defaultRetentionDays, defaultBodyRetentionDays,
            )
            return
        }

        val tickStart = clock()

        // Distinct orgs with results older than the shortest possible retention
        // (1 day). One projected column, deduplicated by the database — this
        // used to select every column of every expired row, raw payloads
        // included, and deduplicate them in the JVM.
        val cutoffScan = tickStart.minus(1, ChronoUnit.DAYS)
        val orgIds = ioTransaction {
            ProbeResults.select(ProbeResults.organizationId)
                .where { ProbeResults.startedAt less cutoffScan }
                .withDistinct()
                .map { it[ProbeResults.organizationId] }
        }

        if (orgIds.isEmpty()) return

        var totalDeleted = 0L
        var totalBodiesExpired = 0L
        var budgetSpent = false

        for (orgId in orgIds) {
            if (RetentionBatching.budgetSpent(Duration.between(tickStart, clock()), tickBudget)) {
                budgetSpent = true
                break
            }

            // Body pass first: a body that is past both windows is deleted here
            // by the object-store call the result pass would have made anyway,
            // and the result pass then finds nothing left to delete for it.
            val bodyDays = PlatformDefaults.retentionConfig.bodyRetentionDays(orgId)
                .let { if (it <= 0) defaultBodyRetentionDays else it }
            if (bodyDays > 0) {
                val bodyCutoff = clock().minus(bodyDays.toLong(), ChronoUnit.DAYS)
                val (expired, bodyStoppedOnBudget) = expireBodies(orgId, bodyCutoff, tickStart)
                totalBodiesExpired += expired
                if (expired > 0) {
                    log.info("Retention: expired {} bodies for org {} (bodyRetention={}d)", expired, orgId, bodyDays)
                }
                if (bodyStoppedOnBudget) {
                    budgetSpent = true
                    break
                }
            }

            val retentionDays = PlatformDefaults.retentionConfig.resultRetentionDays(orgId)
                .let { if (it <= 0) defaultRetentionDays else it }
            if (retentionDays <= 0) continue

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

        if (totalDeleted > 0 || totalBodiesExpired > 0) {
            log.info(
                "Retention job completed: {} results purged and {} bodies expired across {} orgs",
                totalDeleted, totalBodiesExpired, orgIds.size,
            )
        }
        if (budgetSpent) {
            // Not an error: the remainder is still expired next tick. Logged so
            // a permanently-behind backlog is visible rather than silent.
            log.warn(
                "Retention tick hit its {}s budget after {} deletions and {} expired bodies — resuming next tick",
                tickBudget.seconds, totalDeleted, totalBodiesExpired,
            )
        }
    }

    /**
     * Expires one org's stored response bodies past [cutoff], in batches, without
     * touching the results they belong to.
     *
     * The page is driven off `probe_results` — its `(organization_id, started_at)`
     * index is the only thing here that can answer "whose bodies are old" — and
     * joined to `probe_steps` on the FK index. Bodies in an `in_place` store
     * (`body_store_id IS NOT NULL`) are excluded: those belong to the store
     * owner, not to the platform.
     *
     * A body whose object-store delete fails keeps its `response_body_storage_url`
     * and is recorded in `pending_body_deletions`. That is the opposite of the
     * result pass, and deliberately so: there the row is going regardless, so the
     * pending table is the only way to keep the URI referenced, while here the row
     * survives and *is* the reference. Clearing the column on a failed delete
     * would strand the object exactly the way the pending table exists to prevent.
     *
     * @return bodies expired, and whether the pass stopped on the tick budget
     */
    private suspend fun expireBodies(orgId: UUID, cutoff: Instant, tickStart: Instant): Pair<Long, Boolean> {
        var expired = 0L
        var rounds = 0

        while (true) {
            val page = ioTransaction {
                ProbeSteps
                    .join(ProbeResults, JoinType.INNER, ProbeSteps.probeResultId, ProbeResults.id)
                    .select(ProbeSteps.id, ProbeSteps.responseBodyStorageUrl)
                    .where {
                        (ProbeResults.organizationId eq orgId) and
                            (ProbeResults.startedAt less cutoff) and
                            ProbeSteps.responseBodyStorageUrl.isNotNull() and
                            ProbeSteps.bodyStoreId.isNull()
                    }
                    .limit(batchSize)
                    .map { it[ProbeSteps.id] to it[ProbeSteps.responseBodyStorageUrl]!! }
            }

            if (page.isEmpty()) return expired to false

            // Object-store round trips happen between transactions, as in the
            // result pass: a hung store must not hold a pooled connection.
            val failed = storageClient.deleteAll(page.map { it.second }.distinct())
            failed.forEach { (uri, error) -> log.warn("Failed to expire body at {}: {}", uri, error) }

            if (failed.isNotEmpty()) {
                ioTransaction {
                    failed.forEach { (uri, error) -> PendingBodyDeletion.record(listOf(uri), error) }
                }
            }

            // Only the steps whose object actually went lose their URL. A URI
            // the confined client refused counts as gone for this purpose — it
            // names a body the platform does not own and never will delete, so
            // holding the column would re-select it on every tick forever.
            val settled = page.filter { it.second !in failed.keys }.map { it.first }
            val cleared = if (settled.isEmpty()) 0 else ioTransaction {
                ProbeSteps.update({ ProbeSteps.id inList settled }) {
                    it[responseBodyStorageUrl] = null
                    it[bodyNotStoredReason] = BodyNotStoredReasons.BODY_EXPIRED
                }
            }
            expired += cleared.toLong()
            rounds++
            log.info(
                "Retention: org {} body batch {} — {} bodies, {} expired, {} failed",
                orgId, rounds, page.size, cleared, failed.size,
            )

            // A round that cleared nothing cannot make progress: every row in it
            // failed and will be selected again unchanged. Stop rather than spin
            // the tick budget away on a store that is down — the deletion retry
            // job owns them now, and the next tick tries again.
            if (cleared == 0) return expired to false

            when (RetentionBatching.verdict(
                rowsInRound = page.size,
                batchSize = batchSize,
                elapsed = Duration.between(tickStart, clock()),
                budget = tickBudget,
            )) {
                RetentionBatching.Verdict.ORG_DRAINED -> return expired to false
                RetentionBatching.Verdict.BUDGET_SPENT -> return expired to true
                RetentionBatching.Verdict.CONTINUE -> Unit
            }
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
        var batches = 0

        while (true) {
            // One bounded page of ids. `LIMIT` is what keeps this off the heap:
            // the page is the same size whether the org has a thousand expired
            // results or ten million.
            val resultIds = ioTransaction {
                ProbeResults.select(ProbeResults.id)
                    .where { (ProbeResults.organizationId eq orgId) and (ProbeResults.startedAt less cutoff) }
                    .limit(batchSize)
                    .map { it[ProbeResults.id] }
            }

            if (resultIds.isEmpty()) return deleted to false

            // Body URIs for exactly this page, projected on their own — the old
            // query read whole step rows to reach one nullable column. Bodies in
            // an in_place body store are the store owner's to keep or delete.
            val bodyUris = ioTransaction {
                ProbeSteps.select(ProbeSteps.responseBodyStorageUrl)
                    .where { (ProbeSteps.probeResultId inList resultIds) and ProbeSteps.bodyStoreId.isNull() }
                    .mapNotNull { it[ProbeSteps.responseBodyStorageUrl] }
            }

            // Object-store round trips happen between transactions, not inside
            // one: a slow or unreachable store must not hold a pooled
            // connection open for the length of the page.
            // One bulk request per page rather than a round trip per body: at
            // ~100 ms an object, a page of 600 bodies used to cost a minute and
            // a whole tick budget drained nine pages.
            val bodiesStart = clock()
            val failed = storageClient.deleteAll(bodyUris).toList()
            failed.forEach { (uri, error) -> log.warn("Failed to delete body at {}: {}", uri, error) }
            val bodiesMs = Duration.between(bodiesStart, clock()).toMillis()
            // The first page of a tick says what the store is doing: a healthy
            // one answers in well under a second, and a hung one used to stall
            // retention with nothing in the log at all.
            if (batches == 0 && bodyUris.isNotEmpty()) {
                log.info(
                    "Retention: first body page for org {} — {} bodies in {} ms ({})",
                    orgId, bodyUris.size, bodiesMs, if (failed.isEmpty()) "ok" else "${failed.size} failed",
                )
            }

            // The rows below are deleted whether or not the objects went, which
            // used to lose the only reference to a body still sitting in the
            // bucket — bodies can carry personal data from the probed endpoint,
            // so an unreferenced one is outside retention and erasure for good.
            // Record the URI instead; BodyDeletionRetryJob finishes the job.
            if (failed.isNotEmpty()) {
                ioTransaction {
                    failed.forEach { (uri, error) -> PendingBodyDeletion.record(listOf(uri), error) }
                }
            }

            // Leaf-first: steps, then results.
            val removed = ioTransaction {
                ProbeSteps.deleteWhere { probeResultId inList resultIds }
                ProbeResults.deleteWhere { id inList resultIds }
            }
            deleted += removed.toLong()
            batches++
            log.info(
                "Retention: org {} batch {} — {} results, {} bodies ({} failed) in {} ms",
                orgId, batches, resultIds.size, bodyUris.size, failed.size, bodiesMs,
            )

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
