package dev.tracedown.worker.jobs

import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.BodyNotStoredReasons
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.worker.data.JobWatermarks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.notInList
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
 * [PlatformDefaults.retentionConfig], which has no opinion of its own — it
 * answers `null` for every organization and the global config values stand —
 * unless an external module overrides it.
 *
 *  - **Result pass** (first) — for results past the *result* window, deletes
 *    the bodies and then the rows themselves, leaf-first to respect FK
 *    constraints. It runs first because the result window is the promise made
 *    to the customer and to the regulator; the body window is an optimisation
 *    on top of it, and a tick that runs out of budget must run out of it in the
 *    optional half.
 *  - **Body pass** (second) — for results past the *body* window but still
 *    inside the result window, deletes the stored response body via
 *    [BodyStorageClient] and blanks `probe_steps.response_body_storage_url`,
 *    recording [BodyNotStoredReasons.BODY_EXPIRED] in its place. The result, its
 *    steps and their timings all stay.
 *
 * The two windows are independent settings but not independent lifetimes: a
 * body is reachable only through its `probe_steps` row, so the result pass takes
 * the body with the row whether or not the body window has passed. Effective
 * body lifetime is therefore `min(body, result)` when both windows are set, and
 * "as long as the result" when the body window is off. Turning the *result*
 * window off does not turn the body window off — bodies still expire on their
 * own schedule.
 *
 * A window is a number of days; negative means "never expire by age" and the
 * pass is skipped for that organization. Zero is not a value (config refuses it
 * at startup), and a host that returns it from the seam anyway is treated as
 * "never" with a warning rather than as "delete everything now". With *both*
 * global windows off the job does nothing at all — so a host that sets windows
 * per organization must leave at least one global window on, or none of its
 * per-organization windows are ever read.
 *
 * Bodies in an `in_place` body store (`probe_steps.body_store_id` set) are the
 * store owner's: neither pass ever deletes one. The result pass still drops the
 * rows.
 *
 * **Bounded by construction.** Every query here either projects a single column
 * or is capped by [RetentionBatching.DEFAULT_BATCH_SIZE]; nothing reads the raw
 * result payloads, and nothing materialises a set whose size is a function of
 * the backlog. Both passes share one tick budget, organizations are visited in
 * a deterministic order, and a tick that stops early resumes at the
 * organization it stopped on rather than starting from the top — otherwise the
 * organizations at the end of the list are never reached at all. See
 * [RetentionBatching] for why that matters: this job shares a process with
 * aggregation, purge and session cleanup.
 *
 * **Crash window.** Both passes delete the object first and clear the row
 * second, so a crash in between leaves a `probe_steps` row naming an object
 * that is already gone. The reference is harmless — reading it 404s and the
 * next pass clears it — and it is the safe side to fail on: the opposite order
 * loses the only record of a live object. `pending_body_deletions` covers the
 * mirror case (object still there, row gone) and the next tick, at most an hour
 * later, settles both.
 */
class RetentionJob(
    private val defaultRetentionDays: Int,
    private val storageClient: BodyStorageClient,
    /**
     * Global body window in days; negative never expires a body by age and the
     * body pass does not run at all. The default is -1 — unset, bodies simply
     * follow their results, exactly as they did before the two windows were
     * split, so an upgrade changes nothing for an installation that has never
     * configured one.
     */
    private val defaultBodyRetentionDays: Int = -1,
    override val intervalSeconds: Long = 3600L,
    private val batchSize: Int = RetentionBatching.DEFAULT_BATCH_SIZE,
    private val tickBudget: Duration = RetentionBatching.DEFAULT_TICK_BUDGET,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "RetentionJob"

    /**
     * The organization the next tick starts at, set when a tick stops on its
     * budget. Held in memory on purpose: it is a fairness hint, not state that
     * must survive a restart — losing it costs one tick that starts from the
     * top, and the passes themselves are idempotent.
     */
    private var resumeFrom: UUID? = null

    override suspend fun execute() {
        if (defaultRetentionDays < 0 && defaultBodyRetentionDays < 0) {
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
        // included, and deduplicate them in the JVM. Ordered, so the resume
        // cursor below means something.
        val cutoffScan = tickStart.minus(1, ChronoUnit.DAYS)
        val orgIds = ioTransaction {
            ProbeResults.select(ProbeResults.organizationId)
                .where { ProbeResults.startedAt less cutoffScan }
                .withDistinct()
                .orderBy(ProbeResults.organizationId to SortOrder.ASC)
                .map { it[ProbeResults.organizationId] }
        }

        if (orgIds.isEmpty()) {
            resumeFrom = null
            return
        }

        var totalDeleted = 0L
        var totalBodiesExpired = 0L
        var resultPassesFinished = 0
        var stoppedAt: UUID? = null

        for (orgId in resumeOrder(orgIds)) {
            if (RetentionBatching.budgetSpent(Duration.between(tickStart, clock()), tickBudget)) {
                stoppedAt = orgId
                break
            }

            // Result pass first — see the class doc. Its cutoff is also the
            // floor of the body pass: below it, bodies are this pass's to take.
            val retentionDays = window(
                PlatformDefaults.retentionConfig.resultRetentionDays(orgId), defaultRetentionDays, orgId, "result",
            )
            var resultCutoff: Instant? = null
            if (retentionDays != null) {
                resultCutoff = clock().minus(retentionDays.toLong(), ChronoUnit.DAYS)
                val (deleted, stoppedOnBudget) = purgeOrg(orgId, resultCutoff, tickStart)
                totalDeleted += deleted
                if (deleted > 0) {
                    log.info("Retention: deleted {} results for org {} (retention={}d)", deleted, orgId, retentionDays)
                }
                if (stoppedOnBudget) {
                    stoppedAt = orgId
                    break
                }
            }
            resultPassesFinished++

            val bodyDays = window(
                PlatformDefaults.retentionConfig.bodyRetentionDays(orgId), defaultBodyRetentionDays, orgId, "body",
            )
            if (bodyDays != null) {
                val bodyCutoff = clock().minus(bodyDays.toLong(), ChronoUnit.DAYS)
                val (expired, bodyStoppedOnBudget) = expireBodies(orgId, bodyCutoff, resultCutoff, tickStart)
                totalBodiesExpired += expired
                if (expired > 0) {
                    log.info("Retention: expired {} bodies for org {} (bodyRetention={}d)", expired, orgId, bodyDays)
                }
                if (bodyStoppedOnBudget) {
                    stoppedAt = orgId
                    break
                }
            }
        }

        // Where the next tick picks up. Null once the list was walked to the
        // end, so an unhurried tick always starts at the first organization.
        resumeFrom = stoppedAt

        if (totalDeleted > 0 || totalBodiesExpired > 0) {
            log.info(
                "Retention job completed: {} results purged and {} bodies expired across {} orgs",
                totalDeleted, totalBodiesExpired, orgIds.size,
            )
        }
        if (stoppedAt != null) {
            // Not an error: the remainder is still expired next tick, starting
            // at the organization named here. Logged so a permanently-behind
            // backlog is visible rather than silent.
            log.warn(
                "Retention tick hit its {}s budget after {} deletions and {} expired bodies — resuming at org {}",
                tickBudget.seconds, totalDeleted, totalBodiesExpired, stoppedAt,
            )
            if (resultPassesFinished == 0) {
                // The tick spent its whole budget without finishing a single
                // organization's result pass. The result window is the
                // compliance promise, so this is the one state an operator has
                // to act on: more workers, a longer budget, or a bigger batch.
                log.warn(
                    "Retention: no organization's result pass completed this tick — " +
                        "the result window is falling behind for {} organizations",
                    orgIds.size,
                )
            }
        }
    }

    /**
     * Resolves one window to a number of days, or null when the pass should not
     * run for this organization.
     *
     * `null` from the seam means the organization has no window of its own and
     * the global value stands. Negative means "never expire by age". Zero is
     * not a value — config refuses it at startup, so one arriving here came
     * from a host's own seam implementation; it is read as "never" and reported,
     * because the alternative reading deletes everything the organization has.
     */
    private fun window(orgValue: Int?, globalValue: Int, orgId: UUID, which: String): Int? {
        val days = orgValue ?: globalValue
        if (days == 0) {
            log.warn(
                "Retention: org {} resolved a {} window of 0 days, which is not a window — treating it as " +
                    "\"never expire by age\". Use a positive number of days, or a negative value to mean never.",
                orgId, which,
            )
            return null
        }
        return if (days < 0) null else days
    }

    /** The organization list rotated to start at [resumeFrom], or unchanged when there is none. */
    private fun resumeOrder(orgIds: List<UUID>): List<UUID> {
        val from = resumeFrom ?: return orgIds
        // The list is ordered by id, so "where we stopped" is the first id at or
        // after the cursor — the organization itself may have been deleted since.
        val index = orgIds.indexOfFirst { it >= from }
        if (index <= 0) return orgIds
        return orgIds.subList(index, orgIds.size) + orgIds.subList(0, index)
    }

    /**
     * Expires one org's stored response bodies past [bodyCutoff], in batches,
     * without touching the results they belong to.
     *
     * **The window is bounded at both ends.** The upper bound is the body
     * cutoff; the lower bound is the furthest this organization's body pass has
     * already reached ([JobWatermarks], keyed per organization), or — on the
     * first run, and whenever it is later — the result cutoff, since a body
     * older than that belongs to the result pass and is not this pass's work.
     * Without a lower bound the page is driven by a range that starts at the
     * beginning of time, and the planner answers it with a whole-table merge
     * join of `probe_results` and `probe_steps`: measured at 5M steps that cost
     * about a second per organization per tick even when there was nothing left
     * to expire, so at any scale the tick budget went on discovering nothing.
     * With the lower bound it is an index scan of one narrow `started_at` range
     * (see `V1789461261`).
     *
     * The watermark only advances when the whole window drained with nothing
     * left behind. A body whose delete failed keeps its
     * `response_body_storage_url`, so advancing past it would strand it outside
     * both passes until the result window caught up.
     *
     * One `job_watermarks` row per organization that has ever had a body
     * expired. A purged organization leaves its row behind; it is a name and
     * two timestamps, nothing reads it again, and a new organization can never
     * collide with it.
     *
     * Bodies in an `in_place` store (`body_store_id IS NOT NULL`) are excluded:
     * those belong to the store owner, not to the platform.
     *
     * A body whose object-store delete fails keeps its
     * `response_body_storage_url` and is recorded in `pending_body_deletions`.
     * That is the opposite of the result pass, and deliberately so: there the
     * row is going regardless, so the pending table is the only way to keep the
     * URI referenced, while here the row survives and *is* the reference.
     * Clearing the column on a failed delete would strand the object exactly
     * the way the pending table exists to prevent.
     *
     * A body the client *refuses* on confinement stops this organization's pass
     * outright. A refusal is never about one body: it means this worker's
     * `STORAGE_*` settings do not describe the storage the ingestor is writing
     * to, so nothing it is asked to delete here is deletable, and clearing the
     * references would orphan every default-store body the organization has.
     * The pass writes nothing for that page and leaves it for the operator.
     *
     * @return bodies expired, and whether the pass stopped on the tick budget
     */
    private suspend fun expireBodies(
        orgId: UUID,
        bodyCutoff: Instant,
        resultCutoff: Instant?,
        tickStart: Instant,
    ): Pair<Long, Boolean> {
        val watermarkKey = bodyWatermarkKey(orgId)
        // No result window means no floor from it: the body pass is then the
        // only thing that ever expires a body, so on its first run it owns all
        // of history.
        val floor = resultCutoff ?: Instant.EPOCH
        val previous = ioTransaction { JobWatermarks.read(watermarkKey) }
        val from = maxOf(previous ?: floor, floor)
        if (!from.isBefore(bodyCutoff)) {
            // Empty window: either the body window is the longer of the two, or
            // this organization was already swept past this cutoff.
            return 0L to false
        }

        var expired = 0L
        var rounds = 0
        // Steps this tick could not delete. They keep their URL, so the next
        // page would select them again unchanged; excluding them is what lets a
        // page of mixed outcomes make progress instead of re-attempting the
        // same failures until the budget is gone.
        val failedStepIds = LinkedHashSet<UUID>()

        while (true) {
            val page = ioTransaction {
                ProbeSteps
                    .join(ProbeResults, JoinType.INNER, ProbeSteps.probeResultId, ProbeResults.id)
                    .select(ProbeSteps.id, ProbeSteps.responseBodyStorageUrl)
                    .where {
                        var predicate = (ProbeResults.organizationId eq orgId) and
                            (ProbeResults.startedAt greaterEq from) and
                            (ProbeResults.startedAt less bodyCutoff) and
                            ProbeSteps.responseBodyStorageUrl.isNotNull() and
                            ProbeSteps.bodyStoreId.isNull()
                        if (failedStepIds.isNotEmpty()) {
                            predicate = predicate and (ProbeSteps.id notInList failedStepIds)
                        }
                        predicate
                    }
                    .limit(batchSize)
                    .map { it[ProbeSteps.id] to it[ProbeSteps.responseBodyStorageUrl]!! }
            }

            if (page.isEmpty()) {
                advanceWatermark(watermarkKey, bodyCutoff, failedStepIds.isEmpty(), orgId)
                return expired to false
            }

            // Object-store round trips happen between transactions, as in the
            // result pass: a hung store must not hold a pooled connection. On
            // the IO dispatcher, because every one of them is a blocking call
            // and this job runs on the shared worker scope.
            val outcome = withContext(Dispatchers.IO) {
                storageClient.deleteAll(page.map { it.second }.distinct())
            }

            if (outcome.refused.isNotEmpty()) {
                // One ERROR for the organization, not one per body: a fence
                // misconfiguration refuses every body it is handed.
                log.error(
                    "Retention: body pass for org {} stopped — the storage client refused {} of {} bodies as " +
                        "outside platform storage (first: {}). The worker's STORAGE_FILESYSTEM_ROOT / " +
                        "STORAGE_S3_BUCKET / STORAGE_S3_PREFIX do not match the storage bodies were written to. " +
                        "Nothing was expired; the references are kept.",
                    orgId, outcome.refused.size, page.size, outcome.refused.entries.first(),
                )
                return expired to false
            }

            val failed = outcome.failed
            failed.forEach { (uri, error) -> log.warn("Failed to expire body at {}: {}", uri, error) }

            if (failed.isNotEmpty()) {
                // A transaction of its own: see PendingBodyDeletion.record.
                ioTransaction { PendingBodyDeletion.record(failed.keys, failed.values.firstOrNull()) }
                page.filter { it.second in failed.keys }.forEach { failedStepIds.add(it.first) }
            }

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
            // failed and is now excluded, but a store that refuses one page will
            // refuse the next. Stop rather than spin the tick budget away — the
            // deletion retry job owns those URIs now, and the next tick tries
            // again from the same watermark.
            if (cleared == 0) return expired to false

            // The exclusion list is the only thing here that grows with the
            // failures; past a few pages of them the store is down, not flaky.
            if (failedStepIds.size > batchSize * MAX_FAILED_PAGES) {
                log.warn(
                    "Retention: body pass for org {} gave up after {} failed deletions this tick",
                    orgId, failedStepIds.size,
                )
                return expired to false
            }

            when (RetentionBatching.verdict(
                rowsInRound = page.size,
                batchSize = batchSize,
                elapsed = Duration.between(tickStart, clock()),
                budget = tickBudget,
            )) {
                RetentionBatching.Verdict.ORG_DRAINED -> {
                    advanceWatermark(watermarkKey, bodyCutoff, failedStepIds.isEmpty(), orgId)
                    return expired to false
                }
                RetentionBatching.Verdict.BUDGET_SPENT -> return expired to true
                RetentionBatching.Verdict.CONTINUE -> Unit
            }
        }
    }

    /** Records how far this org's body pass reached, if it reached the end cleanly. */
    private suspend fun advanceWatermark(key: String, mark: Instant, clean: Boolean, orgId: UUID) {
        if (!clean) {
            log.debug("Retention: org {} body watermark held back by unfinished deletions", orgId)
            return
        }
        ioTransaction { JobWatermarks.write(key, mark) }
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
            // A refusal is not this pass's problem: the rows are going anyway
            // and the body belongs to a store the platform does not own.
            val failed = withContext(Dispatchers.IO) { storageClient.deleteAll(bodyUris) }.failed.toList()
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

    companion object {
        /** Pages of failed deletions one tick tolerates for an org before giving up on it. */
        private const val MAX_FAILED_PAGES = 4

        /**
         * Watermark key for an org's body pass. 54 characters at most, inside
         * `job_watermarks.job_name`'s 64.
         */
        internal fun bodyWatermarkKey(orgId: UUID) = "RetentionJob.body:$orgId"
    }
}
