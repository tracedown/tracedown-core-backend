package dev.tracedown.ingestor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.sql.SQLException
import java.util.UUID

/**
 * The identity that makes at-least-once delivery safe.
 *
 * Delivery from the result queue is at-least-once now — a message stays in a
 * processing list until the row is committed, so the same envelope can arrive
 * twice. That is only an improvement if the second arrival cannot become a
 * second row: a duplicated probe result would double-count in every aggregate
 * and would advance the service's consecutive-status counter twice for one run.
 * The publisher mints the id before queueing, so both deliveries resolve to the
 * same primary key.
 */
class ResultIdentityTest {

    private fun envelope(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `the publisher's result id is the row identity`() {
        val id = "6f1a4d2e-0000-4000-8000-00000000abcd"
        assertEquals(
            UUID.fromString(id),
            ResultPersistenceService.resultIdOf(envelope("""{"resultId":"$id"}""")),
        )
    }

    @Test
    fun `two deliveries of one envelope resolve to one id`() {
        val raw = """{"resultId":"6f1a4d2e-0000-4000-8000-00000000abcd","serviceId":"x"}"""
        assertEquals(
            ResultPersistenceService.resultIdOf(envelope(raw)),
            ResultPersistenceService.resultIdOf(envelope(raw)),
        )
    }

    @Test
    fun `an envelope from before the field is still accepted`() {
        // A message queued by an older scheduler and still in flight across a
        // rolling upgrade. It persists as it always did — the one shape that is
        // not redelivery-safe — because refusing it would drop exactly the
        // results this path exists to keep.
        val first = ResultPersistenceService.resultIdOf(envelope("""{"serviceId":"x"}"""))
        val second = ResultPersistenceService.resultIdOf(envelope("""{"serviceId":"x"}"""))
        assertNotEquals(first, second)
    }

    @Test
    fun `a result id that is not a UUID is refused rather than guessed at`() {
        // Poison, classified as such, dead-lettered. Minting a fresh id here
        // would silently defeat the deduplication for that message.
        assertThrows(IllegalArgumentException::class.java) {
            ResultPersistenceService.resultIdOf(envelope("""{"resultId":"not-a-uuid"}"""))
        }
    }

    @Test
    fun `losing the race to insert a result is recognised as a duplicate`() {
        val duplicate = SQLException(
            """ERROR: duplicate key value violates unique constraint "probe_results_pkey"""",
            "23505",
        )
        assertTrue(ResultPersistenceService.isDuplicateResult(duplicate))
        // Exposed wraps every driver error; the message is on the wrapped one.
        assertTrue(ResultPersistenceService.isDuplicateResult(RuntimeException("insert failed", duplicate)))
    }

    @Test
    fun `a different unique violation is not mistaken for a duplicate result`() {
        // Swallowing this one would drop a result while reporting success.
        val other = SQLException(
            """ERROR: duplicate key value violates unique constraint "service_variables_service_id_key_key"""",
            "23505",
        )
        assertFalse(ResultPersistenceService.isDuplicateResult(other))
        assertFalse(ResultPersistenceService.isDuplicateResult(SQLException("connection closed", "08006")))
    }

    @Test
    fun `a self-referencing cause chain terminates`() {
        val looping = object : RuntimeException("loops") {
            override val cause: Throwable? get() = this
        }
        assertFalse(ResultPersistenceService.isDuplicateResult(looping))
    }
}
