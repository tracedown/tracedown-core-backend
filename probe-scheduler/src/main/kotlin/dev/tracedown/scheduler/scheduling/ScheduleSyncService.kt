package dev.tracedown.scheduler.scheduling

import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import io.lettuce.core.pubsub.RedisPubSubAdapter
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection
import kotlinx.coroutines.*
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Keeps the Quartz scheduler in sync with the services table.
 *
 * - **Startup**: full DB scan, loads all active services.
 * - **Redis pub/sub**: subscribes to `schedule:nudge` for low-latency
 *   sync when the gateway creates, updates, or deletes a service.
 * - **Consistency sweep**: periodic lightweight query (id + version),
 *   diffs against in-memory state, catches any missed pub/sub messages.
 */
class ScheduleSyncService(
    private val quartzManager: QuartzManager,
    private val sweepIntervalSeconds: Long,
    private val pubSubConnection: StatefulRedisPubSubConnection<String, String>,
) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val serviceVersions = ConcurrentHashMap<UUID, Int>()
    private var sweepJob: Job? = null

    /**
     * Every service this scheduler may run: active, not deleted, and with a
     * live project and workspace above it.
     *
     * The ancestry is part of the question, not a nicety. Deleting a container
     * is supposed to carry its services down with it, but if a cascade ever
     * misses one, selecting on `services` alone would keep probing a target
     * inside a project the owner deleted — writing results and sending alerts
     * for something that is not supposed to exist. Joining the parents in makes
     * that unreachable rather than merely unlikely; both joins are on primary
     * keys, so the sweep costs the same as before.
     */
    private fun liveServices() = (Services innerJoin Projects innerJoin Workspaces)
        .selectAll()
        .where {
            (Services.isActive eq true) and (Services.deleted eq false) and
                (Projects.deleted eq false) and (Workspaces.deleted eq false)
        }

    /** Performs initial full scan and loads all active services. */
    fun bootstrap() {
        val services = transaction {
            liveServices()
                .map { row ->
                    Triple(
                        row[Services.id],
                        row[Services.schedule],
                        row[Services.version],
                    )
                }
        }

        for ((id, schedule, version) in services) {
            try {
                quartzManager.scheduleService(id, schedule)
                serviceVersions[id] = version
            } catch (e: Exception) {
                log.warn("failed to schedule service {}: {}", id, e.message)
            }
        }

        log.info("bootstrapped {} services into scheduler", services.size)
    }

    /**
     * Subscribes to Redis pub/sub for low-latency coordination:
     * - `schedule:nudge` — re-syncs a service's Quartz job on config change.
     * - `probe:trigger` — enqueues one immediate dispatch for a service.
     */
    fun startPubSub() {
        pubSubConnection.addListener(object : RedisPubSubAdapter<String, String>() {
            override fun message(channel: String, message: String) {
                try {
                    val serviceId = UUID.fromString(message)
                    when (channel) {
                        "schedule:nudge" -> handleNudge(serviceId)
                        "probe:trigger" -> handleTrigger(serviceId)
                    }
                } catch (e: Exception) {
                    log.warn("invalid pub/sub message '{}' on {}: {}", message, channel, e.message)
                }
            }
        })
        pubSubConnection.sync().subscribe("schedule:nudge", "probe:trigger")
        log.info("subscribed to schedule:nudge and probe:trigger")
    }

    /**
     * Handles a run-now trigger for a single service by enqueueing one immediate
     * dispatch. The dispatch path applies the same guards as scheduled runs —
     * active/script checks, service window, distributed lock, and queue policy —
     * so inactive or already-running services are ignored, never double-run.
     */
    fun handleTrigger(serviceId: UUID) {
        val enqueued = ProbeJobContext.dispatchQueue.enqueue(serviceId)
        if (!enqueued) {
            log.debug("trigger: dispatch shed for service {}", serviceId)
        } else {
            log.debug("trigger: enqueued immediate dispatch for service {}", serviceId)
        }
    }

    /** Starts the periodic consistency sweep. */
    fun startSweep(scope: CoroutineScope) {
        sweepJob = scope.launch {
            while (isActive) {
                delay(sweepIntervalSeconds * 1000)
                try {
                    sweep()
                } catch (e: Exception) {
                    log.error("consistency sweep failed: {}", e.message, e)
                }
            }
        }
    }

    /** Stops the pub/sub subscription and periodic sweep. */
    fun stop() {
        sweepJob?.cancel()
        try { pubSubConnection.close() } catch (_: Exception) {}
    }

    /**
     * Handles a schedule nudge for a single service.
     * Called when a Redis pub/sub message arrives.
     */
    fun handleNudge(serviceId: UUID) {
        try {
            val service = transaction {
                (Services innerJoin Projects innerJoin Workspaces).selectAll()
                    .where {
                        (Services.id eq serviceId) and
                            (Projects.deleted eq false) and (Workspaces.deleted eq false)
                    }
                    .firstOrNull()
            }

            // A missing row now also means "its project or workspace is gone",
            // which unschedules on exactly the same branch.
            if (service == null || service[Services.deleted] || !service[Services.isActive]) {
                quartzManager.unscheduleService(serviceId)
                serviceVersions.remove(serviceId)
                log.debug("nudge: unscheduled service {}", serviceId)
            } else {
                quartzManager.scheduleService(serviceId, service[Services.schedule])
                serviceVersions[serviceId] = service[Services.version]
                log.debug("nudge: updated service {}", serviceId)
            }
        } catch (e: Exception) {
            log.warn("failed to handle nudge for service {}: {}", serviceId, e.message)
        }
    }

    /** Lightweight consistency sweep — only reads id + version. */
    private fun sweep() {
        val dbState = transaction {
            liveServices()
                .associate { row ->
                    row[Services.id] to Pair(row[Services.schedule], row[Services.version])
                }
        }

        val scheduledIds = quartzManager.getScheduledServiceIds()

        // Add or update services
        for ((id, pair) in dbState) {
            val (schedule, version) = pair
            val currentVersion = serviceVersions[id]
            if (currentVersion == null || currentVersion != version) {
                try {
                    quartzManager.scheduleService(id, schedule)
                    serviceVersions[id] = version
                } catch (e: Exception) {
                    log.warn("sweep: failed to schedule service {}: {}", id, e.message)
                }
            }
        }

        // Remove services no longer in DB
        for (id in scheduledIds) {
            if (id !in dbState) {
                quartzManager.unscheduleService(id)
                serviceVersions.remove(id)
            }
        }

        log.debug("consistency sweep: {} services in DB, {} scheduled", dbState.size, quartzManager.getScheduledServiceIds().size)
    }
}
