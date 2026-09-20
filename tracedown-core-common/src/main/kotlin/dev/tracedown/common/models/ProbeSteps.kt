package dev.tracedown.common.models

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp
import org.jetbrains.exposed.v1.json.jsonb
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object ProbeSteps : Table("probe_steps") {
    val id = javaUUID("id")
    val probeResultId = javaUUID("probe_result_id").references(ProbeResults.id)
    val stepNum = short("step_num")
    val requestUrl = varchar("request_url", 256)
    val statusCode = short("status_code").nullable()
    val responseTimeMs = integer("response_time_ms").nullable()
    val dnsMs = integer("dns_ms").nullable()
    val connectMs = integer("connect_ms").nullable()
    val tlsMs = integer("tls_ms").nullable()
    val ttfbMs = integer("ttfb_ms").nullable()
    val transferMs = integer("transfer_ms").nullable()
    val responseSizeBytes = integer("response_size_bytes").nullable()
    val assertionResults = jsonb<JsonElement>("assertion_results", Json.Default).nullable()
    val extractedVariables = jsonb<JsonElement>("extracted_variables", Json.Default).nullable()
    val headers = jsonb<JsonElement>("headers", Json.Default).nullable()
    val cookies = jsonb<JsonElement>("cookies", Json.Default).nullable()
    val error = text("error").nullable()
    val responseBodyStorageUrl = text("response_body_storage_url").nullable()
    val bodyNotStoredReason = varchar("body_not_stored_reason", 64).nullable()

    /**
     * The body store [responseBodyStorageUrl] lives in; null = the default store.
     * Set only for bodies kept in an `in_place` store, which the platform reads
     * through that store and never deletes.
     */
    val bodyStoreId = javaUUID("body_store_id").references(BodyStores.id).nullable()

    /**
     * The endpoint this call belongs to — `"<METHOD> <template>"`, the call as
     * the script writes it, with interpolated variables left as placeholders
     * (`util/EndpointKeys`). Computed by the scheduler from the script it
     * dispatched and carried on the result envelope.
     *
     * Null for every step ingested before the column existed and for any run
     * whose script would not parse; the aggregation derives a key from
     * [requestUrl] for those instead.
     */
    val endpointKey = varchar("endpoint_key", 210).nullable()
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
