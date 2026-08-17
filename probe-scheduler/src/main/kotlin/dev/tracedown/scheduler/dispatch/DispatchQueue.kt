package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.domain.DomainPolicy
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import dev.tracedown.scheduler.config.SchedulerConfig
import dev.tracedown.scheduler.results.ResultPublisher
import dev.tracedown.scheduler.results.ResultRedactor
import dev.tracedown.scheduler.scheduling.QuartzManager
import dev.tracedown.scheduler.variables.VariableResolver
import dev.tracedown.scheduler.window.ServiceWindowEvaluator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded dispatch queue that decouples Quartz trigger timing from agent HTTP dispatch.
 *
 * Quartz jobs enqueue service IDs instantly (non-blocking). A fixed pool of dispatcher
 * coroutines drains the queue at the rate agents can handle. This prevents thread pool
 * starvation when many cron triggers fire at the same second.
 *
 * @param capacity maximum number of pending dispatches in the queue
 * @param workers number of concurrent dispatcher coroutines
 */
class DispatchQueue(
    private val capacity: Int,
    private val workers: Int,
    private val quartzManager: QuartzManager,
    private val executionBackend: ProbeExecutionBackend,
    private val queuePolicy: QueuePolicyManager,
    private val resultPublisher: ResultPublisher,
    private val probeConfig: SchedulerConfig.ProbeConfig,
    private val trustedDomainMode: Boolean = true,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val channel = Channel<UUID>(capacity)
    private val droppedCount = AtomicLong(0)

    /**
     * Services currently waiting in the channel. A second tick for a service
     * whose previous tick hasn't dispatched yet means the scheduler is over
     * capacity — deferring it would only grow the backlog and make every
     * probe increasingly late, so it's recorded as skipped instead.
     */
    private val queuedServices: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /** Enqueues a service for dispatch. Returns false if the tick was shed (recorded as skipped). */
    fun enqueue(serviceId: UUID): Boolean {
        if (!queuedServices.add(serviceId)) {
            shed(serviceId, "dispatch_backlog")
            return false
        }
        val result = channel.trySend(serviceId)
        if (result.isFailure) {
            queuedServices.remove(serviceId)
            shed(serviceId, "dispatch_queue_full")
            return false
        }
        return true
    }

    private fun shed(serviceId: UUID, reason: String) {
        val count = droppedCount.incrementAndGet()
        if (count % 100 == 1L) {
            log.warn("Dispatch over capacity ({}), shed service {}. Total sheds: {}", reason, serviceId, count)
        }
        recordSkipped(serviceId, reason)
    }

    /**
     * Records a shed probe as a `skipped` result row so the drop is visible in
     * the service's probe history instead of silently thinning coverage.
     */
    private fun recordSkipped(serviceId: UUID, reason: String) {
        try {
            val ctx = transaction { resolveContext(serviceId) } ?: return
            resultPublisher.publish(
                jobId = UUID.randomUUID(),
                serviceId = serviceId,
                agentId = null,
                projectId = ctx.projectId,
                workspaceId = ctx.workspaceId,
                organizationId = ctx.orgId,
                rawResult = buildJsonObject {
                    put("outcome", "skipped")
                    put("reason", reason)
                    put("elapsedMs", 0)
                },
                agentEgressBytes = 0L, // nothing was dispatched to an agent
            )
        } catch (e: Exception) {
            // Never let bookkeeping break the scheduling path.
            log.debug("failed to record skipped probe for {}: {}", serviceId, e.message)
        }
    }

    /** Starts the dispatcher worker pool. Call once at startup. */
    fun start(scope: CoroutineScope) {
        repeat(workers) { workerId ->
            scope.launch {
                log.debug("Dispatcher worker {} started", workerId)
                for (serviceId in channel) {
                    try {
                        dispatch(serviceId)
                    } catch (e: Exception) {
                        log.error("Dispatcher worker {} failed for service {}: {}", workerId, serviceId, e.message, e)
                    }
                }
            }
        }
        log.info("Dispatch queue started: capacity={}, workers={}", capacity, workers)
    }

    /** Shuts down the queue, cancelling pending items. */
    fun close() {
        channel.close()
    }

    /** Returns the number of dropped dispatches since startup. */
    fun droppedTotal(): Long = droppedCount.get()

    private suspend fun dispatch(serviceId: UUID) {
        // Off the queue — the next tick for this service may enqueue again
        // (concurrent-run protection is the probe_active lock, not this set).
        queuedServices.remove(serviceId)

        // Load service from DB
        val service = transaction {
            Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull()
        }

        if (service == null || service[Services.deleted] || !service[Services.isActive]) {
            log.debug("service {} is inactive/deleted — unscheduling", serviceId)
            quartzManager.unscheduleService(serviceId)
            return
        }

        // Check service window (clock fields evaluate in the spec's own
        // timezone when present, else the org default — only resolved when a
        // window is actually set)
        val windowSpec = service[Services.serviceWindow]
        if (!windowSpec.isNullOrBlank() &&
            ServiceWindowEvaluator.isInWindow(windowSpec, orgDefaultTimezone(service[Services.projectId]))
        ) {
            log.debug("service {} is in service window — skipping", serviceId)
            return
        }

        // Acquire execution lock. TTL is derived from the same timeout the
        // dispatch uses (below), so the lock cannot lapse mid-dispatch. The
        // returned token is required to release only our own lock.
        val lockTimeoutMs = probeConfig.defaultTimeoutMs
        val acquisition = queuePolicy.tryAcquire(serviceId, service[Services.queuePolicy], lockTimeoutMs)
        if (acquisition.result != QueuePolicyManager.AcquireResult.ACQUIRED) {
            log.debug("service {} lock not acquired ({})", serviceId, acquisition.result)
            return
        }
        val lockToken = acquisition.token!!

        try {
            val rawScript = service[Services.script]
            if (rawScript.isBlank()) {
                log.debug("service {} has no script — skipping", serviceId)
                return
            }

            // Resolve scoped variables and rewrite $s.key → $s_key in script
            val (script, variables, secretValues) = VariableResolver.resolve(serviceId, rawScript)

            // Load prev result
            val prev = loadPrev(service[Services.lastRunId])

            // Resolve org context for result publishing (and domain policy)
            val ctx = transaction { resolveContext(serviceId) } ?: return

            // Anti-abuse limits for unverified target domains (spec §18.4):
            // max 3 calls, no body saving, min 5-minute interval.
            var allowBodySave = true
            if (!trustedDomainMode) {
                val varsMap = variables.mapValues { (_, v) -> v.jsonPrimitive.content }
                val policy = transaction { DomainPolicy.evaluate(script, varsMap, ctx.orgId) }
                if (!policy.covered) {
                    if (policy.usesIncludes) {
                        log.warn(
                            "service {} uses includes() against unverified domains — skipping (anti-scraping, §18.4)",
                            serviceId,
                        )
                        return
                    }
                    if (policy.callCount > DomainPolicy.MAX_CALLS) {
                        log.warn(
                            "service {} targets unverified domains with {} calls (max {}) — skipping",
                            serviceId, policy.callCount, DomainPolicy.MAX_CALLS,
                        )
                        return
                    }
                    if (!queuePolicy.allowUnverifiedTick(serviceId, DomainPolicy.MIN_INTERVAL_SECONDS)) {
                        log.debug("service {} throttled (unverified domains, 5m minimum)", serviceId)
                        return
                    }
                    allowBodySave = false
                }
            }

            // Execute — via registered agents by default, or whatever backend
            // the host substituted. A run may fan out to several executions
            // (simultaneous mode); each carries the bytes it sent, so the
            // usage buckets sum them into the run total.
            val jobId = UUID.randomUUID()
            val executions = executionBackend.execute(
                ProbeExecutionBackend.Request(
                    serviceId = serviceId,
                    orgId = ctx.orgId,
                    projectId = ctx.projectId,
                    workspaceId = ctx.workspaceId,
                    probeMode = service[Services.probeMode],
                    script = script,
                    variables = variables,
                    timeoutMs = probeConfig.defaultTimeoutMs,
                    prev = prev,
                    allowBodySave = allowBodySave,
                    // The executor masks these out of saved body bytes before
                    // storage; ResultRedactor (below) masks the same values in
                    // the echoed request that comes back.
                    secretValues = secretValues,
                ),
            )
            if (executions.isEmpty()) {
                log.warn("service {} has no eligible probe executor", serviceId)
                return
            }

            for (execution in executions) {
                // Executor unreachable — logged by the backend, nothing to persist.
                val result = execution.result ?: continue
                // Strip any secret plaintext the executor echoed back (e.g. a
                // secret placed in a request URL/header) before it is persisted.
                val redacted = ResultRedactor.redact(result, secretValues)
                resultPublisher.publish(
                    jobId = jobId,
                    serviceId = serviceId,
                    agentId = execution.agentId,
                    projectId = ctx.projectId,
                    workspaceId = ctx.workspaceId,
                    organizationId = ctx.orgId,
                    rawResult = redacted,
                    agentEgressBytes = execution.egressBytes,
                )
            }

            log.info("dispatched service {} ({} execution(s))", serviceId, executions.size)
        } finally {
            val hasPending = queuePolicy.release(serviceId, lockToken)
            if (hasPending) {
                log.debug("service {} has pending run — re-enqueueing", serviceId)
                enqueue(serviceId)
            }
        }
    }

    private fun loadPrev(lastRunId: UUID?): JsonObject? {
        if (lastRunId == null) return null
        return try {
            transaction {
                val conn = org.jetbrains.exposed.sql.transactions.TransactionManager.current().connection
                val stmt = conn.prepareStatement(
                    "SELECT raw_result FROM probe_results WHERE id = ?::uuid",
                    false
                )
                stmt.fillParameters(listOf(Pair(org.jetbrains.exposed.sql.VarCharColumnType(), lastRunId.toString())))
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val raw = rs.getString(1)
                    if (raw != null) Json.decodeFromString<JsonObject>(raw) else null
                } else null
            }
        } catch (e: Exception) {
            log.debug("failed to load prev result for {}: {}", lastRunId, e.message)
            null
        }
    }

    private data class ServiceContext(val projectId: UUID, val workspaceId: UUID, val orgId: UUID)

    private fun resolveContext(serviceId: UUID): ServiceContext? {
        val service = Services.selectAll().where { Services.id eq serviceId }.firstOrNull() ?: return null
        val project = Projects.selectAll().where { Projects.id eq service[Services.projectId] }.firstOrNull() ?: return null
        val workspace = Workspaces.selectAll().where { Workspaces.id eq project[Projects.workspaceId] }.firstOrNull() ?: return null
        return ServiceContext(
            projectId = service[Services.projectId],
            workspaceId = project[Projects.workspaceId],
            orgId = workspace[Workspaces.organizationId],
        )
    }

    /** The owning org's default timezone (project -> workspace -> org). */
    private fun orgDefaultTimezone(projectId: java.util.UUID): String {
        return transaction {
            (Projects innerJoin Workspaces innerJoin Organizations)
                .select(Organizations.defaultTimezone)
                .where { Projects.id eq projectId }
                .firstOrNull()
                ?.get(Organizations.defaultTimezone)
        } ?: "UTC"
    }

}
