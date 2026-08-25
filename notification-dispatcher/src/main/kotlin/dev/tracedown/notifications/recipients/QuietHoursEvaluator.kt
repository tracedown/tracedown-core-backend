package dev.tracedown.notifications.recipients

import org.dmfs.rfc5545.DateTime
import org.dmfs.rfc5545.recur.RecurrenceRule
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.TimeZone

/**
 * Evaluates whether the current time falls within a user's quiet hours.
 *
 * Quiet hours use the same recurrence format as the service maintenance window
 * (see the scheduler's ServiceWindowEvaluator): `RRULE[/durationMinutes[/timezone]]`.
 * Each occurrence of the rule opens a quiet window of the given length (default
 * 60 minutes). The rule's clock fields (BYHOUR/BYMINUTE) are read in the spec's
 * timezone segment when present, else in [defaultZone]. Timezone names contain
 * slashes, so the spec is split as: rrule / duration / everything-after.
 *
 * Example: quiet 22:00–07:00 daily → `FREQ=DAILY;BYHOUR=22;BYMINUTE=0/540/Europe/Amsterdam`.
 */
object QuietHoursEvaluator {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns true if the current time is within the quiet hours window.
     *
     * @param quietHours `RRULE[/durationMinutes[/timezone]]`, or null/blank for none
     * @param now the current instant to check (defaults to now)
     * @param defaultZone IANA zone used when the spec has no timezone segment
     */
    fun isInQuietHours(
        quietHours: String?,
        now: Instant = Instant.now(),
        defaultZone: String = "UTC",
    ): Boolean {
        if (quietHours.isNullOrBlank()) return false

        val parts = quietHours.trim().split('/')
        val rrulePart = parts[0]
        val durationMinutes = if (parts.size > 1) {
            parts[1].toLongOrNull()?.takeIf { it in 1..1440 } ?: return false
        } else {
            60L
        }
        val zoneName = if (parts.size > 2) parts.drop(2).joinToString("/") else defaultZone
        // Validate before converting: TimeZone.getTimeZone silently answers GMT
        // for an id it does not know, so a typo'd zone would not be rejected —
        // it would quietly move someone's quiet hours to GMT and suppress
        // alerts at the wrong times. ZoneId.of throws instead.
        val zone = try {
            TimeZone.getTimeZone(java.time.ZoneId.of(zoneName))
        } catch (e: java.time.DateTimeException) {
            return false
        }
        val windowMs = durationMinutes * 60_000L

        return try {
            val rule = RecurrenceRule(rrulePart)
            // Any occurrence covering `now` must start within the last window
            // length — iterate from there and stop once past `now`.
            val iterator = rule.iterator(DateTime(zone, now.toEpochMilli() - windowMs))
            var steps = 0
            while (iterator.hasNext() && steps < 1000) {
                val startMs = iterator.nextDateTime().timestamp
                if (startMs > now.toEpochMilli()) return false
                if (now.toEpochMilli() < startMs + windowMs) return true
                steps++
            }
            false
        } catch (e: Exception) {
            log.warn("failed to parse quiet hours '{}': {}", quietHours, e.message)
            false
        }
    }
}
