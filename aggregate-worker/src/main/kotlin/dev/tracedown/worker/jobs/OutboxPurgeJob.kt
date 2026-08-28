package dev.tracedown.worker.jobs

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.models.OutboxStream
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
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

        val deleted = newSuspendedTransaction(Dispatchers.IO) {
            // Never delete above the slowest honoured cursor; when none exists
            // the floor is absent and this clause is dropped entirely.
            // retentionDays and floor are numeric values under our control —
            // safe to inline.
            val cursorClause = if (floor != null) "AND seq <= $floor" else ""
            val sql = """
                DELETE FROM outbox
                WHERE created_at < now() - make_interval(days => $retentionDays)
                  $cursorClause
                  AND (published = true OR event_type <> 'probe_result.created')
            """.trimIndent()
            val stmt = connection.prepareStatement(sql, false)
            stmt.executeUpdate().toLong()
        }

        if (deleted > 0) {
            log.info("Outbox purge: deleted {} rows (retention={}d, cursorFloor={})", deleted, retentionDays, floor)
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
