package dev.tracedown.gateway.util

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Hour-bucket keys for the Redis B metric hashes (`metrics:svc:<id>:h:<key>`)
 * and the seal that marks one as computed from durable data.
 *
 * Redis B is an ephemeral cache with no persistence. The ingest path increments
 * the bucket of the hour in progress on every result, so a restart mid-hour
 * takes those counters back to zero and the finished hour keeps only what
 * arrived after the restart — and nothing in the hash distinguishes that from a
 * complete hour, or from an hour that genuinely had no runs.
 *
 * A closed hour never changes, so it can be settled once: recompute it from
 * `probe_results`, write it back carrying [SEALED_FIELD], and trust it from then
 * on. A closed hash without the seal is worth nothing and is recomputed.
 *
 * Pure — no clock of its own, no Redis. The caller passes `now`.
 */
object HourBuckets {

    /** Hash field marking a bucket as recomputed from durable data. */
    const val SEALED_FIELD = "sealed"

    /** The value [SEALED_FIELD] carries on a sealed bucket. */
    const val SEALED_VALUE = "1"

    private const val KEY_PATTERN = "yyyyMMddHH"

    private val keyFormatter = DateTimeFormatter.ofPattern(KEY_PATTERN).withZone(ZoneOffset.UTC)
    private val startParser = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

    /** The key of the hour containing [instant]. */
    fun key(instant: Instant): String = keyFormatter.format(instant)

    /** Start of the hour [key] names. Rejects anything that is not a `yyyyMMddHH` key. */
    fun start(key: String): Instant {
        require(key.length == KEY_PATTERN.length && key.all { it.isDigit() }) { "malformed hour key: $key" }
        return try {
            LocalDateTime.parse(key + "0000", startParser).toInstant(ZoneOffset.UTC)
        } catch (e: DateTimeParseException) {
            throw IllegalArgumentException("malformed hour key: $key", e)
        }
    }

    /**
     * The [hours] hour keys ending with the hour containing [now], oldest
     * first. The last entry is always the current (still open) hour.
     */
    fun window(now: Instant, hours: Int): List<String> =
        (hours - 1 downTo 0).map { key(now.minusSeconds(it * 3600L)) }

    /**
     * True when the hour [key] names has already ended at [now] — every hour
     * except the one in progress, which is still being written to.
     */
    fun isClosed(key: String, now: Instant): Boolean = start(key) < now.truncatedTo(ChronoUnit.HOURS)

    /** Splits [keys] into the closed hours and the hour still in progress, in input order. */
    fun splitClosed(keys: List<String>, now: Instant): Pair<List<String>, List<String>> =
        keys.partition { isClosed(it, now) }

    /** True when [hash] carries the seal, i.e. its numbers came from `probe_results`. */
    fun isSealed(hash: Map<String, String>): Boolean = hash[SEALED_FIELD] == SEALED_VALUE

    /**
     * True when the hour [key] names is young enough that a bucket written for
     * it now would outlive [ttlSeconds]. Hours older than the bucket TTL are
     * served from the DB but never written back: the cache would drop them
     * again before the next read, so sealing them is pure churn.
     */
    fun isWithinRetention(key: String, now: Instant, ttlSeconds: Long): Boolean =
        start(key) >= now.minusSeconds(ttlSeconds)
}
