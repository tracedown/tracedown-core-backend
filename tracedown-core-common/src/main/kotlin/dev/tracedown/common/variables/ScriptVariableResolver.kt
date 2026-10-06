package dev.tracedown.common.variables

import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.util.VariableCrypto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * The variables a probe script runs with, and the script rewritten to read them.
 *
 * Users write `$s.key`, `$p.key`, `$w.key`, `$o.key`. Lace has no scopes and
 * its identifiers have no dots, so each scoped reference is looked up in its
 * scope and rewritten to a plain identifier (`$s.key` → `$s_key`) that the
 * injected map carries. Only explicitly scoped references are resolved —
 * there is no fallback chain, and an unscoped `$name` is left for the
 * executor, which interpolates it to null. Computed variables (`s_name`,
 * `p_name`, …) and the service's config variables are always injected, and
 * win over a stored variable of the same name.
 *
 * The scheduler builds every run's variables here, and the gateway builds the
 * values its save-time target checks judge here too, so a script cannot be
 * accepted against one set of values and dispatched with another. References
 * are read by [ScriptReferences], as everywhere else.
 */
object ScriptVariableResolver {

    private val log = LoggerFactory.getLogger(ScriptVariableResolver::class.java)

    /** How often one unreadable variable is reported, at most. */
    private const val UNREADABLE_WARN_INTERVAL_MS = 60 * 60 * 1000L

    private val lastUnreadableWarning = ConcurrentHashMap<String, Long>()

    class Resolved(
        /** The script with each scoped reference rewritten to the identifier it is injected as. */
        val script: String,
        val variables: JsonObject,
        /**
         * Decrypted plaintext of the SECRET variables this run uses — redacted
         * out of the ProbeResult before it is published or stored, so a secret
         * never surfaces in a result even if a script puts it in a URL or header.
         */
        val secretValues: Set<String>,
        /** Keys of [variables] whose values were decrypted; never to be shown to anyone. */
        val decrypted: Set<String>,
        /**
         * References the script makes, as written (`p.apiToken`), to variables
         * that exist but whose values would not decrypt. They are absent from
         * [variables]; a run must not go ahead without them.
         */
        val unreadable: List<String>,
    )

    /**
     * Resolves the variables [script] uses for [serviceId]. Joins the caller's
     * transaction when there is one. A service that does not exist resolves to
     * nothing, with the script as given.
     */
    fun resolve(serviceId: UUID, script: String): Resolved = transaction {
        val none = Resolved(script, buildJsonObject {}, emptySet(), emptySet(), emptyList())
        val service = Services.selectAll().where { Services.id eq serviceId }.firstOrNull()
            ?: return@transaction none
        val projectId = service[Services.projectId]
        val project = Projects.selectAll().where { Projects.id eq projectId }.firstOrNull()
            ?: return@transaction none
        val workspaceId = project[Projects.workspaceId]
        val workspace = Workspaces.selectAll().where { Workspaces.id eq workspaceId }.firstOrNull()
            ?: return@transaction none
        val orgId = workspace[Workspaces.organizationId]

        val scoped = ScriptReferences.find(script).filter { !it.run && it.scope != null }
        val wanted = scoped.groupBy({ it.scope!! }, { it.key!! }).mapValues { it.value.toSet() }

        // Only the variables the script names are read, and so only those are
        // ever decrypted.
        val scopes = mapOf(
            "o" to load(OrgVariables, OrgVariables.organizationId, orgId, wanted["o"], orgId, "org"),
            "w" to load(WorkspaceVariables, WorkspaceVariables.workspaceId, workspaceId, wanted["w"], orgId, "workspace"),
            "p" to load(ProjectVariables, ProjectVariables.projectId, projectId, wanted["p"], orgId, "project"),
            "s" to load(ServiceVariables, ServiceVariables.serviceId, serviceId, wanted["s"], orgId, "service"),
        )

        val resolved = linkedMapOf<String, String>()
        val secretValues = mutableSetOf<String>()
        val decrypted = mutableSetOf<String>()
        val unreadable = linkedSetOf<String>()
        for (ref in scoped) {
            when (val entry = scopes.getValue(ref.scope!!)[ref.key!!]) {
                null -> Unit
                is Entry.Unreadable -> unreadable += ref.name
                is Entry.Value -> {
                    resolved[ref.variable] = entry.value
                    if (entry.encrypted) decrypted += ref.variable
                    if (entry.secret && entry.value.isNotBlank()) secretValues += entry.value
                }
            }
        }

        val rewritten = ScriptReferences.replace(script) { ref ->
            if (ref.run || ref.scope == null) null
            else if (ref.braced) "\${\$${ref.variable}}"
            else "\$${ref.variable}"
        }

        // Later entries win: computed over config over stored.
        val config = loadConfigVars(serviceId)
        val computed = mapOf(
            "s_name" to service[Services.name],
            "s_lastStatus" to (service[Services.lastStatus] ?: ""),
            "s_lastStatusSince" to (service[Services.lastStatusSince]?.toString() ?: ""),
            "s_lastStatusConsecutive" to service[Services.lastStatusConsecutive].toString(),
            "p_name" to project[Projects.name],
            "w_name" to workspace[Workspaces.name],
        )
        for (key in config.keys + computed.keys) decrypted -= key
        val variables = buildJsonObject {
            for ((k, v) in resolved) put(k, v)
            for ((k, v) in config) put(k, v)
            for ((k, v) in computed) put(k, v)
        }

        Resolved(rewritten, variables, secretValues, decrypted, unreadable.toList())
    }

    private sealed interface Entry {
        class Value(val value: String, val secret: Boolean, val encrypted: Boolean) : Entry
        object Unreadable : Entry
    }

    /**
     * The non-deleted variables named [keys] in one scope. [orgId] and [scope]
     * are the encryption context: secrets are envelope-encrypted with the org's
     * key, everything else with the platform key, and [VariableCrypto.decrypt]
     * tells them apart by the stored format. A value that will not decrypt is
     * [Entry.Unreadable]; a database error is not caught here.
     */
    private fun load(
        table: Table,
        scopeColumn: Column<UUID>,
        scopeId: UUID,
        keys: Set<String>?,
        orgId: UUID,
        scope: String,
    ): Map<String, Entry> {
        if (keys.isNullOrEmpty()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        fun <T> col(name: String) = table.columns.first { it.name == name } as Column<T>
        val keyCol = col<String>("key")
        val valueCol = col<String>("value")
        val ivCol = col<String?>("value_iv")
        val encCol = col<Boolean>("encrypted")
        val secretCol = col<Boolean>("secret")
        val deletedCol = col<Boolean>("deleted")

        val result = mutableMapOf<String, Entry>()
        table.selectAll()
            .where { (scopeColumn eq scopeId) and (deletedCol eq false) and (keyCol inList keys) }
            .forEach { row ->
                val key = row[keyCol]
                val encrypted = row[encCol]
                val stored = row[valueCol]
                result[key] = if (!encrypted) {
                    Entry.Value(stored, row[secretCol], encrypted = false)
                } else {
                    decrypt(orgId, stored, row[ivCol], scope, key)
                        ?.let { Entry.Value(it, row[secretCol], encrypted = true) }
                        ?: Entry.Unreadable
                }
            }
        return result
    }

    /**
     * The plaintext, or null when the value will not decrypt: a wrong or
     * rotated platform key, an organization whose key is gone, a value moved
     * under another key, a legacy value with no IV. Only those are caught —
     * nothing else is a property of the value.
     */
    private fun decrypt(orgId: UUID, stored: String, iv: String?, scope: String, key: String): String? =
        try {
            VariableCrypto.decrypt(orgId, stored, iv, scope, key)
        } catch (_: GeneralSecurityException) {
            unreadable(orgId, scope, key)
        } catch (_: IllegalStateException) {
            unreadable(orgId, scope, key)
        } catch (_: IllegalArgumentException) {
            unreadable(orgId, scope, key)
        }

    /** Reports an unreadable variable, at most hourly per variable. Never the value, nor the exception. */
    private fun unreadable(orgId: UUID, scope: String, key: String): String? {
        val now = System.currentTimeMillis()
        val id = "$orgId:$scope:$key"
        val last = lastUnreadableWarning[id]
        if (last == null || now - last >= UNREADABLE_WARN_INTERVAL_MS) {
            lastUnreadableWarning[id] = now
            log.warn(
                "{} variable '{}' of org {} could not be decrypted; runs that use it are skipped. " +
                    "Check PLATFORM_AES_KEY and the organization's encryption key.",
                scope, key, orgId,
            )
        }
        return null
    }

    /** The service's config variables (e.g. `trackBaseline`), injected under their own names. */
    private fun loadConfigVars(serviceId: UUID): Map<String, String> =
        ServiceVariables.selectAll()
            .where {
                (ServiceVariables.serviceId eq serviceId) and
                    (ServiceVariables.deleted eq false) and
                    (ServiceVariables.systemType eq "config")
            }
            .associate { it[ServiceVariables.key] to it[ServiceVariables.value] }
}
