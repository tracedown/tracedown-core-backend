package dev.tracedown.gateway.jobs

import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.common.util.VariableCryptoEngine
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.core.notLike
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.concurrent.thread

/**
 * One-time migration of legacy secret-variable ciphertexts to the per-org
 * envelope format.
 *
 * Walks every variable row with `secret = true` whose value is not yet in the
 * versioned envelope format ("v2:" prefix), decrypts it with the legacy
 * platform-key path and re-encrypts it under the owning org's DEK. Runs
 * asynchronously at gateway startup; idempotent and safe to re-run — when
 * everything is already converted the candidate queries return nothing.
 *
 * Each row converts in its own transaction (`SELECT ... FOR UPDATE`, with a
 * re-check that the value is still legacy) so the pass can never overwrite a
 * concurrent user update, and a row that fails to convert is logged and
 * skipped without aborting the rest.
 *
 * Non-secret variables are untouched: the envelope applies to the org-scoped
 * secret tree only.
 */
object SecretReencryption {

    private val log = LoggerFactory.getLogger(SecretReencryption::class.java)

    data class Stats(var converted: Int = 0, var failed: Int = 0)

    /** A scope's variable table and the columns the conversion needs. */
    private class ScopeSpec(
        val scope: String,
        val table: Table,
        val idCol: Column<UUID>,
        val keyCol: Column<String>,
        val valueCol: Column<String>,
        val ivCol: Column<String?>,
        /** Lists (variable id, owning org id) for every legacy secret row. */
        val candidates: () -> List<Pair<UUID, UUID>>,
    )

    private val scopes = listOf(
        ScopeSpec(
            "org", OrgVariables,
            OrgVariables.id, OrgVariables.key, OrgVariables.value, OrgVariables.valueIv,
        ) {
            OrgVariables.selectAll()
                .where { legacySecret(OrgVariables.secret, OrgVariables.value) }
                .map { it[OrgVariables.id] to it[OrgVariables.organizationId] }
        },
        ScopeSpec(
            "workspace", WorkspaceVariables,
            WorkspaceVariables.id, WorkspaceVariables.key, WorkspaceVariables.value, WorkspaceVariables.valueIv,
        ) {
            (WorkspaceVariables innerJoin Workspaces).selectAll()
                .where { legacySecret(WorkspaceVariables.secret, WorkspaceVariables.value) }
                .map { it[WorkspaceVariables.id] to it[Workspaces.organizationId] }
        },
        ScopeSpec(
            "project", ProjectVariables,
            ProjectVariables.id, ProjectVariables.key, ProjectVariables.value, ProjectVariables.valueIv,
        ) {
            (ProjectVariables innerJoin Projects innerJoin Workspaces).selectAll()
                .where { legacySecret(ProjectVariables.secret, ProjectVariables.value) }
                .map { it[ProjectVariables.id] to it[Workspaces.organizationId] }
        },
        ScopeSpec(
            "service", ServiceVariables,
            ServiceVariables.id, ServiceVariables.key, ServiceVariables.value, ServiceVariables.valueIv,
        ) {
            (ServiceVariables innerJoin Services innerJoin Projects innerJoin Workspaces).selectAll()
                .where { legacySecret(ServiceVariables.secret, ServiceVariables.value) }
                .map { it[ServiceVariables.id] to it[Workspaces.organizationId] }
        },
    )

    private fun legacySecret(secretCol: Column<Boolean>, valueCol: Column<String>) =
        (secretCol eq true) and (valueCol notLike "${VariableCryptoEngine.ENVELOPE_PREFIX}%")

    /** Runs the pass on a background thread so startup is never delayed. */
    fun runAsync() {
        thread(name = "secret-reencryption", isDaemon = true) {
            try {
                run()
            } catch (e: Exception) {
                log.error("secret re-encryption pass aborted — it will retry on the next startup", e)
            }
        }
    }

    /** Runs the full pass synchronously. Returns conversion counts. */
    fun run(): Stats {
        val stats = Stats()
        for (spec in scopes) {
            val candidates = transaction { spec.candidates() }
            if (candidates.isEmpty()) continue
            log.info("re-encrypting {} legacy {} secret(s) to the envelope format", candidates.size, spec.scope)
            for ((varId, orgId) in candidates) {
                try {
                    if (convert(spec, varId, orgId)) stats.converted++
                } catch (e: Exception) {
                    stats.failed++
                    log.error(
                        "could not re-encrypt {} secret {} (org {}) — left in legacy format: {}",
                        spec.scope, varId, orgId, e.message,
                    )
                }
            }
        }
        if (stats.converted > 0 || stats.failed > 0) {
            log.info("secret re-encryption pass done: {} converted, {} failed", stats.converted, stats.failed)
        }
        return stats
    }

    /**
     * Converts one row in its own transaction. Locks the row and re-checks it
     * is still a legacy ciphertext, so a concurrent update through the normal
     * write path (already envelope-format) is never clobbered.
     */
    private fun convert(spec: ScopeSpec, varId: UUID, orgId: UUID): Boolean = transaction {
        val row = spec.table.selectAll()
            .where { spec.idCol eq varId }
            .forUpdate()
            .firstOrNull() ?: return@transaction false

        val stored = row[spec.valueCol]
        if (stored.startsWith(VariableCryptoEngine.ENVELOPE_PREFIX)) return@transaction false
        val iv = row[spec.ivCol]
            ?: throw IllegalStateException("legacy secret has no IV — cannot decrypt")

        val plaintext = VariableCrypto.decrypt(stored, iv)
        val envelope = VariableCrypto.encrypt(orgId, plaintext, spec.scope, row[spec.keyCol])

        spec.table.update({ spec.idCol eq varId }) {
            it[spec.valueCol] = envelope
            it[spec.ivCol] = null
        }
        true
    }
}
