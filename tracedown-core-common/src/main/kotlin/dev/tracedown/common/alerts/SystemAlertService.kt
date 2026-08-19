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
