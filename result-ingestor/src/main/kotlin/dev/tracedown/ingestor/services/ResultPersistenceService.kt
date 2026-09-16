package dev.tracedown.ingestor.services

import dev.tracedown.common.alerts.AlertContext
import dev.tracedown.common.alerts.SystemAlertRouting
import dev.tracedown.common.alerts.SystemAlertService
import dev.tracedown.common.logging.LogContext
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStore
import dev.tracedown.common.storage.AgentBodyStore
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreSecretException
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.common.storage.StorageConfinementException
import dev.tracedown.common.storage.StoreEndpointBlockedException
import kotlinx.serialization.json.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
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
     * Whether [agentPath] lies inside the part of [store] the agent [slug] writes
     * to — the confinement check, no I/O.
     */
    private fun inPlaceContains(store: BodyStore, slug: String, agentPath: String): Boolean = try {
        BodyStorageClient(confinement = BodyStoreRegistry.confinementOf(store, slug)).contains(agentPath)
    } catch (e: Exception) {
        log.warn("body store {} cannot confine bodies: {}", store.id, e.message)
        false
    }

    /**
     * A client confined to the agent [slug]'s corner of [store] to import from,
     * or null when the store is unusable.
     */
    private fun importSourceFor(store: BodyStore, slug: String, serviceId: UUID, resultId: UUID): BodyStorageClient? = try {
        BodyStoreRegistry.clientFor(store, slug)
    } catch (e: Exception) {
        BodyStoreService.recordFailure(store.id, failureCodeOf(e))
        log.warn("body store {} is unusable for service {} result {}: {}", store.id, serviceId, resultId, e.message)
        null
    }

    /** A short code for the store health row; the exception's own class when nothing better fits. */
    private fun failureCodeOf(e: Throwable): String = when (e) {
        is BodyStoreSecretException -> "secret_undecryptable"
        is StorageConfinementException -> "root_not_permitted"
        is StoreEndpointBlockedException -> "blocked_endpoint"
        else -> "unreachable"
    }

    /**
     * Stores whose organization did not match a result's, already warned about.
     * The mismatch is a standing misconfiguration, not an event — one line per
     * agent and organization is the whole of what an operator needs.
     */
    private val orgMismatchWarned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * How many bodies one result may import, and how long the whole import may
     * take. Ingestion is a single queue: a result whose store answers slowly must
     * not hold the queue behind it, so past the budget the remaining bodies are
     * recorded as unavailable — the run itself still lands, on time.
     */
    private const val MAX_IMPORTS_PER_RESULT = 16
    private val IMPORT_BUDGET: Duration = Duration.ofSeconds(60)

    /** Where each call's body ended up, decided before anything is written. */
    private class BodyPlacement {
        /** Call index → the URI to persist. */
        val stored = HashMap<Int, String>()

        /** Calls whose body could not be taken into storage at all. */
        val unavailable = HashSet<Int>()

        /** Calls whose body stays in the agent's store. */
        val inPlace = HashSet<Int>()

        /** Calls whose reported location was outside the agent's store. */
        val outside = HashSet<Int>()

        /** Calls dropped because the agent's store belongs to another organization. */
        val orgMismatch = HashSet<Int>()

        /** The in_place store the kept bodies live in. */
        var inPlaceStoreId: UUID? = null

        /** Imported sources to remove, once the result has committed. */
        val importedSources = mutableListOf<Pair<BodyStorageClient, String>>()

        /** Copies made into the default store, to remove if the result does not commit. */
        val copies = mutableListOf<String>()
    }

    /**
     * Decides, before anything is written, where each call's response body ends
     * up. Nothing here touches the database beyond reading the agent's store.
     *
     * Where the agent wrote the body depends on the body store it is assigned
     * (`probe_agents.body_store_id`):
     * - **none** — the default store; the bytes are relocated to a server-derived,
     *   tenant-scoped key, exactly as before body stores existed.
     * - **import** — the agent's own store; the body is copied into the default
     *   store through a client confined to the agent's corner of that store, and
     *   recorded there like any relocated one. The source is removed only after
     *   the result commits.
     * - **in_place** — the body stays where it is. Its location is accepted only
     *   when it lies inside the agent's own sub-prefix of the store, and is then
     *   recorded verbatim with the store's id; anything else is
     *   `outsideAssignedStore`, never trusted.
     *
     * Two rules cut across all three:
     * - a body that already lies **inside the default store** is always relocated
     *   as if no store were assigned. An agent keeps writing where its own
     *   configuration says until it is redeployed, so every move to or from a
     *   store has a window in which that is exactly what happens — and nothing
     *   written in that window should be lost to it;
     * - a store of **another organization** is never used. The agent's assignment
     *   crossed an organization boundary; the body is dropped with
     *   `storeOrgMismatch` rather than filed under the wrong owner.
     */
    private fun placeBodies(
        calls: JsonArray?,
        agentId: Long?,
        organizationId: UUID,
        serviceId: UUID,
        resultId: UUID,
    ): BodyPlacement {
        val placement = BodyPlacement()
        if (calls == null) return placement
        val relocator = bodyRelocator
        val reported = calls.withIndex().mapNotNull { (index, callElement) ->
            val resp = callElement.jsonObject["response"] as? JsonObject ?: return@mapNotNull null
            val agentPath = resp["bodyPath"]?.jsonPrimitive?.contentOrNull
            if (agentPath.isNullOrBlank()) null else index to agentPath
        }
        if (reported.isEmpty()) return placement

        val assigned = if (agentId != null) BodyStoreRegistry.storeOfAgent(agentId) else null
        val store = assigned?.store?.takeIf { it.organizationId == organizationId }
        if (assigned != null && store == null) {
            warnOrgMismatch(assigned, organizationId)
        }
        if (store?.inPlace == true) placement.inPlaceStoreId = store.id

        // Built at most once per result: the source side of an import.
        val importSource by lazy {
            store?.let { importSourceFor(it, assigned.slug, serviceId, resultId) }
        }
        val deadline = Instant.now().plus(IMPORT_BUDGET)
        var imports = 0

        for ((index, agentPath) in reported) {
            val ownDefault = relocator?.ownsLocation(agentPath) == true
            when {
                assigned != null && store == null && !ownDefault -> placement.orgMismatch.add(index)

                store == null || ownDefault -> {
                    val relocated = relocator?.relocate(
                        agentBodyPath = agentPath,
                        organizationId = organizationId,
                        serviceId = serviceId,
                        resultId = resultId,
                        callIndex = index,
                    )
                    if (relocated != null) placement.stored[index] = relocated else placement.unavailable.add(index)
                }

                store.inPlace -> {
                    if (inPlaceContains(store, assigned.slug, agentPath)) {
                        placement.stored[index] = agentPath
                        placement.inPlace.add(index)
                    } else {
                        log.warn(
                            "agent {} reported a body outside its own prefix in store {} for service {} result {}",
                            assigned.slug, store.id, serviceId, resultId,
                        )
                        placement.outside.add(index)
                    }
                }

                imports >= MAX_IMPORTS_PER_RESULT || Instant.now().isAfter(deadline) -> {
                    log.warn(
                        "import budget spent on result {} for service {} — body {} left in store {}",
                        resultId, serviceId, index, store.id,
                    )
                    placement.unavailable.add(index)
                }

                else -> {
                    imports++
                    val source = importSource
                    val imported = if (source == null) null else relocator?.importFrom(
                        source = source,
                        agentBodyPath = agentPath,
                        organizationId = organizationId,
                        serviceId = serviceId,
                        resultId = resultId,
                        callIndex = index,
                    )
                    if (imported != null && source != null) {
                        placement.stored[index] = imported
                        placement.copies.add(imported)
                        placement.importedSources.add(source to agentPath)
                        BodyStoreService.clearFailure(store.id)
                    } else {
                        if (source != null) BodyStoreService.recordFailure(store.id, "unreachable")
                        placement.unavailable.add(index)
                    }
                }
            }
        }
        return placement
    }

    /**
     * The two irreversible halves of an import, in the only order that cannot
     * lose a body: the source goes once the row naming the copy has committed,
     * and the copy goes when it has not. Neither ever throws.
     */
    private fun settleImports(placement: BodyPlacement, committed: Boolean) {
        val relocator = bodyRelocator ?: return
        if (committed) {
            for ((source, uri) in placement.importedSources) relocator.removeImported(source, uri)
        } else {
            for (uri in placement.copies) relocator.dropCopy(uri)
        }
    }

    /** One WARN per agent and organization: a standing misconfiguration, not an event. */
    private fun warnOrgMismatch(assigned: AgentBodyStore, organizationId: UUID) {
        if (!orgMismatchWarned.add("${assigned.slug}:$organizationId")) return
        log.warn(
            "agent {} is assigned body store {}, which belongs to another organization than the results it runs — " +
                "its bodies are not being stored. Assign a store of this organization, or none.",
            assigned.slug, assigned.store.id,
        )
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
        //
        // Where the agent wrote the body depends on the body store it is
        // assigned (probe_agents.body_store_id):
        //   none      — the default store, relocated within it as above;
        //   import    — the agent's store; the body is copied into the default
        //               store (through a client confined to the agent's store)
        //               and recorded there, exactly like a relocated one;
        //   in_place  — the body stays in the agent's store. Its location is
        //               accepted only if it lies inside that store, and is then
        //               recorded verbatim with the store's id; anything outside
        //               it is recorded as `outsideAssignedStore`, never trusted.
        val placement = placeBodies(calls, agentId, organizationId, serviceId, resultId)
        val relocatedBodies = placement.stored
        val bodyRelocationFailed = placement.unavailable
        val inPlaceBodies = placement.inPlace
        val outsideAssignedStore = placement.outside
        val storeOrgMismatch = placement.orgMismatch
        var inPlaceStoreId = placement.inPlaceStoreId
        var committed = false

        // The primary key is the backstop behind the redelivery check above: two
        // consumers handed the same message (a reclaim racing the consumer that
        // was thought dead) both pass the check and one of them loses here. That
        // is the intended outcome, not an error — the row exists either way.
        try {
        transaction {
            // 0. A body kept in a store may only be recorded while that store
            // still exists. Locking the row holds a concurrent delete off until
            // this result commits — the delete then sees the step and is refused
            // with `body_store_in_use`, instead of this insert hitting the
            // foreign key and sending a perfectly good result to the dead-letter
            // list. A store that is already gone simply loses its bodies here.
            inPlaceStoreId?.let { id ->
                val alive = BodyStores.selectAll().where { BodyStores.id eq id }.forUpdate().limit(1).any()
                if (!alive) {
                    log.warn("body store {} was removed while result {} was being ingested", id, resultId)
                    outsideAssignedStore.addAll(inPlaceBodies)
                    inPlaceBodies.forEach { relocatedBodies.remove(it) }
                    inPlaceBodies.clear()
                    inPlaceStoreId = null
                }
            }

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
                        // pre-pass — never the agent-reported path, except for a
                        // body kept in the agent's in_place store (checked to lie
                        // inside it), which is recorded with that store's id.
                        it[responseBodyStorageUrl] = relocatedBodies[index]
                        it[bodyStoreId] = if (index in inPlaceBodies) inPlaceStoreId else null
                        // Present exactly when the body was not captured/stored: `notRequested`
                        // (body saving disabled), `bodyTooLarge`, or `timeout` (no body received)
                        // — spec §9 response.bodyNotCapturedReason. A body that was captured but
                        // could not be taken into server-owned storage is recorded as unavailable.
                        // When the scheduler withheld the bodies itself (unverified
                        // target, §18.4) the executor still says `notRequested`; the
                        // envelope carries the real reason — see BodyNotStoredReason.
                        it[bodyNotStoredReason] = BodyNotStoredReason.resolve(
                            reported = response?.get("bodyNotCapturedReason")?.jsonPrimitive?.contentOrNull,
                            withheld = envelope["bodiesWithheld"]?.jsonPrimitive?.contentOrNull,
                            relocationFailed = index in bodyRelocationFailed,
                            outsideAssignedStore = index in outsideAssignedStore,
                            storeOrgMismatch = index in storeOrgMismatch,
                        )
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
        committed = true
        } catch (e: Exception) {
            if (isDuplicateResult(e)) {
                log.info("result {} for service {} was persisted concurrently — redelivery ignored", resultId, serviceId)
                // The other delivery persisted its own copies; these are ours and
                // nothing names them.
                settleImports(placement, committed = false)
                return PersistOutcome.ALREADY_PERSISTED
            }
            settleImports(placement, committed = false)
            throw e
        }
        settleImports(placement, committed = true)

        log.debug("persisted result {} for service {} status={}", resultId, serviceId, status)

        // Shed probes mean the platform is over dispatch capacity — surface it to
        // the org as a banner (throttled inside the service). This one is org-scoped:
        // a skipped probe is that org's own outcome, so it goes to them even where a
        // host redirects shared-infra alerts. It is offered to the routing seam all
        // the same, so a host could reroute it too if it chose.
        if (status == "skipped") {
            val reason = rawResult["reason"]?.jsonPrimitive?.contentOrNull ?: "unknown"
            // A tick the platform withheld on purpose is that policy working,
            // not a fault: the skipped row says why, and a banner would send the
            // org somewhere there is nothing to fix. SkippedProbeAlert owns
            // which reasons those are, and which alert the rest deserve.
            val alertType = SkippedProbeAlert.alertType(reason) ?: return PersistOutcome.PERSISTED
            val data = buildJsonObject {
                put("reason", reason)
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
