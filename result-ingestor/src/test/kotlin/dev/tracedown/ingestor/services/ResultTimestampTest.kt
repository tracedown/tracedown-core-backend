package dev.tracedown.ingestor.services

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals

/**
 * Which instant a persisted result is filed under.
 *
 * The defect these cover: persistence stamped `Instant.now()` and used it for
 * the result row, every step row, the status-since marker, the outbox row and
 * the hourly aggregation bucket key. That is ingest time, and it differs from
 * probe time by exactly the depth of the result queue — so under a backlog the
 * results landed in the wrong bucket and the downtime computed from
 * `last_status_since` was inflated by the length of the queue.
 *
 * The scheduler now stamps the run's own start on the envelope before it is
 * queued.
 */
class ResultTimestampTest {

    private fun envelope(json: String): JsonObject = Json.decodeFromString(json)

    @Test
    fun `the envelope's own timestamp is used`() {
        val probeTime = Instant.parse("2026-08-27T09:15:00Z")
        val ingestTime = Instant.parse("2026-08-27T11:42:00Z")
        val e = envelope("""{"startedAt":"$probeTime"}""")
        assertEquals(probeTime, ResultPersistenceService.startedAtOf(e, now = ingestTime))
    }

    @Test
    fun `a backlogged result keeps the hour it was taken in`() {
        // Two and a half hours of queue: filing this at ingest time puts it in
        // the wrong hourly bucket and reports downtime that never happened.
        val probeTime = Instant.parse("2026-08-27T09:59:30Z")
        val ingestTime = Instant.parse("2026-08-27T12:30:00Z")
        val filed = ResultPersistenceService.startedAtOf(envelope("""{"startedAt":"$probeTime"}"""), ingestTime)
        assertEquals(9, filed.atZone(java.time.ZoneOffset.UTC).hour)
    }

    @Test
    fun `redelivery of the same envelope resolves to the same instant`() {
        // Delivery is at-least-once. With Instant-now the two deliveries of one
        // run disagreed about when it happened; with the envelope they cannot.
        val e = envelope("""{"startedAt":"2026-08-27T09:15:00Z"}""")
        val first = ResultPersistenceService.startedAtOf(e, now = Instant.parse("2026-08-27T09:15:02Z"))
        val second = ResultPersistenceService.startedAtOf(e, now = Instant.parse("2026-08-27T09:47:11Z"))
        assertEquals(first, second)
    }

    @Test
    fun `an envelope from before the field falls back to ingest time`() {
        // One queue drain wide, during a rolling upgrade. Refusing such an
        // envelope would drop exactly the results this path exists to keep.
        val ingestTime = Instant.parse("2026-08-27T11:42:00Z")
        assertEquals(ingestTime, ResultPersistenceService.startedAtOf(envelope("""{}"""), ingestTime))
        assertEquals(ingestTime, ResultPersistenceService.startedAtOf(envelope("""{"startedAt":""}"""), ingestTime))
    }

    @Test
    fun `an unparseable timestamp falls back rather than failing the persist`() {
        val ingestTime = Instant.parse("2026-08-27T11:42:00Z")
        val e = envelope("""{"startedAt":"not-a-timestamp"}""")
        assertEquals(ingestTime, ResultPersistenceService.startedAtOf(e, ingestTime))
    }
}
