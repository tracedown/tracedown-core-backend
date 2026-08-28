package dev.tracedown.ingestor.services

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.logging.LogContext
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.UUID

/**
 * Persists probe results from the Redis queue into the database.
 *
 * In a single transaction: inserts the probe_results row, inserts
 * probe_steps rows, updates the service's status tracking columns,
 * and writes an outbox event for downstream consumers.
 *
 * **Idempotent by identity.** Delivery from the queue is at-least-once (see
 * [dev.tracedown.ingestor.consumers.ProbeResultConsumer]), so the same envelope
 * can arrive twice: once for a persist that was interrupted after the commit,
 * once for a replica reclaiming a dead consumer's in-flight message. The
 * envelope's `resultId` — minted by the publisher before the message was ever
 * queued — is used as the `probe_results` primary key, so a second delivery is
 * recognised and dropped, and the key itself refuses it if two consumers race.
 * Nothing here may become non-idempotent without that guarantee moving with it:
 * the status counters in step 3 in particular would double-count.
 */
object ResultPersistenceService {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What a persist attempt did, so the caller knows whether it is the first. */
    enum class PersistOutcome {
        /** This delivery wrote the row. */
        PERSISTED,

        /** An earlier delivery already wrote it; this one changed nothing. */
        ALREADY_PERSISTED,
    }

    /**
     * The row identity for an envelope.
     *
     * Normally the publisher's `resultId`. A random id is minted only for an
     * envelope that predates the field — i.e. one queued by an older scheduler
     * and still in flight across a rolling upgrade. Such a message is persisted
     * as before and is the one shape that is *not* redelivery-safe; the window
     * is one queue drain long, and the alternative (refusing it) would drop
     * exactly the results this whole path exists to keep.
     */
    fun resultIdOf(envelope: JsonObject): UUID {
        val raw = envelope["resultId"]?.jsonPrimitive?.contentOrNull
        if (raw.isNullOrBlank()) {
            log.warn("envelope carries no resultId — persisting it without redelivery protection")
            return UUID.randomUUID()
        }
        return UUID.fromString(raw)
    }

    /**
     * When the run this envelope describes actually happened.
     *
     * Persistence used to stamp `Instant.now()` and use it for the result row,
     * every step row, the status-since marker, the outbox row and the hourly
     * aggregation bucket key. That is ingest time, not probe time, and the two
     * differ by exactly the depth of the result queue: under any backlog the
     * results landed in the wrong bucket and the downtime computed from
     * `last_status_since` was inflated by however long the queue was.
     *
     * The scheduler stamps `startedAt` on the envelope before it is queued —
     * the instant it handed the script to the executor, or the instant it shed
     * the tick. That is the closest instant to the run that anything in the
     * platform actually knows: the agent does not report its own clock, and
     * trusting one that did would let a skewed agent file results into
     * arbitrary buckets. It is also stable across redelivery, where
     * `Instant.now()` gave the same run a different time per delivery.
     *
     * Falls back to now only for an envelope queued by an older scheduler and
     * still in flight across a rolling upgrade — the same one-drain window as
     * [resultIdOf].
     */
    fun startedAtOf(envelope: JsonObject, now: Instant = Instant.now()): Instant {
        val raw = envelope["startedAt"]?.jsonPrimitive?.contentOrNull
        if (raw.isNullOrBlank()) return now
        return try {
            Instant.parse(raw)
        } catch (e: DateTimeParseException) {
            log.warn("envelope carries an unparseable startedAt '{}' — falling back to ingest time", raw)
            now
        }
    }

    /**
     * Whether a failure is Postgres refusing a second insert of a result row we
     * already hold — the race between two consumers handed the same message.
     *
     * Matched on the primary key by name so that a *different* unique violation
     * (a concurrent variable writeback, say) is not mistaken for a harmless
     * duplicate and quietly swallowed.
     */
    fun isDuplicateResult(t: Throwable): Boolean {
        var cause: Throwable? = t
        while (cause != null) {
            val message = cause.message
            if (message != null &&
                message.contains("probe_results_pkey") &&
                message.contains("duplicate key", ignoreCase = true)
            ) {
                return true
            }
            cause = cause.cause?.takeIf { it !== cause }
        }
        return false
    }

    /**
     * Relocates agent-uploaded bodies to server-derived, tenant-scoped keys.
     * Injected at startup. When null (no storage configured), response bodies are
     * recorded without a storage URL rather than trusting the agent-chosen path.
     */
    @Volatile
    private var bodyRelocator: BodyRelocator? = null

    /** Injects the body relocator. Called once at startup. */
    fun init(relocator: BodyRelocator) {
        this.bodyRelocator = relocator
    }

    /**
     * Agent writeback (`.store()`) may only write METRIC variables — those with
     * secret=false AND encrypted=false. A writeback key that collides with a
     * secret or encrypted ("variable"-type) row must be skipped, never overwritten
     * with attacker-influenced plaintext (which would defeat crypto-shredding and
     * enable variable hijacking).
     */
    fun writebackMayOverwrite(existingSecret: Boolean, existingEncrypted: Boolean): Boolean =
        !existingSecret && !existingEncrypted

    /**
     * Persists a single probe result envelope.
     *
     * @param envelope the JSON envelope as published by ResultPublisher
     * @return whether this delivery wrote the row or found it already written
     */
    fun persist(envelope: JsonObject): PersistOutcome {
        val resultId = resultIdOf(envelope)
        val serviceId = UUID.fromString(envelope["serviceId"]!!.jsonPrimitive.content)
        // Absent for skipped probes — they never reached an agent.
        val agentId = envelope["probeAgentId"]?.jsonPrimitive?.longOrNull
        val projectId = UUID.fromString(envelope["projectId"]!!.jsonPrimitive.content)
        val workspaceId = UUID.fromString(envelope["workspaceId"]!!.jsonPrimitive.content)
        val organizationId = UUID.fromString(envelope["organizationId"]!!.jsonPrimitive.content)
        val rawResult = envelope["rawResult"]!!.jsonObject

        // Attribute every log line from this persistence pass to its org (and
        // the finer ids), so per-org log files capture the ingest trail too.
        return LogContext.scoped(
            org = organizationId,
            workspace = workspaceId,
            project = projectId,
            service = serviceId,
        ) {

        // Redelivery check, before any work: the same envelope reaches here a
        // second time whenever a consumer died between committing and removing
        // the message from its processing list. Re-running the body relocation
        // and the transaction would be wasted at best; the status counters in
        // step 3 would double-count at worst.
        val alreadyPersisted = transaction {
            ProbeResults.selectAll().where { ProbeResults.id eq resultId }.limit(1).any()
        }
        if (alreadyPersisted) {
            log.info("result {} for service {} was already persisted — redelivery ignored", resultId, serviceId)
            return PersistOutcome.ALREADY_PERSISTED
        }

        val outcome = rawResult["outcome"]?.jsonPrimitive?.content ?: "error"
        val status = normalizeStatus(outcome)

        // `error` covers everything that is not a ProbeResult the executor
        // could produce: a script that failed to run, an executor that raised,
        // an agent answering with a diagnostic instead of a result. Spec §9
        // knows only success/failure/timeout as run outcomes, so anything else
        // lands here by construction.
        //
        // These used to be dropped with a warning: a broken script produced no
        // history row, no status change and nothing the person who wrote it
        // could see. They are persisted now — the whole payload goes into
        // raw_result, and errorDetail() pulls out the message worth reading.
        if (status == "error") {
            log.warn("persisting errored run for service {}: {}", serviceId, errorDetail(rawResult))
        }

        // Extract timing from rawResult (Lace ProbeResult uses "elapsedMs")
        val elapsedMs = rawResult["elapsedMs"]?.jsonPrimitive?.intOrNull ?: 0
        val calls = rawResult["calls"]?.jsonArray
        val totalResponseMs = calls
            ?.sumOf { call ->
                val resp = call.jsonObject["response"]
                if (resp is JsonObject) resp["responseTimeMs"]?.jsonPrimitive?.intOrNull ?: 0 else 0
            } ?: 0
        // Probe time, carried on the envelope — not the time this consumer got
        // around to reading it. See startedAtOf.
        val startedAt = startedAtOf(envelope)

        // Take ownership of every stored body BEFORE persisting: relocate the
        // agent-uploaded bytes to a server-derived, tenant-scoped key and record
        // only that URI. The agent's own path is never persisted (it would collide
        // across tenants and could point at arbitrary files). Indices that had a
        // body but could not be relocated are remembered so the step is recorded
        // as body-unavailable instead of silently pointing nowhere.
        val relocatedBodies = HashMap<Int, String>()
        val bodyRelocationFailed = HashSet<Int>()
        if (calls != null) {
            val relocator = bodyRelocator
            for ((index, callElement) in calls.withIndex()) {
                val resp = callElement.jsonObject["response"] as? JsonObject ?: continue
                val agentPath = resp["bodyPath"]?.jsonPrimitive?.contentOrNull ?: continue
                if (agentPath.isBlank()) continue
                val relocated = relocator?.relocate(
                    agentBodyPath = agentPath,
                    organizationId = organizationId,
                    serviceId = serviceId,
                    resultId = resultId,
                    callIndex = index,
                )
                if (relocated != null) relocatedBodies[index] = relocated else bodyRelocationFailed.add(index)
            }
        }

        // The primary key is the backstop behind the redelivery check above: two
        // consumers handed the same message (a reclaim racing the consumer that
        // was thought dead) both pass the check and one of them loses here. That
        // is the intended outcome, not an error — the row exists either way.
        try {
        transaction {
            // 1. Insert probe_results
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = serviceId
                it[probeAgentId] = agentId
                it[ProbeResults.startedAt] = startedAt
                it[ProbeResults.status] = status
                it[runDurationMs] = elapsedMs
                it[ProbeResults.totalResponseMs] = totalResponseMs
                it[ProbeResults.ingressBytes] = rawResult["ingressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                it[ProbeResults.egressBytes] = rawResult["egressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                // Scheduler-measured dispatch bytes, carried on the envelope.
                it[ProbeResults.agentEgressBytes] = envelope["agentEgressBytes"]?.jsonPrimitive?.longOrNull ?: 0
                it[ProbeResults.requestCount] = calls?.size ?: 0
                it[ProbeResults.rawResult] = rawResult
                it[ProbeResults.projectId] = projectId
                it[ProbeResults.workspaceId] = workspaceId
                it[ProbeResults.organizationId] = organizationId
            }

            // 2. Insert probe_steps from rawResult.calls[]
            if (calls != null) {
                for ((index, callElement) in calls.withIndex()) {
                    val call = callElement.jsonObject
                    val request = call["request"].let { if (it is JsonObject) it else null }
                    val response = call["response"].let { if (it is JsonObject) it else null }
                    val responseHeaders = response?.get("headers") as? JsonObject

                    ProbeSteps.insert {
                        it[id] = UUID.randomUUID()
                        it[probeResultId] = resultId
                        it[stepNum] = (index + 1).toShort()
                        it[requestUrl] = request?.get("url")?.jsonPrimitive?.content ?: ""
                        it[statusCode] = response?.get("status")?.jsonPrimitive?.intOrNull?.toShort()
                        it[responseTimeMs] = response?.get("responseTimeMs")?.jsonPrimitive?.intOrNull
                        it[dnsMs] = response?.get("dnsMs")?.jsonPrimitive?.intOrNull
                        it[connectMs] = response?.get("connectMs")?.jsonPrimitive?.intOrNull
                        it[tlsMs] = response?.get("tlsMs")?.jsonPrimitive?.intOrNull
                        it[ttfbMs] = response?.get("ttfbMs")?.jsonPrimitive?.intOrNull
                        it[transferMs] = response?.get("transferMs")?.jsonPrimitive?.intOrNull
                        it[responseSizeBytes] = response?.get("sizeBytes")?.jsonPrimitive?.intOrNull
                        it[assertionResults] = call["assertions"]
                        // `.store()` writeback is a whole-run flat map (rawResult.actions.variables,
                        // persisted to service_variables in step 4). The ProbeResult wire format
                        // (spec §9) does not attribute stored variables to individual calls, so there
                        // is no per-step value to record — leave null rather than fabricate one.
                        it[extractedVariables] = null
                        it[headers] = responseHeaders
                        // The response Set-Cookie header is the only per-call cookie data the
                        // ProbeResult exposes (the executor's cookie jar itself is not emitted).
                        // Header names are lower-cased per spec §9. Null when the call set no cookies.
                        it[cookies] = responseHeaders?.get("set-cookie")
                        // Server-derived, tenant-scoped URI from the relocation
                        // pre-pass — never the agent-reported path.
                        it[responseBodyStorageUrl] = relocatedBodies[index]
                        // Present exactly when the body was not captured/stored: `notRequested`
                        // (body saving disabled), `bodyTooLarge`, or `timeout` (no body received)
                        // — spec §9 response.bodyNotCapturedReason. A body that was captured but
                        // could not be taken into server-owned storage is recorded as unavailable.
                        it[bodyNotStoredReason] = response?.get("bodyNotCapturedReason")?.jsonPrimitive?.contentOrNull
                            ?: if (index in bodyRelocationFailed) "storageUnavailable" else null
                        it[error] = call["error"]?.jsonPrimitive?.contentOrNull
                        it[createdAt] = startedAt
                    }
                }
            }

            // 3. Update service status tracking. Skipped probes don't touch
            // it: last_status stays the last real outcome, and last_run_id
            // must keep pointing at a real result (it feeds `prev` writeback).
            val service = if (status == "skipped") null else Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()

            // Captured BEFORE the status update below overwrites it. On a
            // recovery this is when the outage began — used just below to compute
            // downtime, since the row's value is gone once we update it.
            val previousStatusSince = service?.get(Services.lastStatusSince)

            if (service != null) {
                val previousStatus = service[Services.lastStatus]
                val statusChanged = previousStatus != status

                Services.update({ Services.id eq serviceId }) {
                    // An errored run is not a ProbeResult (spec §9 has no such
                    // outcome), so it must never become `prev` for the next
                    // run — a script reading prev.calls[0] would be handed a
                    // diagnostic envelope. last_status still moves: leaving it
                    // green for a check that did not evaluate is the same
                    // silence this whole path exists to remove.
                    if (status != "error") it[lastRunId] = resultId
                    it[lastStatus] = status
                    if (statusChanged) {
                        it[lastStatusSince] = startedAt
                        it[lastStatusConsecutive] = 1
                    } else {
                        it[lastStatusConsecutive] = service[Services.lastStatusConsecutive] + 1
                    }
                }
            }

            // 4. Write back actions.variables to service_variables
            val actions = rawResult["actions"]?.jsonObject
            val writebackVars = actions?.get("variables")?.jsonObject
            if (writebackVars != null && writebackVars.isNotEmpty()) {
                for ((varKey, varValue) in writebackVars) {
                    val valueStr = if (varValue is JsonPrimitive) varValue.content else varValue.toString()

                    val existing = ServiceVariables.selectAll()
                        .where {
                            (ServiceVariables.serviceId eq serviceId) and
                            (ServiceVariables.key eq varKey) and
                            (ServiceVariables.deleted eq false)
                        }
                        .firstOrNull()

                    if (existing != null) {
                        // Agent writeback (`.store()`) may only touch METRIC variables
                        // (secret=false AND encrypted=false). A writeback key that
                        // collides with a secret or encrypted ("variable"-type) row is
                        // skipped — never overwritten with attacker-influenced plaintext
                        // (which would defeat crypto-shredding and enable variable
                        // hijacking), never decrypted, never bricked.
                        val isMetric = writebackMayOverwrite(
                            existing[ServiceVariables.secret],
                            existing[ServiceVariables.encrypted],
                        )
                        if (isMetric) {
                            ServiceVariables.update({
                                ServiceVariables.id eq existing[ServiceVariables.id]
                            }) {
                                it[value] = valueStr
                                it[updatedAt] = startedAt
                            }
                        } else {
                            log.warn(
                                "writeback for service {} key '{}' skipped: target is a secret/encrypted variable, not a metric",
                                serviceId, varKey,
                            )
                        }
                    } else {
                        ServiceVariables.insert {
                            it[id] = UUID.randomUUID()
                            it[ServiceVariables.serviceId] = serviceId
                            it[key] = varKey
                            it[value] = valueStr
                            it[secret] = false
                            it[encrypted] = false
                            it[deleted] = false
                            it[createdAt] = startedAt
                            it[updatedAt] = startedAt
                        }
                    }
                }
            }

            // Downtime for a recovery notification — computed here, the one place
            // that still has both the pre-update last_status_since (outage start)
            // and this run's time (recovery), and only when a recovery actually
            // fired (laceEmitRecovery emits the "recovered" trigger only if
            // notifyRecovery is on and the service came back up). We carry the
            // seconds, not the raw timestamp, and nothing at all otherwise.
            val recoveryFired = actions?.get("notifications")?.jsonArray
                ?.any { it.jsonObject["trigger"]?.jsonPrimitive?.contentOrNull == "recovered" } == true
            val downtimeSeconds = if (recoveryFired && previousStatusSince != null) {
                Duration.between(previousStatusSince, startedAt).seconds.coerceAtLeast(0)
            } else {
                null
            }

            // 5. Write outbox event for downstream consumers (notification-
            // dispatcher, etc.). Skipped probes are history-only — no events.
            if (status != "skipped") Outbox.insert {
                it[id] = UUID.randomUUID()
                it[aggregateType] = "probe_result"
                it[aggregateId] = resultId
                it[eventType] = "probe_result.created"
                it[payload] = buildJsonObject {
                    put("resultId", resultId.toString())
                    put("serviceId", serviceId.toString())
                    put("projectId", projectId.toString())
                    put("workspaceId", workspaceId.toString())
                    put("organizationId", organizationId.toString())
                    put("status", status)
                    put("runDurationMs", elapsedMs)
                    // Present only on a recovery — the dispatcher formats it into
                    // the recovery message. Absent for every other result.
                    downtimeSeconds?.let { put("downtimeSeconds", it) }
                }
                it[published] = false
                it[createdAt] = startedAt
            }
        }
        } catch (e: Exception) {
            if (isDuplicateResult(e)) {
                log.info("result {} for service {} was persisted concurrently — redelivery ignored", resultId, serviceId)
                return PersistOutcome.ALREADY_PERSISTED
            }
            throw e
        }

        log.debug("persisted result {} for service {} status={}", resultId, serviceId, status)

        // Shed probes mean the platform is over dispatch capacity — surface it to
        // the org as a banner (throttled inside the service). This one is org-scoped:
        // a skipped probe is that org's own outcome, so it goes to them even where a
        // host redirects shared-infra alerts. It is offered to the routing seam all
        // the same, so a host could reroute it too if it chose.
        if (status == "skipped") {
            val reason = rawResult["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            val data = buildJsonObject {
                put("reason", reason)
            }
            // Not every skip is a capacity problem. A tick that found no
            // executor to run on is a fleet-health problem, and telling the org
            // to "reduce probe frequency" would send them the wrong way.
            val alertType = when (reason) {
                "no_eligible_agent" -> SystemAlertService.NO_ELIGIBLE_AGENT
                // Agents were there and none of them took the run. Neither a
                // capacity problem nor an empty fleet — telling the org to
                // reduce probe frequency or check allowlists would send them
                // past the actual fault.
                "agent_unreachable", "agent_rejected" -> SystemAlertService.AGENT_DISPATCH_FAILED
                // The scheduler itself faulted (its database was unreachable,
                // its trigger was dropped). Nothing about the fleet or the
                // org's own settings would explain it.
                "dispatch_error", "trigger_misfired" -> SystemAlertService.SCHEDULER_ERROR
                else -> SystemAlertService.DISPATCH_CAPACITY
            }
            val handled = SystemAlertRouting.handled(
                AlertContext(
                    alertType = alertType,
                    subject = "",
                    orgId = organizationId,
                    orgScoped = true,
                    severity = "warning",
                    data = data,
                )
            )
            if (!handled) {
                SystemAlertService.raise(
                    orgId = organizationId,
                    alertType = alertType,
                    severity = "warning",
                    data = data,
                )
            }
        }

        PersistOutcome.PERSISTED
        } // LogContext.scoped
    }

    /**
     * The most useful line of diagnostic an errored run carries.
     *
     * A ProbeResult (spec §9) puts non-assertion failure detail on the call
     * record's `error`; a run that never got as far as a call carries it at the
     * top level instead. Both are checked, top level first, so an agent- or
     * executor-level message wins over a per-call one.
     */
    fun errorDetail(rawResult: JsonObject): String {
        (rawResult["error"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        val callError = (rawResult["calls"] as? JsonArray)
            ?.firstNotNullOfOrNull { call ->
                ((call as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        return callError ?: "no detail reported"
    }

    /** Maps ProbeResult outcome to DB status enum. */
    private fun normalizeStatus(outcome: String): String = when (outcome) {
        "success" -> "success"
        "failure" -> "failure"
        "timeout" -> "timeout"
        "skipped" -> "skipped"
        else -> "error"
    }
}
