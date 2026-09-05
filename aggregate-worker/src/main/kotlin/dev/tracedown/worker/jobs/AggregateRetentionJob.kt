package dev.tracedown.worker.jobs

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.ProbeAggregates
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.AggregateRetentionJob")

/**
 * Purges hourly aggregate rows from probe_aggregates older than the retention
 * period. Daily rollups are kept — they are cheap and back the long-range
 * charts once the raw results and hourly buckets that fed them have aged out.
 *
 * A retention of `-1` (or `0`) keeps hourly aggregates forever; the job does
 * nothing in that case.
 */
class AggregateRetentionJob(
    private val hourlyRetentionDays: Int,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "AggregateRetentionJob"

    override suspend fun execute() {
        if (hourlyRetentionDays <= 0) {
            log.debug("Hourly aggregate retention disabled (hourlyAggregateRetentionDays={})", hourlyRetentionDays)
            return
        }

        val cutoff = Instant.now().minus(hourlyRetentionDays.toLong(), ChronoUnit.DAYS)

        val deleted = ioTransaction {
            ProbeAggregates.deleteWhere {
                (ProbeAggregates.bucketType eq "hourly") and (ProbeAggregates.bucketStart less cutoff)
            }
        }

        if (deleted > 0) {
            log.info("Aggregate retention: deleted {} hourly aggregate rows (retention={}d)", deleted, hourlyRetentionDays)
        }
    }
}
