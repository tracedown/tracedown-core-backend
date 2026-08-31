package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.domain.DomainPolicy
import dev.tracedown.common.net.ProbeTargetPolicy
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
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
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded dispatch queue that decouples Quartz trigger timing from agent HTTP dispatch.
 *
 * Quartz jobs enqueue service IDs instantly (non-blocking). A fixed pool of dispatcher
 * coroutines drains the queue at the rate agents can handle. This prevents thread pool
 * starvation when many cron triggers fire at the same second.
 *
 * **Nothing a Quartz thread calls here may block.** There are only ten of them,
 * and they are the clock: a thread of theirs spent inside a transaction is a
 * trigger that fires late or, past the pool, misfires and is dropped. Both
 * [enqueue] and [recordMisfire] therefore hand off to [shedChannel] and return;
 * the database and Redis work happens on this queue's own threads.
 *
 * **The workers get their own threads, sized to the connection pool.** They ran
 * on [kotlinx.coroutines.Dispatchers.Default], whose parallelism is the CPU
 * count — so `workers = 50` bought perhaps eight concurrent dispatches on a
 * small host, each one holding a CPU thread through a blocking transaction, and
 * the consistency sweep sharing what was left. A dedicated pool of exactly
 * [workers] threads makes the configured number the real number, and the
 * scheduler sizes its Hikari pool from the same figure so a worker that reaches
 * for a connection finds one instead of timing out after 30 seconds.
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
    /**
     * Which addresses a probe may target. Defaults to the permissive mode so a
     * host that constructs this queue without an opinion keeps the behaviour a
     * self-hosted install has always had; Application resolves the real one
     * from configuration.
     */
    private val targetPolicy: ProbeTargetPolicy.Mode = ProbeTargetPolicy.Mode.ALLOW_PRIVATE,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val channel = Channel<UUID>(capacity)
    private val droppedCount = AtomicLong(0)
    private val unrecordedSheds = AtomicLong(0)

    /**
     * A tick that will not run, waiting to be written down.
     *
     * [at] is captured where the decision was made, not where the row is
     * written, so a shed recorded a second later is still filed at the minute it
     * belonged to.
     */
    private data class ShedRecord(val serviceId: UUID, val reason: String, val at: Instant)

    /**
     * Sheds waiting to be recorded, drained off the Quartz threads.
     *
     * Buffered and non-suspending: an offer that does not fit is refused rather
     * than made to wait, because the caller may be a Quartz thread and blocking
     * the clock to write bookkeeping is what this whole change exists to stop.
     * A refusal is counted and logged — an invisible loss here would be the same
     * failure the skipped row exists to make visible.
     */
    private val shedChannel = Channel<ShedRecord>(SHED_BUFFER)

    /**
     * Threads for the dispatch workers and the shed recorders.
     *
     * Daemon threads, and deliberately never shut down: [close] closes the
     * channels, which ends the loops, and a dispatch already in flight keeps its
     * thread until it finishes rather than having its continuation rejected by a
     * pool shutting down underneath it. The JVM is exiting either way.
     */
    private val dispatchThreads: CoroutineDispatcher =
        Executors.newFixedThreadPool(workers + SHED_RECORDERS, DispatchThreadFactory())
            .asCoroutineDispatcher()

    /**
     * Services currently waiting in the channel. A second tick for a service
     * whose previous tick hasn't dispatched yet means the scheduler is over
     * capacity — deferring it would only grow the backlog and make every
     * probe increasingly late, so it's recorded as skipped instead.
     */
    private val queuedServices: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    /**
     * Enqueues a service for dispatch. Returns false if the tick was shed
     * (recorded as skipped).
     *
     * Called on a Quartz thread. Does no I/O — a shed is queued for recording,
     * not recorded here. The path that exists to make overload visible used to
     * open a transaction (three queries) plus a Redis push on the way past, so
     * the exact overload it handles saturated the ten-thread Quartz pool and the
     * next triggers misfired: dropped with no row and no log, which is the
     * opposite of visible.
     */
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

    /**
     * Records a trigger Quartz dropped without firing.
     *
     * The probe triggers use `withMisfireHandlingInstructionDoNothing`, which is
     * the right policy — a cron probe that is late is not worth running twice —
     * but on its own it is silent, and silence on a monitoring dashboard reads
     * as "all fine". This makes the dropped tick a `skipped` row like any other
     * shed. Also called on a Quartz thread; also does no I/O.
     */
    fun recordMisfire(serviceId: UUID) {
        shed(serviceId, "trigger_misfired")
    }

    private fun shed(serviceId: UUID, reason: String) {
        val count = droppedCount.incrementAndGet()
        if (count % 100 == 1L) {
            log.warn("Dispatch over capacity ({}), shed service {}. Total sheds: {}", reason, serviceId, count)
        }
        recordSkipped(serviceId, reason, Instant.now())
    }

    /**
     * Queues a shed probe to be written down as a `skipped` result row, so the
     * drop is visible in the service's probe history instead of silently
     * thinning coverage.
     *
     * Non-blocking by contract: the channel refuses rather than suspends, so this
     * is safe to call from a Quartz thread, from a dispatch worker, or from the
     * error path of either.
     */
    private fun recordSkipped(serviceId: UUID, reason: String, at: Instant) {
        val result = shedChannel.trySend(ShedRecord(serviceId, reason, at))
        if (result.isFailure) {
            val lost = unrecordedSheds.incrementAndGet()
            if (lost % 100 == 1L) {
                log.warn("shed recorder is not keeping up — {} sheds not written to history", lost)
            }
        }
    }

    /** Writes one queued shed to the result queue. Runs on this queue's own threads. */
    private fun writeSkipped(record: ShedRecord) {
        try {
            val ctx = transaction { resolveContext(record.serviceId) } ?: return
            resultPublisher.publish(
                jobId = UUID.randomUUID(),
                serviceId = record.serviceId,
                agentId = null,
                projectId = ctx.projectId,
                workspaceId = ctx.workspaceId,
                organizationId = ctx.orgId,
                rawResult = buildJsonObject {
                    put("outcome", "skipped")
                    put("reason", record.reason)
                    put("elapsedMs", 0)
                },
                // The instant the tick was shed, not the instant this recorder
                // got to it — the row belongs to the minute it was due.
                startedAt = record.at,
                agentEgressBytes = 0L, // nothing was dispatched to an agent
            )
        } catch (e: Exception) {
            // Never let bookkeeping break the scheduling path.
            log.debug("failed to record skipped probe for {}: {}", record.serviceId, e.message)
        }
    }

    /** Starts the dispatcher worker pool. Call once at startup. */
    fun start(scope: CoroutineScope) {
        repeat(workers) { workerId ->
            scope.launch(dispatchThreads) {
                log.debug("Dispatcher worker {} started", workerId)
                for (serviceId in channel) {
                    dispatch(serviceId, workerId)
                }
            }
        }
        repeat(SHED_RECORDERS) {
            scope.launch(dispatchThreads) {
                for (record in shedChannel) {
                    writeSkipped(record)
                }
            }
        }
        log.info("Dispatch queue started: capacity={}, workers={}", capacity, workers)
    }

    /** Shuts down the queue, cancelling pending items. */
    fun close() {
        channel.close()
        shedChannel.close()
    }

    /** Returns the number of dropped dispatches since startup. */
    fun droppedTotal(): Long = droppedCount.get()

    /**
     * Runs one tick, and guarantees it leaves a trace either way.
     *
     * A failure inside [runDispatch] — the connection pool timing out under
     * contention was the observed one — used to be caught by the worker loop and
     * logged, and the probe then produced no result row at all, not even a
     * skipped one. The service's history simply thinned. Anything that fails
     * before a result was published is now written down as a skipped tick naming
     * the scheduler as the cause, which is the honest attribution: nothing was
     * learned about the target, and nothing about the target or the agents
     * explains it.
     */
    private suspend fun dispatch(serviceId: UUID, workerId: Int) {
        val accounted = AtomicBoolean(false)
        try {
            runDispatch(serviceId, accounted)
        } catch (e: Exception) {
            log.error("Dispatcher worker {} failed for service {}: {}", workerId, serviceId, e.message, e)
            if (!accounted.get()) recordSkipped(serviceId, "dispatch_error", Instant.now())
        }
    }

    private suspend fun runDispatch(serviceId: UUID, accounted: AtomicBoolean) {
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

            // The addresses this tick would actually hand an agent, judged with
            // every variable already substituted — a script whose URL is
            // assembled from `$o.endpoint` cannot be judged from its source.
            // This is the authoritative check the platform can make; the agent
            // makes the connection, so redirects and its own resolver are
            // outside it (see ProbeTargetPolicy).
            val resolvedVars = variables.mapValues { (_, v) -> v.jsonPrimitive.content }
            val target = ProbeTargetPolicy.evaluate(script, resolvedVars, targetPolicy)
            if (!target.allowed) {
                log.warn(
                    "service {} targets an address this install does not permit ({} — {}) — skipping",
                    serviceId, target.url, target.reason,
                )
                // A skipped row, not a failure: nothing was learned about the
                // target, and a synthetic failure would read as downtime for a
                // service that may be perfectly healthy. The reason names the
                // policy so the gap is explicable from the history alone.
                recordSkipped(serviceId, target.reason ?: "target_blocked", Instant.now())
                accounted.set(true)
                return
            }

            // Anti-abuse limits for unverified target domains (spec §18.4):
            // max 3 calls, no body saving, min 5-minute interval. The rule
            // below narrows the service's own setting — it never widens it, so
            // a service that saves bodies still loses them on unverified
            // domains.
            var allowBodySave = service[Services.saveResponseBodies]
            if (!trustedDomainMode) {
                val policy = transaction { DomainPolicy.evaluate(script, resolvedVars, ctx.orgId) }
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
            // The run starts here. This instant — not the time the ingestor
            // happens to read the result — is what every row derived from this
            // tick is filed under. It is the closest thing the platform knows to
            // when the probe actually happened: the agent is about to be handed
            // the script, and it does not report a clock of its own.
            val startedAt = Instant.now()
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
                // Nothing ran, and silence on a monitoring dashboard reads as
                // "all fine". Record the tick as skipped so the gap is visible
                // in the service's probe history — the one condition under
                // which the whole fleet can drop out at once (every agent
                // failing its health challenge) used to produce a log line and
                // nothing else.
                log.warn("service {} has no eligible probe executor", serviceId)
                recordSkipped(serviceId, "no_eligible_agent", startedAt)
                accounted.set(true)
                return
            }

            var published = 0
            for (execution in executions) {
                // No result: the backend exhausted every agent it was allowed
                // to re-run on. Handled after the loop — in `simultaneous` mode
                // a sibling execution may still have produced one, and one
                // agent failing is not the same as the tick observing nothing.
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
                    startedAt = startedAt,
                    agentEgressBytes = execution.egressBytes,
                )
                published++
                accounted.set(true)
            }

            if (published == 0) {
                // Agents were selected and every one of them failed at the
                // agent level — the case an agent container killed between
                // health rounds produces. Nothing was learned about the target,
                // so this is recorded as a skipped tick naming the cause, not
                // as a failure the service did not have: a synthetic failure
                // would flip last_status and count as downtime for a service
                // that may be perfectly healthy. The alert is what makes it
                // loud; the row is what makes the gap visible.
                val reason = executions.firstNotNullOfOrNull { it.failureReason } ?: "agent_unreachable"
                log.warn("service {} produced no result from {} execution(s): {}", serviceId, executions.size, reason)
                recordSkipped(serviceId, reason, startedAt)
                accounted.set(true)
                return
            }

            log.info("dispatched service {} ({} execution(s))", serviceId, executions.size)
        } finally {
            // A lock release that fails must not be mistaken for a tick that
            // produced nothing — by this point the results are already on the
            // queue.
            val hasPending = try {
                queuePolicy.release(serviceId, lockToken)
            } catch (e: Exception) {
                log.warn("failed to release lock for service {}: {}", serviceId, e.message)
                false
            }
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

    /** Names the dispatch threads so a thread dump says which pool is busy. */
    private class DispatchThreadFactory : ThreadFactory {
        private val seq = AtomicLong(0)
        override fun newThread(r: Runnable): Thread = Thread(r, "dispatch-${seq.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    companion object {
        /**
         * Sheds buffered for recording. Generous: one entry is a UUID, a short
         * string and an instant, and the buffer only fills if the recorders are
         * behind — precisely when the sheds are most worth keeping.
         */
        const val SHED_BUFFER = 10_000

        /**
         * Threads writing shed rows. Two is enough — each write is one small
         * transaction and one Redis push — and keeping it small leaves the
         * connection pool to the dispatchers.
         */
        const val SHED_RECORDERS = 2
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
