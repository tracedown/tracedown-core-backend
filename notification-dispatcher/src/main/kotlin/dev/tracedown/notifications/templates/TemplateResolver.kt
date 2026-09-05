package dev.tracedown.notifications.templates

import dev.tracedown.common.models.NotificationTemplates
import dev.tracedown.common.models.ProjectNotificationTemplates
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Resolves the template text for a notification event.
 *
 * Resolution order:
 * 1. Named template lookup (notification tag = "template") → DB lookup by org + name, bound to project
 * 2. Text literal (notification tag = "text") → use the text directly
 * 3. Structured / fallback → default template based on trigger type
 */
object TemplateResolver {

    // ${conditions} lists every failed scope in the group, e.g.
    // "[status: expected 200, got 500; totalDelayMs: expected 2, got 165]"
    // (baseline spikes render "average N" instead of "expected N").
    private val DEFAULT_STRUCTURED = "\${s.name} in \${w.name}.\${p.name} call to \${url} has \"\${trigger}\": \${conditions}"
    private val DEFAULT_ASSERT = "\${s.name} in \${w.name}.\${p.name} call to \${url} assertion failed: expected \${expected}, got \${actual}"
    private val DEFAULT_TIMEOUT = "\${s.name} call to \${url} timed out after \${ms}ms"
    private val DEFAULT_RECOVERED = "\${s.name} in \${w.name}.\${p.name} recovered after \${downtime} of downtime"
    private val DEFAULT_ERROR = "\${s.name} in \${w.name}.\${p.name} call to \${url} failed: \${text}"

    /**
     * Resolves a template by name for the given org and project.
     *
     * @return the template text if found and bound to the project, null otherwise
     */
    fun resolveByName(orgId: UUID, projectId: UUID, templateName: String): String? {
        return transaction {
            val template = NotificationTemplates.selectAll()
                .where {
                    (NotificationTemplates.organizationId eq orgId) and
                        (NotificationTemplates.name eq templateName) and
                        (NotificationTemplates.deleted eq false)
                }
                .firstOrNull() ?: return@transaction null

            // Verify it's bound to this project
            val bound = ProjectNotificationTemplates.selectAll()
                .where {
                    (ProjectNotificationTemplates.notificationTemplateId eq template[NotificationTemplates.id]) and
                        (ProjectNotificationTemplates.projectId eq projectId)
                }
                .firstOrNull()

            if (bound != null) template[NotificationTemplates.text] else null
        }
    }

    /**
     * Returns the default template for the given trigger type.
     */
    fun defaultTemplate(trigger: String): String = when (trigger) {
        "expect", "check", "baseline_spike" -> DEFAULT_STRUCTURED
        "assert" -> DEFAULT_ASSERT
        "timeout" -> DEFAULT_TIMEOUT
        "recovered" -> DEFAULT_RECOVERED
        "error" -> DEFAULT_ERROR
        else -> DEFAULT_STRUCTURED
    }
}
