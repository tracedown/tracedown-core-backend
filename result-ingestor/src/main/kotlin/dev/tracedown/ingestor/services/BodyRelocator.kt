package dev.tracedown.ingestor.services

import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStoreRegistry
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * Takes ownership of the storage location of an agent-uploaded response body.
 *
 * The probe agent uploads bodies to a location it chooses and returns that URI
 * in the ProbeResult. That URI must NEVER be persisted verbatim — a shared agent
 * would collide keys across tenants (cross-tenant body disclosure), and a
 * compromised agent could hand back an arbitrary `file://`/`s3://` path pointing
 * at platform secrets or another tenant's data.
 *
 * Instead, the ingestor derives a server-side, tenant-scoped, collision-free key
 * — `{orgId}/{serviceId}/{resultId}/call_{n}_response{ext}` — and relocates the
 * bytes there via a confined [BodyStorageClient]. The agent's path is used only
 * as a relocation SOURCE (confinement-checked, so an escape path is rejected) and
 * only its filename extension is treated as advisory. The persisted URI is always
 * the server-derived one.
 */
class BodyRelocator(private val storage: BodyStorageClient) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Relocates the body at [agentBodyPath] to the canonical key for this
     * (org, service, result, call), returning the server-derived storage URI to
     * persist, or null if relocation was not possible (the body is then recorded
     * without a storage URL rather than trusting the agent path).
     */
    fun relocate(
        agentBodyPath: String,
        organizationId: UUID,
        serviceId: UUID,
        resultId: UUID,
        callIndex: Int,
    ): String? {
        val destKey = "$organizationId/$serviceId/$resultId/call_${callIndex}_response${extensionOf(agentBodyPath)}"
        return try {
            storage.relocate(agentBodyPath, destKey)
        } catch (e: Exception) {
            // Confinement violations (escape paths, foreign buckets), missing
            // source bytes, or an unreachable backend all land here. Never fall
            // back to persisting the agent path — drop the body reference.
            log.warn("body relocation failed for service {} result {}: {}", serviceId, resultId, e.message)
            null
        }
    }

    /**
     * Imports the body at [agentBodyPath] from the agent's own `import` body
     * store — read through [source], which is confined to that store — into the
     * canonical key in this relocator's (default) store, removing it from the
     * source. Same contract as [relocate]: the server-derived URI, or null.
     */
    fun importFrom(
        source: BodyStorageClient,
        agentBodyPath: String,
        organizationId: UUID,
        serviceId: UUID,
        resultId: UUID,
        callIndex: Int,
    ): String? {
        val destKey = "$organizationId/$serviceId/$resultId/call_${callIndex}_response${extensionOf(agentBodyPath)}"
        return try {
            storage.relocateFrom(source, agentBodyPath, destKey, BodyStoreRegistry.MAX_BODY_BYTES)
        } catch (e: Exception) {
            log.warn("body import failed for service {} result {}: {}", serviceId, resultId, e.message)
            null
        }
    }

    /** Advisory extension (e.g. ".json") from the agent's filename, sanitized. */
    private fun extensionOf(path: String): String {
        val leaf = path.substringAfterLast('/').substringAfterLast('\\')
        val dot = leaf.lastIndexOf('.')
        if (dot <= 0 || dot == leaf.length - 1) return ""
        val ext = leaf.substring(dot + 1)
        // Only keep a short, plain extension; anything else is dropped.
        return if (ext.length <= 12 && ext.all { it.isLetterOrDigit() }) ".$ext" else ""
    }
}
