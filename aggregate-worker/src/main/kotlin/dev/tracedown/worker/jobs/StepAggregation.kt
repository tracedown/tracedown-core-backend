package dev.tracedown.worker.jobs

import dev.tracedown.common.util.EndpointKeySql

/**
 * The per-endpoint half of aggregation: rolls `probe_steps` into
 * `probe_step_aggregates` the way [HourlyAggregationJob] and
 * [DailyAggregationJob] roll `probe_results` into `probe_aggregates`.
 *
 * Same window, same transaction and same watermark as the rollup beside it, so
 * a bucket is either built in both tables or in neither, and re-running a
 * window — which both jobs do on every tick, and again across any downtime —
 * recomputes a bucket rather than adding to it.
 *
 * Where a step's endpoint comes from: normally off the row, named by the
 * scheduler from the script it dispatched. Rows that carry no key get one
 * derived from their resolved `request_url` by `EndpointKeySql.fallbackKey`,
 * the SQL twin of `EndpointKeys.fallbackKey`.
 */
object StepAggregation {

    /**
     * The upsert one aggregation run issues, for a bucket granularity of
     * [unit] (`hour` or `day`) recorded as [bucketType].
     *
     * Takes the window bounds as its two parameters, exactly like the rollups
     * beside it, and re-derives a bucket wholesale on conflict — every stored
     * number is a `SUM` or a `COUNT` over the source rows, never an increment
     * of what is already there, which is what makes a re-run a no-op instead
     * of a doubling.
     *
     * The key is computed once, in the inner select, and grouped on by name.
     * Skipped runs are excluded as they are in `probe_aggregates`: a tick that
     * never ran is not a call that was made.
     */
    fun upsertSql(unit: String, bucketType: String): String = """
        INSERT INTO probe_step_aggregates (
            id, service_id, bucket_start, bucket_type, endpoint_key, status_code,
            call_count, timed_count,
            sum_dns_ms, sum_connect_ms, sum_tls_ms, sum_ttfb_ms, sum_transfer_ms, sum_response_ms,
            sum_size_bytes, sized_count
        )
        SELECT
            gen_random_uuid(),
            k.service_id,
            k.bucket_start,
            '$bucketType',
            k.endpoint_key,
            k.status_code,
            count(*),
            -- The denominator for the phase sums. A call with no response time
            -- contributed no timings, so averaging over every call would make a
            -- phase look faster the more often the call failed outright.
            count(*) FILTER (WHERE k.response_time_ms IS NOT NULL),
            coalesce(sum(k.dns_ms), 0),
            coalesce(sum(k.connect_ms), 0),
            coalesce(sum(k.tls_ms), 0),
            coalesce(sum(k.ttfb_ms), 0),
            coalesce(sum(k.transfer_ms), 0),
            coalesce(sum(k.response_time_ms), 0),
            coalesce(sum(k.response_size_bytes), 0),
            count(*) FILTER (WHERE k.response_size_bytes IS NOT NULL)
        FROM (
            SELECT
                r.service_id,
                date_trunc('$unit', r.started_at) AS bucket_start,
                coalesce(s.endpoint_key, ${EndpointKeySql.fallbackKey("s.request_url")}) AS endpoint_key,
                -- A call that never got a response still happened, and the
                -- reason it has no code is the finding. 0 is the code for "no
                -- response"; no real status code is 0, so the two never merge.
                coalesce(s.status_code, 0) AS status_code,
                s.dns_ms, s.connect_ms, s.tls_ms, s.ttfb_ms, s.transfer_ms,
                s.response_time_ms, s.response_size_bytes
            FROM probe_steps s
            JOIN probe_results r ON r.id = s.probe_result_id
            WHERE r.started_at >= ? AND r.started_at < ? AND r.status != 'skipped'
        ) k
        GROUP BY k.service_id, k.bucket_start, k.endpoint_key, k.status_code
        ON CONFLICT (service_id, bucket_start, bucket_type, endpoint_key, status_code)
        DO UPDATE SET
            call_count      = EXCLUDED.call_count,
            timed_count     = EXCLUDED.timed_count,
            sum_dns_ms      = EXCLUDED.sum_dns_ms,
            sum_connect_ms  = EXCLUDED.sum_connect_ms,
            sum_tls_ms      = EXCLUDED.sum_tls_ms,
            sum_ttfb_ms     = EXCLUDED.sum_ttfb_ms,
            sum_transfer_ms = EXCLUDED.sum_transfer_ms,
            sum_response_ms = EXCLUDED.sum_response_ms,
            sum_size_bytes  = EXCLUDED.sum_size_bytes,
            sized_count     = EXCLUDED.sized_count
    """.trimIndent()

    /** The hourly statement, built once. */
    val HOURLY_SQL: String = upsertSql("hour", "hourly")

    /** The daily statement, built once. */
    val DAILY_SQL: String = upsertSql("day", "daily")
}
