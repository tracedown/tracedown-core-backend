package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * Per-endpoint rollups of `probe_steps`: one row per service, bucket, endpoint
 * and status code. The companion of [ProbeAggregates], which rolls the same
 * runs up per service and per agent.
 *
 * Every column is a count or a sum, so rows add up and a bucket can be
 * re-derived at any time from the steps it covers.
 */
object ProbeStepAggregates : Table("probe_step_aggregates") {
    val id = javaUUID("id")
    val serviceId = javaUUID("service_id").references(Services.id)
    val bucketStart = timestamp("bucket_start")

    /** "hourly" | "daily". */
    val bucketType = varchar("bucket_type", 8)

    /** `"<METHOD> <template>"` — see `util/EndpointKeys`. */
    val endpointKey = varchar("endpoint_key", 210)

    /** 0 = the call produced no HTTP response (DNS/connect/TLS failure, timeout). */
    val statusCode = short("status_code")

    val callCount = integer("call_count")

    /** Calls that carry phase timings — the denominator for the `sum*Ms` columns. */
    val timedCount = integer("timed_count")

    val sumDnsMs = long("sum_dns_ms")
    val sumConnectMs = long("sum_connect_ms")
    val sumTlsMs = long("sum_tls_ms")
    val sumTtfbMs = long("sum_ttfb_ms")
    val sumTransferMs = long("sum_transfer_ms")
    val sumResponseMs = long("sum_response_ms")

    val sumSizeBytes = long("sum_size_bytes")

    /** Calls that reported a response size — the denominator for [sumSizeBytes]. */
    val sizedCount = integer("sized_count")

    override val primaryKey = PrimaryKey(id)
}
