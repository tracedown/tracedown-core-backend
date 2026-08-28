package dev.tracedown.ingestor.consumers

import dev.tracedown.ingestor.consumers.IngestFailurePolicy.Verdict
import io.lettuce.core.RedisCommandTimeoutException
import io.lettuce.core.RedisConnectionException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.sql.SQLException
import java.sql.SQLTransientConnectionException
import java.util.concurrent.TimeoutException

/**
 * The rule that decides whether a finished probe run survives an infrastructure
 * wobble or is thrown away.
 *
 * The defect these cover: the consumer popped a result off the queue — removing
 * it — and only then persisted it. Any failure in that window was logged and
 * discarded, so a thirty-second database failover destroyed every result that
 * arrived during it, including the failure result that should have paged
 * someone. The queue is reliable now, which only helps if retrying is bounded
 * by something: a message that can never succeed must not be retried forever,
 * and a message that will succeed must not be dropped because it is taking a
 * while.
 */
class IngestFailurePolicyTest {

    // ---- the taxonomy ----

    @Test
    fun `a database that went away is transient`() {
        // The failover case this whole path exists for. Postgres SQLState class
        // 08 is a connection exception; HikariCP raises it when the pool cannot
        // hand out a connection either.
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(SQLException("closed", "08006")))
        assertEquals(
            Verdict.TRANSIENT,
            IngestFailurePolicy.classify(SQLTransientConnectionException("pool exhausted", "08003")),
        )
    }

    @Test
    fun `a deadlock or a serialization failure is transient`() {
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(SQLException("deadlock", "40P01")))
    }

    @Test
    fun `resource exhaustion and operator intervention are transient`() {
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(SQLException("out of memory", "53200")))
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(SQLException("shutting down", "57P01")))
    }

    @Test
    fun `a stalled object store is transient`() {
        // Body relocation runs before the transaction and reaches the filesystem
        // or the bucket. Neither being there is a reason to lose the result.
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(IOException("connection reset")))
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(TimeoutException("no answer")))
    }

    @Test
    fun `redis being unreachable is transient`() {
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(RedisConnectionException("refused")))
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(RedisCommandTimeoutException("timed out")))
    }

    @Test
    fun `a payload that is not the expected JSON is poison`() {
        // Retrying it produces the same bytes, forever.
        assertEquals(Verdict.POISON, IngestFailurePolicy.classify(SerializationException("Unexpected JSON token")))
    }

    @Test
    fun `a missing or malformed envelope field is poison`() {
        // `envelope["serviceId"]!!` on an envelope without one, and
        // `UUID.fromString` on something that is not a UUID.
        assertEquals(Verdict.POISON, IngestFailurePolicy.classify(NullPointerException()))
        assertEquals(Verdict.POISON, IngestFailurePolicy.classify(IllegalArgumentException("Invalid UUID string")))
    }

    @Test
    fun `a row the schema refuses is poison`() {
        // 23 integrity constraint violation, 22 data exception. The same row
        // will be refused on every attempt.
        assertEquals(Verdict.POISON, IngestFailurePolicy.classify(SQLException("violates check", "23514")))
        assertEquals(Verdict.POISON, IngestFailurePolicy.classify(SQLException("value too long", "22001")))
    }

    @Test
    fun `a wrapped driver error is classified by what it wraps`() {
        // Exposed wraps every driver error; the SQLState that says which kind of
        // failure this is sits on the wrapped exception, not the wrapper.
        val wrapped = RuntimeException("insert failed", SQLException("terminating connection", "57P01"))
        assertEquals(Verdict.TRANSIENT, IngestFailurePolicy.classify(wrapped))
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        val looping = object : RuntimeException("loops") {
            override val cause: Throwable? get() = this
        }
        assertEquals(Verdict.UNKNOWN, IngestFailurePolicy.classify(looping))
    }

    @Test
    fun `an unrecognised failure is neither trusted nor discarded`() {
        assertEquals(Verdict.UNKNOWN, IngestFailurePolicy.classify(RuntimeException("something new")))
        // Missing table or column: a bug, or a migration still in flight. The
        // bounded-retry path decides without having to guess.
        assertEquals(Verdict.UNKNOWN, IngestFailurePolicy.classify(SQLException("no such column", "42703")))
    }

    // ---- what the verdict does ----

    @Test
    fun `a poison message is never retried in place`() {
        assertFalse(IngestFailurePolicy.mayRetryInProcess(Verdict.POISON, attemptsMade = 1))
    }

    @Test
    fun `a transient failure is retried in place and then handed back`() {
        for (attempt in 1 until IngestFailurePolicy.MAX_IN_PROCESS_ATTEMPTS) {
            assertTrue(
                IngestFailurePolicy.mayRetryInProcess(Verdict.TRANSIENT, attempt),
                "attempt $attempt should still retry in place",
            )
        }
        assertFalse(
            IngestFailurePolicy.mayRetryInProcess(Verdict.TRANSIENT, IngestFailurePolicy.MAX_IN_PROCESS_ATTEMPTS),
        )
    }

    @Test
    fun `in-place retries outlast a database failover`() {
        // The whole point of retrying in place: a failover of half a minute is
        // ridden out without the message ever leaving the consumer.
        val total = (1 until IngestFailurePolicy.MAX_IN_PROCESS_ATTEMPTS)
            .sumOf { IngestFailurePolicy.backoffMs(it) }
        assertTrue(total >= 30_000, "in-place retries only span ${total}ms")
    }

    @Test
    fun `backoff grows and then stops growing`() {
        assertEquals(1_000L, IngestFailurePolicy.backoffMs(1))
        assertEquals(2_000L, IngestFailurePolicy.backoffMs(2))
        assertEquals(4_000L, IngestFailurePolicy.backoffMs(3))
        assertEquals(IngestFailurePolicy.MAX_BACKOFF_MS, IngestFailurePolicy.backoffMs(99))
        assertEquals(IngestFailurePolicy.MAX_REQUEUE_DELAY_MS, IngestFailurePolicy.requeueDelayMs(99))
    }

    @Test
    fun `a poison message is dead-lettered on its first delivery`() {
        // Anything else stalls every result behind one unusable message, which
        // is a worse outage than losing that one.
        assertTrue(IngestFailurePolicy.deadLetter(Verdict.POISON, deliveriesMade = 1))
    }

    @Test
    fun `a transient failure is never dead-lettered, however long it lasts`() {
        // A result is not discarded because the database was down for an hour.
        assertFalse(IngestFailurePolicy.deadLetter(Verdict.TRANSIENT, deliveriesMade = 1))
        assertFalse(IngestFailurePolicy.deadLetter(Verdict.TRANSIENT, deliveriesMade = 1_000))
    }

    @Test
    fun `an unrecognised failure circulates a bounded number of times`() {
        assertFalse(IngestFailurePolicy.deadLetter(Verdict.UNKNOWN, deliveriesMade = 1))
        assertFalse(
            IngestFailurePolicy.deadLetter(Verdict.UNKNOWN, IngestFailurePolicy.MAX_DELIVERIES - 1),
        )
        assertTrue(IngestFailurePolicy.deadLetter(Verdict.UNKNOWN, IngestFailurePolicy.MAX_DELIVERIES))
    }
}
