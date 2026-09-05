package dev.tracedown.worker.jobs

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.AgentHealthChecks
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Trims agent health-check history to the configured window (default 90
 * days; -1 keeps forever). The `probe_agents` snapshot columns are
 * untouched — only the history rows age out.
 */
class AgentHealthCleanupJob(
    private val retentionDays: Int,
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "AgentHealthCleanupJob"

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute() {
        if (retentionDays <= 0) {
            log.debug("Agent health retention disabled (agentHealthRetentionDays={})", retentionDays)
            return
        }
        val cutoff = Instant.now().minus(retentionDays.toLong(), ChronoUnit.DAYS)
        val deleted = ioTransaction {
            AgentHealthChecks.deleteWhere { createdAt less cutoff }
        }
        if (deleted > 0) {
            log.info("Agent health cleanup: deleted {} checks older than {}d", deleted, retentionDays)
        }
    }
}
