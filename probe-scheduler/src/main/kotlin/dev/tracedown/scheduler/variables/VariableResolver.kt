package dev.tracedown.scheduler.variables

import dev.tracedown.common.models.*
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.common.variables.SystemVariables
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Resolves explicitly scoped variables and rewrites the script for Lace compatibility.
 *
 * Users write `$s.varName`, `$p.varName`, `$w.varName`, `$o.varName` in scripts.
 * Since Lace treats dots as property access, the resolver:
 * 1. Parses the script for `$X.varName` references
 * 2. Looks up each variable in the correct scope (s=service, p=project, w=workspace, o=org)
 * 3. Rewrites the script: `$s.varName` → `$s_varName` (valid Lace IDENT)
 * 4. Returns the rewritten script + resolved variables with underscore keys
 *
 * Only explicitly scoped references are resolved — there is no fallback chain
 * for unscoped `$varName`, which stays untouched and interpolates to null.
 * Computed system variables (`s_name`, `s_lastStatus`, etc.) are always injected.
 */
object VariableResolver {

    /** Matches scoped variable references: $s.key, $p.key, $w.key, $o.key */
    private val SCOPED_VAR_RE = Regex("""\$([spwo])\.([a-zA-Z_][a-zA-Z0-9_]*)""")

    data class ResolveResult(
        val script: String,
        val variables: JsonObject,
        /** Decrypted plaintext values of the SECRET variables used in this run —
         *  redacted out of the ProbeResult before it is published/persisted, so a
         *  secret never surfaces in a result even if a script puts it in a URL/header. */
        val secretValues: Set<String> = emptySet(),
    )

    /** Resolves variables for a service, rewriting the script for Lace compatibility. */
    fun resolve(serviceId: UUID, script: String): ResolveResult {
        return transaction {
            val service = Services.selectAll()
                .where { Services.id eq serviceId }
                .firstOrNull() ?: return@transaction ResolveResult(script, buildJsonObject {})

            val projectId = service[Services.projectId]
            val project = Projects.selectAll()
                .where { Projects.id eq projectId }
                .firstOrNull() ?: return@transaction ResolveResult(script, buildJsonObject {})

            val workspaceId = project[Projects.workspaceId]
            val workspace = Workspaces.selectAll()
                .where { Workspaces.id eq workspaceId }
                .firstOrNull() ?: return@transaction ResolveResult(script, buildJsonObject {})

            val orgId = workspace[Workspaces.organizationId]

            // Load all variable scopes
            val orgVars = loadScope(OrgVariables.organizationId, orgId, OrgVariables, orgId, "org")
            val wsVars = loadScope(WorkspaceVariables.workspaceId, workspaceId, WorkspaceVariables, orgId, "workspace")
            val projVars = loadScope(ProjectVariables.projectId, projectId, ProjectVariables, orgId, "project")
            val svcVars = loadScope(ServiceVariables.serviceId, serviceId, ServiceVariables, orgId, "service")
            val svcConfigVars = loadConfigVars(serviceId)

            val resolved = mutableMapOf<String, String>()
            val secretValues = mutableSetOf<String>()

            // Resolve scoped references ($s.key, $p.key, $w.key, $o.key)
            for (match in SCOPED_VAR_RE.findAll(script)) {
                val scope = match.groupValues[1]
                val key = match.groupValues[2]
                val entry = when (scope) {
                    "s" -> svcVars[key]
                    "p" -> projVars[key]
                    "w" -> wsVars[key]
                    "o" -> orgVars[key]
                    else -> null
                }
                if (entry != null) {
                    resolved["${scope}_$key"] = entry.value
                    // A secret's plaintext must never surface in the ProbeResult (a script
                    // may place it in a URL/header, which the executor echoes back). Collect
                    // its value so the dispatcher can redact it before publishing.
                    if (entry.secret && entry.value.isNotBlank()) secretValues.add(entry.value)
                }
            }

            // Rewrite script: $s.varName → $s_varName
            val rewritten = SCOPED_VAR_RE.replace(script) { m ->
                "\$${m.groupValues[1]}_${m.groupValues[2]}"
            }


            // Build final variable map
            val variables = buildJsonObject {
                for ((key, value) in resolved) {
                    put(key, value)
                }

                // System config variables (always passed — used by extensions, not scripts)
                for ((key, value) in svcConfigVars) {
                    put(key, value)
                }

                // Computed system variables (always injected)
                put("s_name", service[Services.name])
                put("s_lastStatus", service[Services.lastStatus] ?: "")
                put("s_lastStatusSince", service[Services.lastStatusSince]?.toString() ?: "")
                put("s_lastStatusConsecutive", service[Services.lastStatusConsecutive].toString())
                put("p_name", project[Projects.name])
                put("w_name", workspace[Workspaces.name])
            }

            ResolveResult(rewritten, variables, secretValues)
        }
    }

    /** A resolved variable: its decrypted plaintext value and whether it is a secret. */
    private data class VarEntry(val value: String, val secret: Boolean)

    /**
     * Loads all non-deleted variables for a scope into a key→entry map (value +
     * secret flag). [orgId] and [scope] identify the encryption context:
     * secrets are envelope-encrypted with the org DEK, everything else with
     * the platform key — [VariableCrypto.decrypt] dispatches on the stored format.
     */
    private fun loadScope(
        scopeColumn: org.jetbrains.exposed.v1.core.Column<UUID>,
        scopeId: UUID,
        table: org.jetbrains.exposed.v1.core.Table,
        orgId: UUID,
        scope: String,
    ): Map<String, VarEntry> {
        val result = mutableMapOf<String, VarEntry>()
        @Suppress("UNCHECKED_CAST")
        val keyCol = table.columns.first { it.name == "key" } as org.jetbrains.exposed.v1.core.Column<String>
        @Suppress("UNCHECKED_CAST")
        val valueCol = table.columns.first { it.name == "value" } as org.jetbrains.exposed.v1.core.Column<String>
        @Suppress("UNCHECKED_CAST")
        val ivCol = table.columns.first { it.name == "value_iv" } as org.jetbrains.exposed.v1.core.Column<String?>
        @Suppress("UNCHECKED_CAST")
        val encCol = table.columns.first { it.name == "encrypted" } as org.jetbrains.exposed.v1.core.Column<Boolean>
        @Suppress("UNCHECKED_CAST")
        val secretCol = table.columns.first { it.name == "secret" } as org.jetbrains.exposed.v1.core.Column<Boolean>
        @Suppress("UNCHECKED_CAST")
        val deletedCol = table.columns.first { it.name == "deleted" } as org.jetbrains.exposed.v1.core.Column<Boolean>

        table.selectAll()
            .where { (scopeColumn eq scopeId) and (deletedCol eq false) }
            .forEach { row ->
                val k = row[keyCol]
                val v = row[valueCol]
                val iv = row[ivCol]
                val enc = row[encCol]
                val plain = if (enc) VariableCrypto.decrypt(orgId, v, iv, scope, k) else v
                result[k] = VarEntry(plain, row[secretCol])
            }
        return result
    }

    /** Loads service-level config system variables (e.g. silentOnRepeat, trackBaseline). */
    private fun loadConfigVars(serviceId: UUID): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ServiceVariables.selectAll()
            .where {
                (ServiceVariables.serviceId eq serviceId) and
                (ServiceVariables.deleted eq false) and
                (ServiceVariables.systemType eq "config")
            }
            .forEach { row ->
                result[row[ServiceVariables.key]] = row[ServiceVariables.value]
            }
        return result
    }
}
