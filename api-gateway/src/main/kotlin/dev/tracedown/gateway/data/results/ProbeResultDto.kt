package dev.tracedown.gateway.data.results

import io.ktor.openapi.JsonSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProbeResultSummary(
    val id: String,
    @JsonSchema.Enum("success", "failure", "timeout", "skipped", "error")
    val status: String,
    val runDurationMs: Int,
    val totalResponseMs: Int,
    val startedAt: String,
    val agentSlug: String? = null,
    /** What started the run: its schedule, or somebody asking for it. */
    @JsonSchema.Enum("schedule", "manual")
    val trigger: String = "schedule",
)

@Serializable
data class ProbeResultDetail(
    val id: String,
    val serviceId: String,
    @JsonSchema.Enum("success", "failure", "timeout", "skipped", "error")
    val status: String,
    val runDurationMs: Int,
    val startedAt: String,
    val probeAgentId: Long? = null,
    val rawResult: JsonElement,
    val steps: List<ProbeStepSummary>,
    /** The agent that ran it, by slug; null when it ran on none (a skipped run) or the agent is gone. */
    val agentSlug: String? = null,
    /** What started the run: its schedule, or somebody asking for it. */
    @JsonSchema.Enum("schedule", "manual")
    val trigger: String = "schedule",
)

/**
 * A run somebody asked for, by the id they were handed for it.
 *
 * [state] is `pending` until the run is recorded, then `done` — or `skipped`
 * when it was not made, with [reason] saying why — and [result] is the run as
 * the results list shows it, under the same id. A service that runs on
 * several agents at once makes one result per agent: [results] lists them all
 * (the first is [result]), [status] is the worst of them, and the run is
 * `done` once every one is in (or the bound has passed). `expired`: no result
 * within the gateway's bound — the request may have been lost, and it may
 * still settle.
 */
@Serializable
data class RunStatus(
    val runId: String,
    @JsonSchema.Enum("pending", "done", "skipped", "expired")
    val state: String,
    val requestedAt: String,
    val result: ProbeResultSummary? = null,
    @JsonSchema.Description("Why a skipped run was not made (the skipped result's reason); null otherwise.")
    val reason: String? = null,
    @JsonSchema.Enum("success", "failure", "timeout", "skipped", "error")
    @JsonSchema.Description(
        "The worst status among `results` — failure, then timeout, error, skipped (an agent that did not run it), " +
            "success; null while none is in.",
    )
    val status: String? = null,
    @JsonSchema.Description("Every result of the run: one per agent it ran on. Empty while none is in.")
    val results: List<ProbeResultSummary> = emptyList(),
)

@Serializable
data class ProbeStepSummary(
    val id: String,
    val stepNum: Short,
    val requestUrl: String,
    val statusCode: Short?,
    val responseTimeMs: Int?,
    val dnsMs: Int?,
    val connectMs: Int?,
    val tlsMs: Int?,
    val ttfbMs: Int?,
    val transferMs: Int?,
    val responseSizeBytes: Int?,
    val error: String?,
    /** The step's assertions as the agent reported them (the ProbeResult's `calls[].assertions`). */
    val assertionResults: JsonArray?,
    /** The response headers, by name. */
    val headers: JsonObject?,
    val hasBody: Boolean,
    val bodyNotStoredReason: String?,
)

/** Where a moment sits in the history: the page holding results at or before `at`, and the total count. */
@Serializable
data class ResultPageAt(val page: Int, val total: Long)

/**
 * A step's stored response body, as text: what the key-authenticated API
 * answers for a body wherever it is kept. [encoding] is `base64` when [content]
 * is the base64 of the stored bytes — bytes that are not UTF-8 text, or text
 * carrying control characters — and null when [content] is the text itself.
 * [contentType] is always present: the type the store reported when it is one
 * the gateway repeats, null otherwise.
 *
 * Its own class, not the dashboard's body response: that one may carry a
 * storage URL instead of the body, and this shape never does.
 */
@Serializable
data class StepBodyContent(
    val content: String,
    val contentType: String? = null,
    @JsonSchema.Description("`base64` when `content` is the base64 of the stored bytes (not UTF-8 text, or text carrying control characters); null when it is the text itself.")
    val encoding: String? = null,
)
