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
    val createdAt = timestamp("created_at")

    override val primaryKey = PrimaryKey(id)
}
