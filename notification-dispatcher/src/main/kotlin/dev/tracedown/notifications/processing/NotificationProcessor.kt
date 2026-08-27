package dev.tracedown.notifications.processing

import dev.tracedown.common.models.NotificationLog
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import dev.tracedown.notifications.delivery.EmailDeliveryService
import dev.tracedown.notifications.delivery.WebhookDeliveryService
import dev.tracedown.notifications.recipients.RecipientCooldown
import dev.tracedown.notifications.recipients.RecipientResolver
import dev.tracedown.notifications.templates.NotificationBuilder
import dev.tracedown.notifications.templates.RenderedNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.*
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Orchestrates the full notification processing pipeline.
 *
 * For each outbox event:
 * 1. Reads the probe result's rawResult.actions.notifications
 * 2. Groups and renders notifications via NotificationBuilder
 * 3. Resolves eligible recipients
 * 4. Delivers via email and webhooks
 *
 * ## Failing one channel must not re-send the other
 *
 * The outbox row is marked published only when [process] returns normally, so a
 * throw anywhere re-runs the whole event on the next poll. Email and webhook
 * used to share that fate: a webhook-side error left the event unpublished and
 * the same email went out again five seconds later, and again, and again.
 *
 * Two things fix that. Each channel is now attempted independently, so one
 * failing does not skip the other; and each channel is idempotent per probe
 * result, because `notification_log` already records exactly who was dispatched
 * to for a result and is consulted before dispatching. A re-run therefore
 * completes what was missed and repeats nothing. The event is still left
 * unpublished when a channel fails, which is what makes the re-run happen.
 */
class NotificationProcessor(
    private val emailDeliveryService: EmailDeliveryService,
    private val webhookDeliveryService: WebhookDeliveryService,
    private val recipientCooldown: RecipientCooldown,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Processes a single outbox event payload.
     *
     * @param payload the outbox event payload containing resultId, serviceId, etc.
     */
    suspend fun process(payload: JsonObject) {
        val resultId = UUID.fromString(payload["resultId"]?.jsonPrimitive?.content ?: return)
        val serviceId = UUID.fromString(payload["serviceId"]?.jsonPrimitive?.content ?: return)
        val projectId = UUID.fromString(payload["projectId"]?.jsonPrimitive?.content ?: return)
        val workspaceId = UUID.fromString(payload["workspaceId"]?.jsonPrimitive?.content ?: return)
        val orgId = UUID.fromString(payload["organizationId"]?.jsonPrimitive?.content ?: return)

        // Load raw result and context
        val context = newSuspendedTransaction(Dispatchers.IO) {
            loadContext(resultId, serviceId, projectId, workspaceId)
        } ?: run {
            log.warn("could not load context for result {}", resultId)
            return
        }

        // Check if there are notifications to process
        val actions = context.rawResult["actions"]?.jsonObject
        val notifications = actions?.get("notifications")
        if (notifications == null) {
            log.debug("no notifications in result {}", resultId)
            return
        }

        // Build static vars from context, plus ${downtime} for recovery messages.
        // The ingestor computes the downtime (it alone has the pre-update
        // last_status_since) and sends it only on a recovery — here we just
        // format it into the message.
        val downtime = payload["downtimeSeconds"]?.jsonPrimitive?.longOrNull
            ?.let { formatDuration(it) } ?: "an unknown period"
        val staticVars = buildStaticVars(context) + ("downtime" to downtime)

        // Render notifications
        val rendered = NotificationBuilder.buildNotifications(
            rawResult = context.rawResult,
            staticVars = staticVars,
            orgId = orgId,
            projectId = projectId,
        )

        if (rendered.isEmpty()) {
            log.debug("no rendered notifications for result {}", resultId)
            return
        }

        log.info("processing {} notification event(s) for result {} (one dispatch per channel)", rendered.size, resultId)

        // Resolve recipients
        val recipients = RecipientResolver.resolve(
            orgId = orgId,
            workspaceId = workspaceId,
            projectId = projectId,
            serviceId = serviceId,
            channel = EMAIL_CHANNEL,
        )

        // One dispatch per service per run \u2014 NOT per failure event. A single run
        // can raise several notification events (e.g. a soft .check and a hard
        // .expect both failing, or several failing calls); coalesce them into one
        // message so each channel fires exactly once per run. The rendered texts
        // are already baked, so distinct+join gives the combined body.
        val combinedText = rendered.map { it.text }.distinct().joinToString("\n")
        val primary = rendered.first()
        val recovered = rendered.any { it.trigger == "recovered" }
        val subject = "[Tracedown] ${context.serviceName} \u2014 ${if (recovered) "recovered" else "failure"}"
        val webhookVars = staticVars + buildRuntimeVars(primary, context) + mapOf("text" to combinedText)

        // Anti-storm cooldown: drop recipients still within the per-recipient
        // cooldown for this service+channel+kind. The kind matters: a recovery
        // is not a repeat of the failure before it, and must never be swallowed
        // by that failure's window (see RecipientCooldown). Suppression is
        // silent (no log row), like silenced/quiet-hours filtering upstream.
        val kind = RecipientCooldown.kindOf(recovered)
        val eligible = recipients.filter {
            !recipientCooldown.isOnCooldown(it.orgUserId, serviceId, EMAIL_CHANNEL, kind)
        }

        // Re-processing guard: anyone already logged for this probe result was
        // dispatched to on an earlier pass that failed on the other channel.
        // They are not mailed twice.
        val alreadyEmailed = newSuspendedTransaction(Dispatchers.IO) {
            loggedRecipients(resultId, EMAIL_CHANNEL)
        }
        val toEmail = eligible.filterNot { it.email in alreadyEmailed }

        // Each channel stands on its own: one throwing must not skip the other,
        // and must not undo what the other already did.
        var firstFailure: Exception? = null

        try {
            if (toEmail.isNotEmpty()) {
                // combinedText is already fully rendered \u2014 deliver it as the body.
                emailDeliveryService.deliver(
                    recipients = toEmail,
                    template = combinedText,
                    vars = emptyMap(),
                    subject = subject,
                    orgId = orgId,
                    serviceId = serviceId,
                    probeResultId = resultId,
                )
            }
            // Open the cooldown window for everyone this result was dispatched
            // to \u2014 the ones just mailed, and the ones an earlier pass mailed.
            eligible.forEach { recipientCooldown.markDispatched(it.orgUserId, serviceId, EMAIL_CHANNEL, kind) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // shutdown, not a delivery failure
        } catch (e: Exception) {
            log.error("email delivery failed for result {}: {}", resultId, e.message, e)
            firstFailure = e
        }

        try {
            webhookDeliveryService.deliver(
                orgId = orgId,
                workspaceId = workspaceId,
                projectId = projectId,
                serviceId = serviceId,
                probeResultId = resultId,
                vars = webhookVars,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e // shutdown, not a delivery failure
        } catch (e: Exception) {
            log.error("webhook dispatch failed for result {}: {}", resultId, e.message, e)
            if (firstFailure == null) firstFailure = e
        }

        // Leaving the event unpublished is what schedules the retry. The two
        // guards above are what make that retry safe.
        firstFailure?.let { throw it }
    }

    /** Email addresses already logged for this probe result on [channel]. */
    private fun loggedRecipients(probeResultId: UUID, channel: String): Set<String> =
        NotificationLog
            .select(NotificationLog.recipient)
            .where {
                (NotificationLog.probeResultId eq probeResultId) and
                    (NotificationLog.channel eq channel)
            }
            .map { it[NotificationLog.recipient] }
            .toSet()

    private fun buildRuntimeVars(
        notification: RenderedNotification,
        context: ProcessingContext,
    ): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        vars["trigger"] = notification.trigger
        vars["callIndex"] = notification.callIndex.toString()
        if (notification.scope != null) vars["scope"] = notification.scope

        val calls = context.rawResult["calls"]?.jsonArray
        val call = calls?.getOrNull(notification.callIndex) as? JsonObject
        if (call != null) {
            vars["url"] = (call["request"] as? JsonObject)?.get("url")?.jsonPrimitive?.content ?: ""
            vars["ms"] = (call["response"] as? JsonObject)?.get("responseTimeMs")?.jsonPrimitive?.content
                ?: context.rawResult["elapsedMs"]?.jsonPrimitive?.content ?: ""
        } else {
            vars["url"] = ""
            vars["ms"] = context.rawResult["elapsedMs"]?.jsonPrimitive?.content ?: ""
        }

        vars["status"] = context.rawResult["outcome"]?.jsonPrimitive?.content ?: ""

        // Extract expected/actual from matching notification event
        val actions = context.rawResult["actions"]?.jsonObject
        val notifArray = actions?.get("notifications")?.jsonArray
        val matchingEvent = notifArray?.firstOrNull { evt ->
            val obj = evt.jsonObject
            obj["callIndex"]?.jsonPrimitive?.intOrNull == notification.callIndex &&
                obj["trigger"]?.jsonPrimitive?.content == notification.trigger
        }?.jsonObject

        val data = matchingEvent?.get("notification")?.jsonObject?.get("data")
        if (data is JsonObject) {
            vars["expected"] = data["expected"]?.jsonPrimitive?.contentOrNull ?: ""
            vars["actual"] = data["actual"]?.jsonPrimitive?.contentOrNull ?: ""
        }

        return vars
    }

    private data class ProcessingContext(
        val rawResult: JsonObject,
        val serviceName: String,
        val serviceSchedule: String,
        val projectName: String,
        val workspaceName: String,
    )

    /** Formats a duration in seconds as its two most-significant units (e.g. "2d 3h", "45m 10s"). */
    private fun formatDuration(totalSeconds: Long): String {
        val secs = totalSeconds.coerceAtLeast(0)
        val days = secs / 86_400
        val hours = (secs % 86_400) / 3_600
        val minutes = (secs % 3_600) / 60
        val seconds = secs % 60
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    private fun loadContext(
        resultId: UUID,
        serviceId: UUID,
        projectId: UUID,
        workspaceId: UUID,
    ): ProcessingContext? {
        val result = ProbeResults.selectAll()
            .where { ProbeResults.id eq resultId }
            .firstOrNull() ?: return null

        val service = Services.selectAll()
            .where { Services.id eq serviceId }
            .firstOrNull() ?: return null

        val project = Projects.selectAll()
            .where { Projects.id eq projectId }
            .firstOrNull() ?: return null

        val workspace = Workspaces.selectAll()
            .where { Workspaces.id eq workspaceId }
            .firstOrNull() ?: return null

        return ProcessingContext(
            rawResult = result[ProbeResults.rawResult],
            serviceName = service[Services.name],
            serviceSchedule = service[Services.schedule],
            projectName = project[Projects.name],
            workspaceName = workspace[Workspaces.name],
        )
    }

    private fun buildStaticVars(context: ProcessingContext): Map<String, String> = mapOf(
        "w.name" to context.workspaceName,
        "p.name" to context.projectName,
        "s.name" to context.serviceName,
        "s.schedule" to context.serviceSchedule,
    )

    private companion object {
        /**
         * The only channel recipient resolution runs for. Silences and quiet
         * hours are per-user controls and so apply to mail alone; webhooks are
         * bound to resources, not to people, and are resolved independently by
         * [WebhookDeliveryService].
         */
        const val EMAIL_CHANNEL = "email"
    }
}
