package dev.tracedown.common.runs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * How a run somebody asked for travels from the gateway to the scheduler, and
 * what the result says about where it came from.
 *
 * ## Two channels, because a deploy is not atomic
 *
 * A gateway has always published a run request on [TRIGGER_CHANNEL] as the
 * service id and nothing else, and a scheduler has always read that message
 * with `UUID.fromString` — which refuses anything longer than a UUID. So the
 * run's id cannot ride on that message: a scheduler from before run handles
 * would refuse it, and the run would not happen at all.
 *
 * A run with an id goes out on [RUN_CHANNEL] instead ([encodeRun]). A
 * scheduler that knows run handles subscribes to both; one that does not
 * subscribes only to the first. The gateway publishes on [RUN_CHANNEL] and,
 * when no scheduler received it, publishes the bare id on [TRIGGER_CHANNEL] —
 * so during a rolling deploy:
 *
 *  - a new gateway with an old scheduler: nobody hears the run channel, the
 *    bare id goes out and the old scheduler runs it, as it always did. The run
 *    is not filed under its id, and its handle expires;
 *  - an old gateway with a new scheduler: the bare id arrives on
 *    [TRIGGER_CHANNEL] and runs as a [MANUAL] run with no id;
 *  - both new: the run channel, and the result is filed under the id.
 *
 * Every replica of the scheduler hears every message, so one with an id is
 * run by the replica that claims it first ([claimKey]).
 */
object RunTrigger {

    /** The bare service id. What every gateway has published, and every scheduler reads. */
    const val TRIGGER_CHANNEL = "probe:trigger"

    /** `{"serviceId": …, "runId": …}`: a run filed under the id it was asked under. */
    const val RUN_CHANNEL = "probe:run"

    /** `probe_results.trigger` of a run its cron started. */
    const val SCHEDULE = "schedule"

    /** `probe_results.trigger` of a run somebody asked for. */
    const val MANUAL = "manual"

    /** Every value `probe_results.trigger` takes. */
    val TRIGGERS: Set<String> = linkedSetOf(SCHEDULE, MANUAL)

    /**
     * Skip reasons that only a run somebody asked for is recorded with: the
     * answer to a request the scheduler did not carry out, so that its handle
     * settles instead of waiting out its bound. A scheduled tick in the same
     * position records nothing, as it always has. Decisions, not faults —
     * nothing is raised for them.
     */
    const val SKIP_PREFIX = "run_"
    /** The service is switched off, deleted, or has lost its project or workspace. */
    const val SKIP_SERVICE_INACTIVE = "run_service_inactive"
    /** The service has no script. */
    const val SKIP_SCRIPT_MISSING = "run_script_missing"
    /** The service is inside its maintenance window. */
    const val SKIP_IN_SERVICE_WINDOW = "run_in_service_window"
    /** The host's dispatch gate is holding the service. */
    const val SKIP_HELD = "run_held"
    /** A run of the service is already in progress, and its queue policy runs nothing after it. */
    const val SKIP_ALREADY_RUNNING = "run_already_running"
    /** A dispatch of the service is already waiting in the queue, or already waiting to follow the running one. */
    const val SKIP_ALREADY_QUEUED = "run_already_queued"
    /**
     * No scheduler heard the request — none is running, or Redis is away —
     * so nothing will run it. Settled by the gateway at once rather than left
     * to expire.
     */
    const val SKIP_NOT_DELIVERED = "run_not_delivered"

    /**
     * Envelope fields of a run asked for under an id: the id (`runId`) and how
     * many results the run publishes (`runSize` — more than one in
     * `simultaneous` mode). The first result is filed under the id itself;
     * every one of them carries both, so whichever is ingested last can say
     * the run is complete. An ingestor that predates them ignores them.
     */
    const val ENVELOPE_RUN_ID = "runId"
    const val ENVELOPE_RUN_SIZE = "runSize"

    /** One run request read off either channel. [runId] is null for a bare service id. */
    data class Request(val serviceId: UUID, val runId: UUID?)

    /** The [RUN_CHANNEL] message for [runId] of [serviceId]. */
    fun encodeRun(serviceId: UUID, runId: UUID): String = buildJsonObject {
        put("serviceId", serviceId.toString())
        put("runId", runId.toString())
    }.toString()

    /** Reads a message from [channel]; null when it is not one this knows how to read. */
    fun decode(channel: String, message: String): Request? = when (channel) {
        TRIGGER_CHANNEL -> uuidOrNull(message.trim())?.let { Request(it, null) }
        RUN_CHANNEL -> runCatching {
            val o = Json.parseToJsonElement(message) as JsonObject
            val serviceId = uuidOrNull(o["serviceId"]?.jsonPrimitive?.contentOrNull)
            val runId = uuidOrNull(o["runId"]?.jsonPrimitive?.contentOrNull)
            if (serviceId != null && runId != null) Request(serviceId, runId) else null
        }.getOrNull()
        else -> null
    }

    /** The key a scheduler replica sets (NX) to take [runId] for itself. */
    fun claimKey(runId: UUID) = "run_claim:$runId"

    /** How long a claim is kept: long enough that no replica hears the message after it lapses. */
    const val CLAIM_TTL_SECONDS = 3600L

    private fun uuidOrNull(raw: String?): UUID? = try {
        raw?.let(UUID::fromString)
    } catch (_: IllegalArgumentException) {
        null
    }
}
