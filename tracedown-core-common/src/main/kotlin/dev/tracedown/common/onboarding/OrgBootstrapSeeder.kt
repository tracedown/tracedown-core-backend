package dev.tracedown.common.onboarding

import dev.tracedown.common.models.NotificationTemplates
import dev.tracedown.common.models.OrgRulePresets
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.util.UUID

/**
 * Seam for the starter templates seeded into every new org at bootstrap.
 *
 * The default is the built-in hardcoded set ([DefaultRulePresets], with no
 * email templates). A host may override the provider to source templates from
 * its own storage. [seedForOrg] runs inside the org-creation transaction, so a
 * provider may read its tables directly. The inserts are owned here either
 * way; a provider only supplies the content.
 */
object OrgBootstrapSeeder {

    data class RulePreset(val displayName: String, val script: String)
    data class EmailTemplate(val name: String, val text: String)
    data class Templates(
        val rulePresets: List<RulePreset>,
        val emailTemplates: List<EmailTemplate> = emptyList(),
    )

    private var provider: () -> Templates = {
        Templates(DefaultRulePresets.PRESETS.map { RulePreset(it.name, it.script) })
    }

    /** Overrides where the starter templates come from. */
    fun register(provider: () -> Templates) {
        this.provider = provider
    }

    /** Inserts the current provider's templates into [orgId]. Call within a transaction. */
    fun seedForOrg(orgId: UUID, createdBy: UUID) {
        val templates = provider()
        val now = Instant.now()
        for (p in templates.rulePresets) {
            OrgRulePresets.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[workspaceId] = null
                it[OrgRulePresets.createdBy] = createdBy
                it[displayName] = p.displayName
                it[script] = p.script
                it[createdAt] = now
            }
        }
        for (e in templates.emailTemplates) {
            NotificationTemplates.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[name] = e.name
                it[text] = e.text
                it[createdAt] = now
            }
        }
    }
}
