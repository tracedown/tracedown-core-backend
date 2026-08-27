package dev.tracedown.ingestor.consumers

import io.lettuce.core.RedisException
import java.io.IOException
import java.sql.SQLException
import java.util.concurrent.TimeoutException
import kotlinx.serialization.SerializationException

/**
 * What to do when a probe result cannot be recorded.
 *
 * Kept as pure functions on purpose: this is the rule that decides whether a
 * finished probe run survives an infrastructure wobble or is thrown away, and
 * it should be readable and testable without a database, a queue or a clock.
 *
 * The distinction everything else hangs off:
 *
 * - **Transient** — the fault is the world's, not the message's. A database
 *   failover, an exhausted connection pool, a stalled object store, a Redis
 *   blip. The same message will persist perfectly once the fault clears, and
 *   every *other* message is failing too, so it is retried without limit. It is
 *   retried in place first (riding out a failover without touching the queue),
 *   then handed back to the queue so the pipeline keeps moving.
 * - **Poison** — the fault is the message's, and no amount of retrying will
 *   change it. A payload that is not JSON, a missing service id, an id that is
 *   not a UUID, a value the schema refuses. Retrying one of these forever
 *   stalls the pipeline behind a single unusable message, which is a worse
 *   outage than setting that message aside. It is dead-lettered on the first
 *   failure.
 * - **Unknown** — an exception this rule cannot name. Treated as transient,
 *   because most infrastructure faults are, but *bounded*: after
 *   [MAX_DELIVERIES] full rounds it is dead-lettered rather than circulating
 *   forever. A mystery that has survived ten deliveries is behaving like a
 *   poison message whatever its type says.
 */
object IngestFailurePolicy {

    enum class Verdict {
        /** The fault is external and will clear; retry without limit. */
        TRANSIENT,

        /** The message itself is unusable; dead-letter it now. */
        POISON,

        /** Unrecognised; retried like a transient failure, but bounded. */
        UNKNOWN,
    }

    /**
     * Attempts made against one delivery before it is handed back to the queue.
     * Six attempts at [backoffMs] spans ~31 seconds, which is chosen to cover a
     * database failover without the message leaving the consumer at all.
     */
    const val MAX_IN_PROCESS_ATTEMPTS = 6

    /**
     * Deliveries an [Verdict.UNKNOWN] failure gets before it is dead-lettered.
     * [Verdict.TRANSIENT] is not bounded by it — a result is not discarded
     * because the database was down for a long time.
     */
    const val MAX_DELIVERIES = 10

    /** Ceiling on both backoffs, so a long outage does not turn into a long sleep. */
    const val MAX_BACKOFF_MS = 16_000L
    const val MAX_REQUEUE_DELAY_MS = 30_000L

    /**
     * Classifies the failure that stopped a result from being recorded.
     *
     * The cause chain is walked, not just the top exception: Exposed wraps
     * every driver error, and the SQLState that says whether this is a dead
     * connection or a rejected value is on the wrapped one.
     */
    fun classify(t: Throwable): Verdict {
        var cause: Throwable? = t
        while (cause != null) {
            when (cause) {
                // A payload that is not JSON, or is not the shape the ingestor
                // parses. It will never become one.
                is SerializationException -> return Verdict.POISON

                // `UUID.fromString` on something that is not a UUID, and the
                // shape errors kotlinx raises for a wrong JSON type.
                is IllegalArgumentException -> return Verdict.POISON

                // The `!!` on a required envelope field that is not there.
                is NullPointerException -> return Verdict.POISON

                is SQLException -> return classifySqlState(cause.sqlState)

                // Redis unreachable, and the timeouts its command layer raises.
                is RedisException -> return Verdict.TRANSIENT

                // Object storage and filesystem stalls during body relocation.
                is IOException -> return Verdict.TRANSIENT

                is TimeoutException -> return Verdict.TRANSIENT
            }
            val next = cause.cause
            cause = if (next === cause) null else next
        }
        return Verdict.UNKNOWN
    }

    /**
     * The verdict for a driver error, from its SQLState class (the first two
     * characters — the standard's own grouping).
     */
    private fun classifySqlState(sqlState: String?): Verdict = when (sqlState?.take(2)) {
        // 08 connection exception, 53 insufficient resources, 57 operator
        // intervention (including a failover shutting the connection),
        // 40 transaction rollback (deadlock, serialization failure).
        "08", "53", "57", "40" -> Verdict.TRANSIENT

        // 23 integrity constraint violation and 22 data exception: the row was
        // refused for what it contains. Sending it again sends the same row.
        "23", "22" -> Verdict.POISON

        // Everything else — notably 42, which is a missing table or column and
        // could be either a genuine bug or a schema migration still in flight.
        // Bounded retry decides between them without having to guess.
        else -> Verdict.UNKNOWN
    }

    /**
     * Whether to try again immediately (after a backoff) rather than hand the
     * message back to the queue.
     *
     * @param attemptsMade attempts already made against this delivery, the
     *   first included
     */
    fun mayRetryInProcess(verdict: Verdict, attemptsMade: Int): Boolean =
        verdict != Verdict.POISON && attemptsMade < MAX_IN_PROCESS_ATTEMPTS

    /** Exponential backoff between in-process attempts, capped. */
    fun backoffMs(attemptsMade: Int): Long {
        val exponent = (attemptsMade - 1).coerceIn(0, 20)
        return (1_000L shl exponent).coerceAtMost(MAX_BACKOFF_MS)
    }

    /**
     * Whether the message should go to the dead-letter queue instead of back on
     * the main one.
     *
     * @param deliveriesMade deliveries of this message so far, this one included
     */
    fun deadLetter(verdict: Verdict, deliveriesMade: Int): Boolean = when (verdict) {
        Verdict.POISON -> true
        Verdict.UNKNOWN -> deliveriesMade >= MAX_DELIVERIES
        // A result is never discarded because infrastructure was down. It goes
        // back on the queue, behind everything else, for as long as it takes.
        Verdict.TRANSIENT -> false
    }

    /**
     * How long to pause after handing a message back to the queue.
     *
     * Without this, a database that is down turns the whole queue into a spin:
     * every message fails, every message is requeued, and the consumer burns a
     * core re-reading the backlog. The pause grows with the number of failed
     * deliveries and is capped so recovery is still prompt.
     */
    fun requeueDelayMs(deliveriesMade: Int): Long {
        val exponent = (deliveriesMade - 1).coerceIn(0, 20)
        return (1_000L shl exponent).coerceAtMost(MAX_REQUEUE_DELAY_MS)
    }
}
