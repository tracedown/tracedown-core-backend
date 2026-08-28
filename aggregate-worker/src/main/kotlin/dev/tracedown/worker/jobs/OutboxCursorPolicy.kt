package dev.tracedown.worker.jobs

import dev.tracedown.common.models.OutboxCursorState
import java.time.Duration
import java.time.Instant

/**
 * Which consumer cursors may hold the outbox back, and which have stopped
 * counting.
 *
 * The outbox purge deleted only below the slowest registered cursor, with no
 * upper bound on how long "slowest" was allowed to mean "not moving at all".
 * A consumer that degrades correctly — retrying forever against a dependency
 * that is down — keeps its cursor row and stops advancing it, and the outbox,
 * which takes a row per probe result, then grows without limit until Postgres
 * runs out of disk. A consumer that is retired but whose cursor row is left
 * behind does the same thing, permanently and silently.
 *
 * So a cursor earns its veto by advancing. Two conditions must both hold before
 * one is disregarded:
 *
 *  1. It has not advanced for longer than the staleness horizon.
 *  2. It is behind the head of the log.
 *
 * The second is what keeps a healthy consumer safe. A caught-up consumer does
 * not advance either — there is nothing to advance over — so elapsed time alone
 * would condemn the quietest deployment first. A caught-up cursor also pins
 * nothing: it sits at the head, so honouring it costs nothing and the question
 * never arises.
 *
 * Disregarding a cursor is not the same as deleting it. The row stays, so a
 * consumer that comes back resumes from where it was rather than replaying the
 * entire log; it simply may find that the oldest events it had not read are
 * gone. That trade is deliberate: a bounded loss of events older than the
 * outbox retention window is recoverable operational damage, and a full disk is
 * not.
 *
 * The policy holds with any number of consumers, including none — with no
 * cursors there is nothing to disregard and nothing to pin, and the floor is
 * absent exactly as before.
 */
object OutboxCursorPolicy {

    /**
     * How long a cursor may sit behind the head without advancing before it
     * stops being honoured.
     *
     * Comfortably longer than any restart, deploy or transient dependency
     * outage, and comfortably shorter than the outbox retention window — which
     * is what actually decides how much a returning consumer loses. With the
     * default 7-day retention, a consumer down for a day is disregarded but
     * still finds every event of the last week when it comes back; it has to be
     * gone for longer than the retention window before anything it had not read
     * is actually deleted.
     */
    val DEFAULT_STALE_HORIZON: Duration = Duration.ofHours(24)

    /**
     * How far behind the head a still-honoured cursor may fall before it is
     * worth mentioning. Purely a reporting threshold — a lagging cursor is
     * still honoured in full.
     */
    const val LAG_REPORT_THRESHOLD = 10_000L

    /**
     * The purge decision for one pass.
     *
     * @param floor highest `seq` that may be deleted, or null when no honoured
     *   cursor constrains the purge at all
     * @param abandoned consumers whose cursor was disregarded — each an
     *   operational fault someone has to see
     * @param lagging consumers still honoured but meaningfully behind, with
     *   their distance from the head
     */
    data class Decision(
        val floor: Long?,
        val abandoned: List<String>,
        val lagging: List<Lag>,
    )

    /** A consumer's distance from the head of the log. */
    data class Lag(val consumerName: String, val behind: Long)

    /**
     * Decides the purge floor from the registered cursors.
     *
     * @param cursors every registered cursor
     * @param headSeq highest `seq` in the outbox (0 when empty)
     * @param now current time
     * @param horizon staleness horizon; a non-positive value disables the rule
     *   and restores the unconditional floor
     */
    fun decide(
        cursors: List<OutboxCursorState>,
        headSeq: Long,
        now: Instant,
        horizon: Duration = DEFAULT_STALE_HORIZON,
    ): Decision {
        if (cursors.isEmpty()) return Decision(floor = null, abandoned = emptyList(), lagging = emptyList())

        val abandoned = mutableListOf<String>()
        val lagging = mutableListOf<Lag>()
        var floor: Long? = null

        for (cursor in cursors) {
            val behind = (headSeq - cursor.lastId).coerceAtLeast(0)
            if (isAbandoned(cursor, headSeq, now, horizon)) {
                abandoned += cursor.consumerName
                continue
            }
            if (behind >= LAG_REPORT_THRESHOLD) lagging += Lag(cursor.consumerName, behind)
            floor = if (floor == null) cursor.lastId else minOf(floor, cursor.lastId)
        }

        return Decision(floor = floor, abandoned = abandoned.sorted(), lagging = lagging)
    }

    /** Whether one cursor has stopped counting: behind the head and not moving. */
    fun isAbandoned(
        cursor: OutboxCursorState,
        headSeq: Long,
        now: Instant,
        horizon: Duration = DEFAULT_STALE_HORIZON,
    ): Boolean {
        if (horizon.isZero || horizon.isNegative) return false
        if (cursor.lastId >= headSeq) return false
        return cursor.updatedAt.isBefore(now.minus(horizon))
    }
}
