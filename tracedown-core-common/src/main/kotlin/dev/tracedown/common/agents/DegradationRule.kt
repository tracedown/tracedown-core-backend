package dev.tracedown.common.agents

import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.models.AgentHealthChecks
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * When a passing health challenge counts as *degraded*.
 *
 * The round trip is a cold mTLS handshake plus the agent's callback to the
 * gateway, so its floor is set by geography: an agent on another continent
 * sits near a second every round, an adjacent one near 40 ms. A fixed ceiling
 * therefore leaves the far agent a couple of hundred milliseconds of headroom
 * and flags ordinary jitter, while the near one could triple and never be
 * noticed. So the ceiling is the larger of the fixed floor and twice the
 * agent's own recent median — and, like the down verdict, it takes two
 * consecutive slow rounds: one slow handshake is weather, two is a condition.
 *
 * Shared rather than private to the scheduler because two callers must reach
 * the same verdict about the same agent: the scheduler decides it once, as the
 * round is written, to raise [SystemAlertService.AGENT_DEGRADED]; the gateway
 * recomputes it whenever it serialises an agent's health, so the dashboard
 * shows what the platform actually concluded instead of re-deciding on a fixed
 * ceiling of its own. [verdictForNewRound] is the first path, [verdictFor] the
 * second, and they are the same rule fed the same rounds.
 */
object DegradationRule {

    /** Passing rounds the baseline is taken over. */
    const val BASELINE_ROUNDS = 30

    /** Fewer samples than this and the fixed floor stands alone. */
    const val MIN_BASELINE_SAMPLES = 5

    /** The `agent_health_checks.result` a round that answered correctly carries. */
    const val RESULT_PASS = "pass"

    /**
     * What the rule concluded about an agent's latest passing round.
     *
     * [baselineMs] is null when the agent has too few rounds for a median, in
     * which case [thresholdMs] is the fixed floor alone.
     */
    data class Verdict(val baselineMs: Int?, val thresholdMs: Int, val degraded: Boolean)

    /** The verdict for an agent nothing is known about: the floor, and no conviction. */
    val UNKNOWN = Verdict(baselineMs = null, thresholdMs = SystemAlertService.DEGRADED_RTT_MS, degraded = false)

    /** The round trip above which [baselineMs] makes a round slow. */
    fun thresholdMs(baselineMs: Int?): Int =
        maxOf(SystemAlertService.DEGRADED_RTT_MS, (baselineMs ?: 0) * 2)

    /**
     * Whether this round, at [roundTripMs], following a passing round at
     * [priorRoundTripMs] (null when there was none), is degraded against an
     * agent whose recent passing rounds have median [baselineMs] (null when
     * there are too few to say).
     */
    fun isDegraded(roundTripMs: Int, priorRoundTripMs: Int?, baselineMs: Int?): Boolean {
        val threshold = thresholdMs(baselineMs)
        return roundTripMs > threshold && priorRoundTripMs != null && priorRoundTripMs > threshold
    }

    /** Median of [recentPassMs], or null below [MIN_BASELINE_SAMPLES]. */
    fun baseline(recentPassMs: List<Int>): Int? {
        if (recentPassMs.size < MIN_BASELINE_SAMPLES) return null
        val sorted = recentPassMs.sorted()
        return sorted[sorted.size / 2]
    }

    /**
     * The verdict for a round of [roundTripMs] judged against [history] — the
     * agent's earlier passing rounds, most recent first, the round itself
     * excluded.
     */
    fun verdictForRound(roundTripMs: Int, history: List<Int>): Verdict {
        val baselineMs = baseline(history)
        return Verdict(
            baselineMs = baselineMs,
            thresholdMs = thresholdMs(baselineMs),
            degraded = isDegraded(roundTripMs, history.firstOrNull(), baselineMs),
        )
    }

    /**
     * The verdict read back from [recentPassMs] — the agent's passing rounds,
     * most recent first, the round being judged *included* as the first entry.
     *
     * This is the same call as [verdictForRound] with the list split where the
     * scheduler split it when it wrote the round: the head is the round, the
     * rest is the baseline it was measured against. That is what keeps a
     * verdict computed long afterwards equal to the one the scheduler reached.
     */
    fun verdict(recentPassMs: List<Int>): Verdict {
        val latest = recentPassMs.firstOrNull() ?: return UNKNOWN
        return verdictForRound(latest, recentPassMs.drop(1).take(BASELINE_ROUNDS))
    }

    /**
     * The verdict for a round of [roundTripMs] that is about to be written for
     * [agentId], from the agent's passing rounds so far.
     *
     * Called inside the writing transaction and *before* the insert, so the
     * sample never includes the round being judged.
     */
    fun verdictForNewRound(agentId: Long, roundTripMs: Int): Verdict =
        verdictForRound(roundTripMs, recentPassMs(listOf(agentId), BASELINE_ROUNDS)[agentId].orEmpty())

    /** [verdicts] for a single agent. */
    fun verdictFor(agentId: Long): Verdict = verdicts(listOf(agentId)).getValue(agentId)

    /**
     * The current verdict for each of [agentIds], in one round trip.
     *
     * Reads one round more than the baseline: the head is the agent's latest
     * passing round — the one the scheduler last judged — and the
     * [BASELINE_ROUNDS] behind it are the sample it judged against. An agent
     * with no passing round at all gets [UNKNOWN].
     *
     * An agent whose latest round did *not* pass keeps the verdict of its last
     * passing one, which is also the last verdict the scheduler reached: a
     * failing round is answered by the agent's status, not by this.
     */
    fun verdicts(agentIds: Collection<Long>): Map<Long, Verdict> {
        val ids = agentIds.toSet()
        if (ids.isEmpty()) return emptyMap()
        val rounds = recentPassMs(ids, BASELINE_ROUNDS + 1)
        return ids.associateWith { verdict(rounds[it].orEmpty()) }
    }

    /**
     * The [limit] most recent passing round trips of each agent in [agentIds],
     * most recent first.
     *
     * One statement for the whole fleet — the status feed is polled, and a
     * query per agent would multiply with it. Ordering and filtering match the
     * scheduler's own read exactly (`created_at` descending, passing rounds
     * with a recorded round trip), which is what makes the two paths agree;
     * `idx_agent_health_checks_agent` covers it.
     */
    private fun recentPassMs(agentIds: Collection<Long>, limit: Int): Map<Long, List<Int>> {
        // Agent ids and the limit are numbers of ours, and the result value is
        // a constant — nothing here comes from a request.
        val idList = agentIds.joinToString(",")
        val sql = """
            SELECT probe_agent_id, round_trip_ms FROM (
                SELECT probe_agent_id, round_trip_ms,
                       ROW_NUMBER() OVER (PARTITION BY probe_agent_id ORDER BY created_at DESC) AS rn
                FROM ${AgentHealthChecks.tableName}
                WHERE probe_agent_id IN ($idList)
                  AND result = '$RESULT_PASS'
                  AND round_trip_ms IS NOT NULL
            ) r
            WHERE rn <= $limit
            ORDER BY probe_agent_id, rn
        """.trimIndent()

        val rounds = mutableMapOf<Long, MutableList<Int>>()
        TransactionManager.current().exec(sql) { rs ->
            while (rs.next()) {
                rounds.getOrPut(rs.getLong(1)) { mutableListOf() }.add(rs.getInt(2))
            }
        }
        return rounds
    }
}
