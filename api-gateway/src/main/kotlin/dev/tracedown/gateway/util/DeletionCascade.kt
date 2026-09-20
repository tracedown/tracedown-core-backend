package dev.tracedown.gateway.util

import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.innerJoin
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.util.UUID

/**
 * Carries a deleted container's descendants down with it.
 *
 * Deleting a project or a workspace used to flip that one row only. The
 * services underneath kept `deleted = false`, so the scheduler — which selects
 * on `services` alone — kept firing them, kept writing results and kept sending
 * notifications for a project nobody could see any more. Every container delete
 * now runs its subtree through here, inside the caller's transaction, so the
 * container and everything it owns stop together or not at all.
 *
 * Only descendants that *do* something on their own are carried down:
 *
 *  - **services** — the probes themselves.
 *  - **projects** (workspace delete only) — containers in their own right.
 *  - **grafana integrations** — a scrape credential that keeps authenticating
 *    on its own `deleted` flag alone, without consulting its project.
 *
 * Variables, notification silences, resource grants and webhook bindings are
 * deliberately left alone. They are inert once the services above them are
 * stopped and the container is unreachable, and the purge job reaches them by
 * join when the container is erased, so flipping them would change nothing
 * except a restore's ability to tell them apart from rows the user had deleted
 * earlier.
 *
 * Every row a single cascade touches is stamped with the *same* instant, which
 * is what lets a restore put back exactly what the delete took and nothing else.
 */
object DeletionCascade {

    /** What one cascade carried down, for the caller to nudge and invalidate. */
    data class Cascaded(
        val projectIds: List<UUID> = emptyList(),
        val serviceIds: List<UUID> = emptyList(),
    )

    /**
     * Soft-deletes everything under a project. Call inside the transaction that
     * deletes the project itself, with the project's own deletion instant.
     */
    fun project(projectId: UUID, now: Instant): Cascaded {
        val serviceIds = Services.selectAll()
            .where { (Services.projectId eq projectId) and (Services.deleted eq false) }
            .map { it[Services.id] }

        softDeleteServices(serviceIds, now)
        softDeleteIntegrationsOf(listOf(projectId), now)

        return Cascaded(serviceIds = serviceIds)
    }

    /**
     * Soft-deletes everything under a workspace. Call inside the transaction
     * that deletes the workspace itself, with the workspace's own deletion
     * instant.
     *
     * Services are collected by joining through `projects` rather than from the
     * live project ids flipped below, so a service still running under a project
     * that was already deleted — the exact residue this bug left behind — is
     * stopped too.
     */
    fun workspace(workspaceId: UUID, now: Instant): Cascaded {
        val projects = Projects.selectAll()
            .where { Projects.workspaceId eq workspaceId }
            .map { it[Projects.id] to it[Projects.deleted] }

        val liveProjectIds = projects.filter { !it.second }.map { it.first }

        val serviceIds = (Services innerJoin Projects).selectAll()
            .where { (Projects.workspaceId eq workspaceId) and (Services.deleted eq false) }
            .map { it[Services.id] }

        softDeleteServices(serviceIds, now)

        if (liveProjectIds.isNotEmpty()) {
            Projects.update({ Projects.id inList liveProjectIds }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = now
            }
        }

        softDeleteIntegrationsOf(projects.map { it.first }, now)

        return Cascaded(projectIds = liveProjectIds, serviceIds = serviceIds)
    }

    /**
     * Stamps `purge_after` alongside the soft-delete, as Core does wherever it
     * deletes a row the user asked to be gone: Core keeps no retention window,
     * so a deleted row is purgeable as soon as it is deleted. A host that wants
     * a grace period pushes the column out afterwards.
     */
    private fun softDeleteServices(serviceIds: List<UUID>, now: Instant) {
        if (serviceIds.isEmpty()) return
        Services.update({ Services.id inList serviceIds }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = now
        }
    }

    private fun softDeleteIntegrationsOf(projectIds: List<UUID>, now: Instant) {
        if (projectIds.isEmpty()) return
        GrafanaIntegrations.update({
            (GrafanaIntegrations.projectId inList projectIds) and (GrafanaIntegrations.deleted eq false)
        }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = now
        }
    }
}
