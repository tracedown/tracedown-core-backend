package dev.tracedown.common.alerts

import dev.tracedown.common.models.SystemAlerts
import dev.tracedown.common.realtime.RealtimePublisher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Platform-raised operational alerts, surfaced as dismissable banners to org
 * admins (spec: settings write access).
 *
 * One row per (org, type, subject) "episode": while the condition keeps
 * recurring, `last_seen_at` refreshes and per-user dismissals hold. An alert
 * counts as ACTIVE while `last_seen_at` is within [ACTIVE_WINDOW]; a
 * condition returning after that starts a NEW episode — `created_at` resets
 * and dismissals clear, so the banner reappears.
 */
object SystemAlertService {

    /** How long after the last observation an alert still shows. */
    val ACTIVE_WINDOW: Duration = Duration.ofMinutes(10)

    /** Alert type vocabulary (extend freely — the table is generic). */
    const val DISPATCH_CAPACITY = "dispatch_capacity"
    const val AGENT_DOWN = "agent_down"
    const val AGENT_DEGRADED = "agent_degraded"

    /**
     * A probe tick found no executor it was allowed to run on, so the run was
     * recorded as skipped. Distinct from [DISPATCH_CAPACITY]: nothing was over
     * capacity, there was simply nothing healthy (or nothing allowlisted) to
     * dispatch to.
     */
    const val NO_ELIGIBLE_AGENT = "no_eligible_agent"

    /**
     * Agents were available and every one of them failed to take the run — the
     * connection was refused, the handshake did not complete, or the job was
     * turned away. Distinct from [NO_ELIGIBLE_AGENT] (there was nothing to
     * dispatch to) and from [AGENT_DOWN] (a health round's verdict about one
     * named agent): here the fleet looked healthy and dispatch still failed,
     * which is what an agent lost between health rounds looks like.
     */
    const val AGENT_DISPATCH_FAILED = "agent_dispatch_failed"

    /**
     * A finished probe result could not be recorded and has been set aside on
     * the dead-letter queue instead of being retried any further. The run
     * happened; its history row does not exist and will not appear on its own.
     *
     * This is the one alert that reports a gap the platform itself created, so
     * it is raised at `error`, not `warning`: nothing else in the product will
     * ever show the missing result, and the operator has to go and look at the
     * queue. Distinct from the dispatch alerts, which are all about runs that
     * never produced a result to begin with.
     */
    const val RESULT_INGEST_FAILED = "result_ingest_failed"

    /**
     * A probe tick was lost to a fault inside the scheduler itself rather than
     * to anything about the agents or the target — the database was
     * unreachable, a pooled connection could not be had, the dispatch path
     * raised. Distinct from every agent alert: nothing was wrong with the fleet,
     * and telling the org to check its agents or reduce its probe frequency
     * would send it past the actual fault, which is the operator's.
     */
    const val SCHEDULER_ERROR = "scheduler_error"

    /**
     * A registered outbox consumer stopped advancing its cursor while behind the
     * head of the log, for longer than the purge job is willing to wait. The
     * purge no longer holds the log back for it, so events it never read will
     * age out of the retention window.
     *
     * Platform infrastructure, not any one organization's concern: raised
     * through the routing seam with a null org, never to org banners.
     */
    const val OUTBOX_CONSUMER_STALLED = "outbox_consumer_stalled"

    /**
     * The health challenge could not be completed because the token endpoint
     * (or the store behind it) was unreachable from the scheduler itself. The
     * round says nothing about the agent, so the subject names the endpoint,
     * not an agent.
     */
    const val HEALTH_TOKEN_UNAVAILABLE = "health_token_unavailable"

    /**
     * Agent challenge round-trips above this raise [AGENT_DEGRADED]. The
     * challenge is a cold mTLS handshake plus the agent's callback to the
     * gateway (~5 round trips), so a healthy agent on another continent
     * legitimately takes ~1–1.2 s — the boundary sits at the top of that,
     * flagging only what geography can't explain.
     */
    const val DEGRADED_RTT_MS = 1200

    private const val RAISE_THROTTLE_SECONDS = 60L

    private val log = LoggerFactory.getLogger(javaClass)

    /** Last raise per (org, type, subject) — keeps hot paths cheap. */
    private val lastRaise = ConcurrentHashMap<String, Instant>()

    /**
     * Records an observation of the condition. Throttled per key; safe to
     * call from hot paths (per shed probe, per health check).
     */
    fun raise(orgId: UUID, alertType: String, subject: String = "", severity: String = "warning", data: JsonObject? = null) {
        val key = "$orgId|$alertType|$subject"
        val now = Instant.now()
        val last = lastRaise[key]
        if (last != null && Duration.between(last, now).seconds < RAISE_THROTTLE_SECONDS) return
        lastRaise[key] = now

        try {
            val newEpisode = transaction {
                // Episodes are immutable history rows: the latest one is
                // refreshed while the condition recurs; a condition returning
                // after the active window gets a fresh row (and is therefore
                // undismissed by construction).
                val latest = SystemAlerts.selectAll()
                    .where {
                        (SystemAlerts.organizationId eq orgId) and
                            (SystemAlerts.alertType eq alertType) and
                            (SystemAlerts.subject eq subject)
                    }
                    .orderBy(SystemAlerts.lastSeenAt, org.jetbrains.exposed.sql.SortOrder.DESC)
                    .firstOrNull()

                if (latest != null && !latest[SystemAlerts.lastSeenAt].plus(ACTIVE_WINDOW).isBefore(now)) {
                    SystemAlerts.update({ SystemAlerts.id eq latest[SystemAlerts.id] }) {
                        it[lastSeenAt] = now
                        it[SystemAlerts.severity] = severity
                        if (data != null) it[SystemAlerts.data] = data
                    }
                    false
                } else {
                    SystemAlerts.insert {
                        it[id] = UUID.randomUUID()
                        it[organizationId] = orgId
                        it[SystemAlerts.alertType] = alertType
                        it[SystemAlerts.subject] = subject
                        it[SystemAlerts.severity] = severity
                        it[SystemAlerts.data] = data
                        it[createdAt] = now
                        it[lastSeenAt] = now
                    }
                    true
                }
            }

            if (newEpisode) {
                RealtimePublisher.publish("org:$orgId", orgId, "system.alert", buildJsonObject {
                    put("alertType", alertType)
                    put("subject", subject)
                })
            }
        } catch (e: Exception) {
            // Alerting must never break the calling pipeline.
            log.warn("failed to raise system alert {}: {}", alertType, e.message)
        }
    }
}
