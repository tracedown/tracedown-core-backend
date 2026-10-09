package dev.tracedown.gateway.controllers.runs

import dev.tracedown.common.audit.AuditActor
import dev.tracedown.common.config.PlatformDefaults
import dev.tracedown.common.models.OutboxEmit
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.RunRequests
import dev.tracedown.common.models.RunState
import dev.tracedown.common.runs.RunBounds
import dev.tracedown.common.runs.RunTrigger
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.data.results.RunStatus
import dev.tracedown.gateway.util.NotFoundException
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.select
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update

/**
 * Runs somebody asked for, by the id they were handed for each.
 *
 * The gateway mints the id when the run is asked for and records it here
 * ([record], inside the request's own transaction); the scheduler files the run
 * — its result, or the skipped row saying why it was not made — under the same
 * id in `probe_results`, and the ingestor marks the request settled beside it.
 * [status] reads the two together.
 */
object RunRequestController {

    /**
     * How long a request may go without a result before it reads `expired`.
     * Set at startup from configuration; this is only what a gateway that
     * never calls [init] uses, derived the same way.
     */
    private var expiry: Duration = Duration.ofSeconds(RunBounds.runExpirySeconds(30_000))

    /** The installation's result window, for an organization with none of its own. */
    private var resultRetentionDays: Int = 90

    /** Set once at startup. */
    fun init(expirySeconds: Long, resultRetentionDays: Int) {
        this.expiry = Duration.ofSeconds(expirySeconds)
        this.resultRetentionDays = resultRetentionDays
    }

    /**
     * Records a request for a run of [serviceId] under [runId], asked for by
     * [userId] (through the API key making the call, if one is). Must be
     * called inside the transaction that authorized it.
     *
     * The row is kept as long as the run's result would be: until
     * [requestedAt] plus the organization's result window, or for good while
     * results are.
     */
    internal fun record(runId: UUID, serviceId: UUID, orgId: UUID, userId: UUID, requestedAt: Instant) {
        val days = PlatformDefaults.retentionConfig.resultRetentionDays(orgId) ?: resultRetentionDays
        RunRequests.insert {
            it[id] = runId
            it[RunRequests.serviceId] = serviceId
            it[organizationId] = orgId
            it[requestedBy] = userId
            it[apiKeyId] = AuditActor.currentApiKeyId()
            it[RunRequests.requestedAt] = requestedAt
            it[state] = RunState.PENDING
            // Zero is not a window (the worker reads it as "never"), and neither
            // is a negative one.
            it[purgeAfter] = if (days > 0) requestedAt.plus(days.toLong(), ChronoUnit.DAYS) else null
        }
    }

    /**
     * Settles the request for [runId] as skipped with [reason], when nothing
     * will ever run it — no scheduler heard it ([RunTrigger.SKIP_NOT_DELIVERED]).
     * Only while it is still pending.
     */
    fun settleUndelivered(runId: UUID, reason: String = RunTrigger.SKIP_NOT_DELIVERED) {
        transaction {
            val updated = RunRequests.update({ (RunRequests.id eq runId) and (RunRequests.state eq RunState.PENDING) }) {
                it[state] = RunState.SKIPPED
                it[RunRequests.reason] = reason
            }
            if (updated == 0) return@transaction
            // The settlement, for readers of the event feed, with the state.
            val row = RunRequests.select(RunRequests.serviceId, RunRequests.organizationId)
                .where { RunRequests.id eq runId }
                .single()
            OutboxEmit.emitResourceEvent(
                RunState.SETTLED_EVENT, "run_request", runId,
                buildJsonObject {
                    put("runId", runId.toString())
                    put("serviceId", row[RunRequests.serviceId].toString())
                    put("orgId", row[RunRequests.organizationId].toString())
                    put("state", RunState.SKIPPED)
                    put("reason", reason)
                },
                organizationId = row[RunRequests.organizationId],
            )
        }
    }

    /** The worst of [statuses] — [RunState.worst], the one order the ingestor's settlement uses too. */
    internal fun worstOf(statuses: List<String>): String? = RunState.worst(statuses)

    /** How far before its request a run's results are looked for when the one under its id is missing (clock skew). */
    private const val SIBLING_LOOKBACK_SECONDS = 60L

    /**
     * Where the run [runId] of [serviceId] stands, for [userId]: read access
     * to the service's results is what it takes, and an id that is not a run
     * of that service in [orgId] is 404 — as is one asked for on a service the
     * caller may not see.
     */
    fun status(orgId: UUID, serviceId: UUID, runId: UUID, userId: UUID, now: Instant = Instant.now()): RunStatus = transaction {
        ProbeResultController.requireResultsRead(orgId, serviceId, userId)
        val request = RunRequests.selectAll()
            .where { (RunRequests.id eq runId) and (RunRequests.serviceId eq serviceId) and (RunRequests.organizationId eq orgId) }
            .firstOrNull() ?: throw NotFoundException()

        // The results are read by the request's own id and the instant they
        // share: the first is filed under the id, and a run on several agents
        // at once (`simultaneous`) has siblings stamped with the same start.
        // An ingestor that predates run requests files them without settling
        // the request, so the results, not the stored state, decide.
        val expected = request[RunRequests.expectedResults]?.toInt() ?: 1
        val requestedAt = request[RunRequests.requestedAt]
        val filed = resultRow(orgId, serviceId) { ProbeResults.id eq runId }
        val results = when {
            filed != null && expected <= 1 -> listOf(filed)
            // Siblings carry the run's id, and were started with it: read
            // within a second of its start, on the service's own index.
            filed != null -> {
                val startedAt = filed[ProbeResults.startedAt]
                listOf(filed) + resultRows(orgId, serviceId) {
                    (ProbeResults.startedAt greaterEq startedAt.minusSeconds(1)) and
                        (ProbeResults.startedAt lessEq startedAt.plusSeconds(1)) and
                        (ProbeResults.runId eq runId) and (ProbeResults.id neq runId)
                }
            }
            // The row under the id itself is missing (it could not be
            // ingested): its siblings still say how the run went. Read from
            // the request on, on the same index.
            else -> resultRows(orgId, serviceId) {
                (ProbeResults.startedAt greaterEq requestedAt.minusSeconds(SIBLING_LOOKBACK_SECONDS)) and (ProbeResults.runId eq runId)
            }
        }
        val first = filed ?: results.firstOrNull()

        val stored = request[RunRequests.state]
        val late = Duration.between(requestedAt, now) > expiry
        val statuses = results.map { it[ProbeResults.status] }
        val state = when {
            // Complete, or as complete as it will get within the bound.
            results.isNotEmpty() && (results.size >= expected || late) ->
                if (statuses.all { it == "skipped" }) RunState.SKIPPED else RunState.DONE
            results.isNotEmpty() -> RunState.PENDING
            // Settled with no result: never delivered, or the result has since
            // gone with its retention window.
            stored != RunState.PENDING -> stored
            late -> RunState.EXPIRED
            else -> RunState.PENDING
        }
        val reason = when {
            state != RunState.SKIPPED -> null
            results.isEmpty() -> request[RunRequests.reason]
            else -> first?.get(ProbeResults.rawResult)?.get("reason")?.jsonPrimitive?.contentOrNull
        }
        RunStatus(
            runId = runId.toString(),
            state = state,
            requestedAt = requestedAt.toString(),
            result = first?.let(ProbeResultController::summaryOf),
            reason = reason,
            status = worstOf(statuses),
            results = results.map(ProbeResultController::summaryOf),
        )
    }

    private fun resultRows(orgId: UUID, serviceId: UUID, where: () -> Op<Boolean>) =
        ProbeResults
            .join(ProbeAgents, JoinType.LEFT, ProbeResults.probeAgentId, ProbeAgents.id)
            .select(ProbeResults.columns + ProbeAgents.slug)
            .where { (ProbeResults.serviceId eq serviceId) and (ProbeResults.organizationId eq orgId) and where() }
            .orderBy(ProbeResults.id)
            .toList()

    private fun resultRow(orgId: UUID, serviceId: UUID, where: () -> Op<Boolean>) =
        resultRows(orgId, serviceId, where).firstOrNull()
}
