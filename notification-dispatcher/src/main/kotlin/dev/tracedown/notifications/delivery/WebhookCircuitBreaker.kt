package dev.tracedown.notifications.delivery

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-webhook circuit breaker for outbound deliveries.
 *
 * A webhook whose endpoint is simply gone burns its full retry ladder on every
 * event it is bound to — with the default ceiling that is tens of seconds of
 * pure backoff per event, and the endpoint is no more reachable at the end of it
 * than at the start. The breaker converts that into a fast, cheap failure: after
 * [failureThreshold] consecutive failed deliveries the circuit opens for
 * [openMillis], during which deliveries for that webhook are recorded as failed
 * immediately, without an HTTP attempt and without a single backoff sleep.
 *
 * State is per webhook id, not per host: two webhooks pointing at the same host
 * may hold different credentials and fail independently, and a webhook id is the
 * thing an operator can actually see and re-enable.
 *
 * When the window expires the next delivery is let through as a probe. If it
 * succeeds the breaker forgets the webhook entirely; if it fails the circuit
 * re-opens at once, because the failure count is still at or above the
 * threshold.
 *
 * Kept free of coroutines, HTTP and time-of-day so every clause is testable with
 * a hand-cranked clock. The map is concurrent because delivery workers run in
 * parallel; the read-modify-write in [recordFailure] is done under `compute`.
 */
class WebhookCircuitBreaker(
    /** Consecutive failed deliveries that open the circuit. */
    private val failureThreshold: Int = 3,
    /** How long the circuit stays open before a probe delivery is allowed. */
    private val openMillis: Long = 60_000L,
    /** Injectable clock — tests drive it directly. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class State(val failures: Int, val openUntilMs: Long)

    private val states = ConcurrentHashMap<UUID, State>()

    /** True while [webhookId] is being fast-failed and must not be dialled. */
    fun isOpen(webhookId: UUID): Boolean {
        val state = states[webhookId] ?: return false
        return clock() < state.openUntilMs
    }

    /** A delivery succeeded — the endpoint is healthy, drop all accumulated state. */
    fun recordSuccess(webhookId: UUID) {
        states.remove(webhookId)
    }

    /**
     * A delivery failed after exhausting its own retries. Opens (or re-opens)
     * the circuit once the threshold is reached.
     */
    fun recordFailure(webhookId: UUID) {
        states.compute(webhookId) { _, previous ->
            val failures = (previous?.failures ?: 0) + 1
            val openUntil = if (failures >= failureThreshold) clock() + openMillis else 0L
            State(failures, openUntil)
        }
    }

    /** Consecutive failures recorded for [webhookId]; 0 once it has succeeded. */
    fun failureCount(webhookId: UUID): Int = states[webhookId]?.failures ?: 0

    /** Number of webhooks currently carrying state. Bounded by the fleet size. */
    fun trackedCount(): Int = states.size
}
