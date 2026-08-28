package dev.tracedown.ingestor.consumers

/**
 * Which in-flight messages belong to a consumer that is no longer alive.
 *
 * A message being worked on lives in a *processing list* — moved there
 * atomically as it left the main queue, removed from it once the result is
 * recorded. If the consumer dies in between, the message is not lost, but it is
 * not going anywhere either: it sits in a list nobody is reading. This decides
 * when another consumer may take it.
 *
 * The list is **per consumer**, not shared, and that is the whole design. A
 * shared processing list cannot distinguish "being worked on right now" from
 * "abandoned", so any reclaim would race the consumer that is mid-persist. With
 * one list per consumer, liveness is a property of the *consumer*, and a
 * heartbeat key that the consumer refreshes answers it: no heartbeat, no
 * consumer, and its list is free to take.
 *
 * A consumer that restarts takes a **new** id, so its own abandoned list is
 * reclaimable by whoever sweeps first — including itself. Ids are never reused.
 */
object ProcessingListReclaim {

    /** One list per consumer, holding the messages it currently has in hand. */
    const val PROCESSING_PREFIX = "probe_results_processing:"

    /** Presence key a live consumer refreshes; its absence is what declares death. */
    const val HEARTBEAT_PREFIX = "probe_results_consumer:"

    /**
     * How long a heartbeat outlives its last refresh.
     *
     * The gap between this and [HEARTBEAT_REFRESH_SECONDS] is the tolerance for
     * a consumer that is alive but briefly starved — several refreshes must be
     * missed in a row before it is declared dead. Reclaiming a live consumer's
     * message is not a correctness problem (persistence is idempotent), but it
     * is wasted work, so the margin is generous.
     */
    const val HEARTBEAT_TTL_SECONDS = 60L

    /** How often a live consumer refreshes its heartbeat. */
    const val HEARTBEAT_REFRESH_SECONDS = 15L

    fun processingKey(consumerId: String): String = PROCESSING_PREFIX + consumerId

    fun heartbeatKey(consumerId: String): String = HEARTBEAT_PREFIX + consumerId

    /** The consumer a processing list belongs to, or null if the key is not one. */
    fun consumerIdOf(processingKey: String): String? =
        if (processingKey.startsWith(PROCESSING_PREFIX) && processingKey.length > PROCESSING_PREFIX.length) {
            processingKey.removePrefix(PROCESSING_PREFIX)
        } else {
            null
        }

    /**
     * The processing lists whose messages may be moved back onto the main queue.
     *
     * @param processingKeys every processing list currently in Redis
     * @param liveConsumerIds the consumers that still have a heartbeat
     * @param selfConsumerId the sweeping consumer, never reclaimed from itself —
     *   its own in-flight message is in hand, not abandoned, and its heartbeat
     *   could still be missing for a moment at startup
     */
    fun orphaned(
        processingKeys: Collection<String>,
        liveConsumerIds: Set<String>,
        selfConsumerId: String,
    ): List<String> = processingKeys.filter { key ->
        val owner = consumerIdOf(key)
        owner != null && owner != selfConsumerId && owner !in liveConsumerIds
    }
}
