package dev.tracedown.worker.jobs

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.OutboxStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.OutboxPurgeJob")

/**
 * Trims the transactional outbox once consumers have processed its rows.
 *
 * Two consumer styles share the table and both must be respected before a row
 * is removed:
 *
 *  - The fast-path consumer flips `published = true` on the rows it handles
 *    (only its own event type). A row it cares about is kept until published.
 *  - Cursor consumers record their offset in `outbox_cursors`. A row is kept
 *    until every *honoured* cursor has advanced past its `seq` — the floor is
 *    the minimum offset across those cursors.
 *
 * A row is deleted only when it is past the retention window AND at or below the
 * cursor floor (when any cursor exists) AND either already published or not of
 * the fast-path event type. When no cursor rows exist the floor is absent and
 * behavior collapses to the published/retention rule.
 *
 * **A cursor holds the log back only while it is moving.** Honouring every
 * registered cursor unconditionally means one consumer that has stopped — down,
 * retrying forever against something unreachable, or retired without its row
 * being removed — pins the outbox indefinitely, and the outbox takes a row per
 * probe result. [OutboxCursorPolicy] decides which cursors still count; the
 * ones that do not are reported, loudly, because a disregarded cursor means
 * events are now being deleted that a consumer never read.
 */
class OutboxPurgeJob(
    private val retentionDays: Int = 7,
    override val intervalSeconds: Long = 3600L,
    private val staleHorizon: Duration = OutboxCursorPolicy.DEFAULT_STALE_HORIZON,
    private val clock: () -> Instant = Instant::now,
) : ScheduledJob {

    override val name = "OutboxPurgeJob"

    private companion object {
        /** Rows deleted per transaction. */
        const val BATCH_SIZE = 5_000
    }

    override suspend fun execute() {
        if (retentionDays <= 0) return

        val cursors = OutboxStream.states()
        val headSeq = if (cursors.isEmpty()) 0L else OutboxStream.headSeq()
        val decision = OutboxCursorPolicy.decide(cursors, headSeq, clock(), staleHorizon)

        for (lag in decision.lagging) {
            log.warn(
                "Outbox consumer '{}' is {} events behind the head — it is still holding the purge floor",
                lag.consumerName, lag.behind,
            )
        }
        if (decision.abandoned.isNotEmpty()) reportAbandoned(decision.abandoned, headSeq)

        val floor = decision.floor

        // In batches, each its own short transaction, so a large backlog never
        // holds one long transaction open — which would hold back vacuum and
        // every reader of the event feed for as long as it ran.
        var deleted = 0L
        val started = System.nanoTime()
        while (true) {
            val round = ioTransaction { deleteBatch(floor, connection.connection as java.sql.Connection) }
            deleted += round
            val verdict = RetentionBatching.verdict(
                round.toInt(), BATCH_SIZE, Duration.ofNanos(System.nanoTime() - started), RetentionBatching.DEFAULT_TICK_BUDGET,
            )
            if (verdict != RetentionBatching.Verdict.CONTINUE) break
        }

        if (deleted > 0) {
            log.info("Outbox purge: deleted {} rows (retention={}d, cursorFloor={})", deleted, retentionDays, floor)
        }
    }

    /**
     * Deletes one batch of at most [BATCH_SIZE] rows past retention, below
     * [floor] when there is one, and moves the retention mark; returns how many.
     */
    private fun deleteBatch(floor: Long?, conn: java.sql.Connection): Long {
        // A row's age is how long ago it was written (inserted_at), not
        // created_at: a probe result's row carries the run's start, so a
        // result recorded late would be "old" the moment it lands. Rows
        // older than the column have only created_at.
        //
        // The same statement moves the retention mark to the last row it
        // deleted in the order the event feed reads (xid, then seq), so a
        // reader can tell that it has been passed: what is deleted here is
        // not a prefix (an unpublished result row outlives newer rows), so
        // the first row left cannot say that. The mark is the furthest row any
        // batch deleted, so it is right whatever order the batches go in.
        val cursorClause = if (floor != null) "AND seq <= $floor" else ""
        val sql = """
            WITH gone AS (
                DELETE FROM outbox
                WHERE seq IN (
                    SELECT seq FROM outbox
                    WHERE COALESCE(inserted_at, created_at::timestamptz) < now() - make_interval(days => $retentionDays)
                      $cursorClause
                      AND (published = true OR event_type <> 'probe_result.created')
                    LIMIT $BATCH_SIZE
                )
                RETURNING COALESCE(xid, 0) AS xid, seq
            ), last AS (
                -- Never past the oldest transaction still open: a reader's
                -- position cannot be there yet, and a row past it (one of
                -- another history, whose xid this database has not reached)
                -- must not drag the mark beyond every reader.
                SELECT
                    CASE WHEN g.xid >= h.x THEN h.x - 1 ELSE g.xid END AS xid,
                    CASE WHEN g.xid >= h.x THEN 9223372036854775807 ELSE g.seq END AS seq
                FROM (SELECT xid, seq FROM gone ORDER BY xid DESC, seq DESC LIMIT 1) g,
                     (SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint AS x) h
            ), mark AS (
                UPDATE outbox_retention r
                SET purged_xid = last.xid, purged_seq = last.seq, updated_at = now()
                FROM last
                WHERE r.id = 1 AND (last.xid, last.seq) > (r.purged_xid, r.purged_seq)
            )
            SELECT COUNT(*) AS deleted FROM gone
        """.trimIndent()
        return conn.prepareStatement(sql).use { stmt ->
            stmt.executeQuery().use { rs -> if (rs.next()) rs.getLong("deleted") else 0L }
        }
    }

    /**
     * Surfaces a consumer that stopped counting.
     *
     * There is no organization to attribute this to — the outbox is shared
     * platform infrastructure — so it is offered to the alert router as an
     * infra alert (`orgId = null`) rather than written to anyone's banners. A
     * host that watches the seam picks it up; a self-hoster, who has no router
     * registered, gets the log line, at `error`, because a purge running past a
     * consumer is data loss that nothing else in the product will report.
     */
    private fun reportAbandoned(abandoned: List<String>, headSeq: Long) {
        log.error(
            "Outbox cursor(s) {} have not advanced in {}h while behind head seq {} — " +
                "no longer holding the purge floor. Events they never read will now age out. " +
                "Restart the consumer, or delete its outbox_cursors row if it is retired.",
            abandoned, staleHorizon.toHours(), headSeq,
        )
        SystemAlertRouting.handled(
            AlertContext(
                alertType = SystemAlertService.OUTBOX_CONSUMER_STALLED,
                subject = abandoned.joinToString(","),
                orgId = null,
                orgScoped = false,
                severity = "error",
                data = buildJsonObject {
                    put("consumers", abandoned.joinToString(","))
                    put("headSeq", headSeq)
                    put("staleHorizonHours", staleHorizon.toHours())
                },
            )
        )
    }
}
