package dev.tracedown.gateway.util

import dev.tracedown.common.config.DeletionRetention
import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
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
 *  - **projects** and **workspaces** — containers in their own right.
 *  - **grafana integrations** — a scrape credential that keeps authenticating
 *    on its own `deleted` flag alone, without consulting its project.
 *  - **memberships** (organization delete only) — an org_users row is what
 *    grants access; it has to go with the organization it grants access to.
 *
 * Variables, notification silences, resource grants and webhook bindings are
 * deliberately left alone at every level, services included. They are inert
 * once the services above them are stopped and the container is unreachable,
 * and the purge job reaches them by join when the container is erased, so
 * flipping them would change nothing except a restore's ability to tell them
 * apart from rows the user had deleted earlier. That is also why a service
 * delete has no entry point here: everything hanging off a service — its
 * variables, its agent allow-list, its silences, its results and aggregates —
 * is of exactly that inert kind, so the same rule applied one level lower
 * carries nothing down.
 *
 * Every row a single cascade touches is stamped with the *same* instant and the
 * *same* purge date ([DeletionRetention.purgeAfter] of that instant), which is
 * what lets a restore put back exactly what the delete took and nothing else —
 * and what makes the operator's retention mean the same thing for a service as
 * for the organization above it.
 *
 * A descendant the user had already deleted keeps its own, earlier stamp: each
 * step below selects only rows that are still live.
 */
object DeletionCascade {

    /** What one cascade carried down, for the caller to nudge and invalidate. */
    data class Cascaded(
        val workspaceIds: List<UUID> = emptyList(),
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
        softDeleteProjects(liveProjectIds, now)
        softDeleteIntegrationsOf(projects.map { it.first }, now)

        return Cascaded(projectIds = liveProjectIds, serviceIds = serviceIds)
    }

    /**
     * Soft-deletes everything under an organization. Call inside the transaction
     * that deletes the organization itself, with its own deletion instant.
     *
     * The organization delete used to stop the org's services and nothing else:
     * its workspaces and projects kept `deleted = false`, so they read as live
     * containers of an organization that was gone, and — carrying no purge date
     * of their own — they were erased only as a side effect of the organization
     * row finally purging. The subtree now goes down the way it does one level
     * lower, with the same instant and the same purge date throughout.
     *
     * Memberships go too: an `org_users` row is the grant, and it cannot outlive
     * what it grants access to. The caller still reconciles each affected
     * account afterwards — losing a membership is what schedules an account with
     * no remaining organization for deletion.
     */
    fun organization(orgId: UUID, now: Instant): Cascaded {
        val workspaceIds = Workspaces.selectAll()
            .where { (Workspaces.organizationId eq orgId) and (Workspaces.deleted eq false) }
            .map { it[Workspaces.id] }

        val allProjects = (Projects innerJoin Workspaces).selectAll()
            .where { Workspaces.organizationId eq orgId }
            .map { it[Projects.id] to it[Projects.deleted] }

        val serviceIds = (Services innerJoin Projects innerJoin Workspaces).selectAll()
            .where { (Workspaces.organizationId eq orgId) and (Services.deleted eq false) }
            .map { it[Services.id] }

        softDeleteServices(serviceIds, now)
        softDeleteProjects(allProjects.filter { !it.second }.map { it.first }, now)
        softDeleteIntegrationsOf(allProjects.map { it.first }, now)

        if (workspaceIds.isNotEmpty()) {
            Workspaces.update({ Workspaces.id inList workspaceIds }) {
                it[deleted] = true
                it[deletedAt] = now
                it[purgeAfter] = DeletionRetention.purgeAfter(now)
            }
        }

        OrgUsers.update({ (OrgUsers.organizationId eq orgId) and (OrgUsers.deleted eq false) }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = DeletionRetention.purgeAfter(now)
        }

        return Cascaded(
            workspaceIds = workspaceIds,
            projectIds = allProjects.filter { !it.second }.map { it.first },
            serviceIds = serviceIds,
        )
    }

    private fun softDeleteServices(serviceIds: List<UUID>, now: Instant) {
        if (serviceIds.isEmpty()) return
        Services.update({ Services.id inList serviceIds }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = DeletionRetention.purgeAfter(now)
        }
    }

    private fun softDeleteProjects(projectIds: List<UUID>, now: Instant) {
        if (projectIds.isEmpty()) return
        Projects.update({ Projects.id inList projectIds }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = DeletionRetention.purgeAfter(now)
        }
    }

    private fun softDeleteIntegrationsOf(projectIds: List<UUID>, now: Instant) {
        if (projectIds.isEmpty()) return
        GrafanaIntegrations.update({
            (GrafanaIntegrations.projectId inList projectIds) and (GrafanaIntegrations.deleted eq false)
        }) {
            it[deleted] = true
            it[deletedAt] = now
            it[purgeAfter] = DeletionRetention.purgeAfter(now)
        }
    }
}
