package dev.tracedown.metrics.scrape

import dev.tracedown.common.config.ioTransaction
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Raw metric values read from Redis B for a single service.
 */
data class ServiceMetrics(
    val counters: Map<String, String>,
    val state: Map<String, String>,
)

/**
 * Rolled-up aggregate values for a single service, sourced from the latest
 * all-agents rollup row in `probe_aggregates` (`probe_agent_id IS NULL`).
 * `uptimePct` and `errorRate` are fractions in the range 0..1.
 */
data class ServiceAggregate(
    val uptimePct: Double?,
    val errorRate: Double?,
    val p50Ms: Int?,
    val p95Ms: Int?,
    val p99Ms: Int?,
)

/**
 * Reads metric data from Redis B and resets TTL on read.
 */
class MetricsReader(
    private val redisB: RedisCommands<String, String>,
    private val metricsTtlSeconds: Long,
) {

    /**
     * Reads raw counters and state for a service from Redis B.
     * Resets TTL on the keys (keeps data alive while being read).
     *
     * @param serviceId the service to read metrics for
     * @return metrics data, or null if no data exists
     */
    suspend fun read(serviceId: UUID): ServiceMetrics? = withContext(Dispatchers.IO) {
        val counterKey = "metrics:svc:$serviceId:counters"
        val stateKey = "metrics:svc:$serviceId:state"

        val counters = redisB.hgetall(counterKey)
        val state = redisB.hgetall(stateKey)

        if (counters.isEmpty() && state.isEmpty()) return@withContext null

        // Reset TTL on read
        if (counters.isNotEmpty()) redisB.expire(counterKey, metricsTtlSeconds)
        if (state.isNotEmpty()) redisB.expire(stateKey, metricsTtlSeconds)

        ServiceMetrics(counters, state)
    }

    /**
     * Reads the latest all-agents rollup row from `probe_aggregates` for each
     * of the given services. These per-service rollup rows are written by the
     * aggregate-worker with `probe_agent_id IS NULL`; the freshest bucket
     * (highest `bucket_start`) wins per service.
     *
     * @param serviceIds services to read aggregate rollups for
     * @return map of service id to its latest rollup, omitting services with none
     */
    suspend fun readAggregates(serviceIds: List<UUID>): Map<UUID, ServiceAggregate> {
        if (serviceIds.isEmpty()) return emptyMap()
        return ioTransaction {
            val conn = this.connection.connection as java.sql.Connection
            val idArray = conn.createArrayOf("uuid", serviceIds.map { it.toString() }.toTypedArray())
            val out = HashMap<UUID, ServiceAggregate>()
            conn.prepareStatement(LATEST_ROLLUP_SQL).use { stmt ->
                stmt.setArray(1, idArray)
                val rs = stmt.executeQuery()
                while (rs.next()) {
                    val id = UUID.fromString(rs.getString("service_id"))
                    out[id] = ServiceAggregate(
                        uptimePct = rs.getObject("uptime_pct")?.let { (it as Number).toDouble() },
                        errorRate = rs.getObject("error_rate")?.let { (it as Number).toDouble() },
                        p50Ms = rs.getObject("p50_ms")?.let { (it as Number).toInt() },
                        p95Ms = rs.getObject("p95_ms")?.let { (it as Number).toInt() },
                        p99Ms = rs.getObject("p99_ms")?.let { (it as Number).toInt() },
                    )
                }
            }
            out
        }
    }

    private companion object {
        /** Latest all-agents rollup per service (one row per service_id). */
        private val LATEST_ROLLUP_SQL = """
            SELECT DISTINCT ON (service_id)
                   service_id, uptime_pct, error_rate, p50_ms, p95_ms, p99_ms
            FROM probe_aggregates
            WHERE probe_agent_id IS NULL
              AND service_id = ANY(?)
            ORDER BY service_id, bucket_start DESC
        """.trimIndent()
    }
}
