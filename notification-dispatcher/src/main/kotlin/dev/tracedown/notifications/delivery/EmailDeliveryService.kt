package dev.tracedown.notifications.delivery

import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.NotificationLog
import dev.tracedown.notifications.recipients.Recipient
import dev.tracedown.notifications.templates.TemplateRenderer
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

/**
 * Delivers notification emails to recipients.
 *
 * Renders the notification template into HTML (with variable highlighting),
 * publishes to the email queue for the email-service to pick up, and logs
 * each delivery to notification_log.
 *
 * **`notification_log.recipient` holds a real email address**, and the table has
 * no foreign key to the account it belongs to — it is keyed on the organization.
 * That means nothing about it is reached by erasing an account: the row is
 * deleted by the purge job matching on the address itself, and aged out by
 * `NotificationLogRetentionJob`. If a delivery ever starts recording a different
 * kind of recipient identifier here, the erasure path (aggregate-worker's
 * PurgeJob) has to learn about it in the same change — an address that survives
 * erasure is the whole failure mode.
 */
class EmailDeliveryService(private val emailPublisher: EmailPublisher) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Publishes notification emails for each recipient to the email queue.
     *
     * @param recipients the list of eligible recipients
     * @param template the raw template text (with ${var} placeholders)
     * @param vars the variable context for rendering
     * @param subject the email subject line
     * @param orgId the organization ID for logging
     * @param serviceId the service ID for logging
     * @param probeResultId the probe result ID for logging
     */
    suspend fun deliver(
        recipients: List<Recipient>,
        template: String,
        vars: Map<String, String>,
        subject: String,
        orgId: UUID,
        serviceId: UUID,
        probeResultId: UUID,
    ) {
        val htmlContent = TemplateRenderer.renderHtml(template, vars)

        for (recipient in recipients) {
            // Log before publishing so the email-service's status event
            // (consumed by EmailStatusConsumer) can never race the insert.
            val logId = UUID.randomUUID()
            newSuspendedTransaction(Dispatchers.IO) {
                NotificationLog.insert {
                    it[id] = logId
                    it[NotificationLog.organizationId] = orgId
                    it[NotificationLog.serviceId] = serviceId
                    it[NotificationLog.probeResultId] = probeResultId
                    it[channel] = "email"
                    it[NotificationLog.recipient] = recipient.email
                    it[NotificationLog.status] = "queued"
                    it[createdAt] = Instant.now()
                }
            }

            emailPublisher.publishBody(
                to = recipient.email,
                subject = subject,
                body = htmlContent,
                source = "notification-dispatcher",
                notificationLogId = logId,
                // Alerts are unsolicited, so they explain why they arrived. The
                // layout omits this line for mail that does not ask for it.
                footer = "You received this because you have access to a monitored service on Tracedown.",
            )
            log.debug("email queued for {} for result {}", recipient.email, probeResultId)
        }
    }
}
