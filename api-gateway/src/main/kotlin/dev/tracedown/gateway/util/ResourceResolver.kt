package dev.tracedown.gateway.util

import dev.tracedown.common.cache.CachedProjectContext
import dev.tracedown.common.cache.CachedServiceContext
import dev.tracedown.common.cache.ResourceCache
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.util.UUID

/**
 * Resolved resource hierarchy for a service.
 */
data class ServiceContext(
    val serviceId: UUID,
    val projectId: UUID,
    val workspaceId: UUID,
)

/**
 * Resolved resource hierarchy for a project.
 */
data class ProjectContext(
    val projectId: UUID,
    val workspaceId: UUID,
)

/**
 * Resolves the parent hierarchy for resources.
 *
 * Uses Redis C cache when available (cache-first, DB-fallback).
 * When Redis C is not configured, falls through to DB queries directly.
 * All methods throw [NotFoundException] if the resource doesn't exist or is deleted.
 * Must be called inside a transaction.
 */
object ResourceResolver {

    private var cache: ResourceCache = ResourceCache.DISABLED

    /** Initializes the resolver with a Redis C cache. Call once at startup. */
    fun init(cache: ResourceCache) {
        this.cache = cache
    }

    /** Resolves a service → its project → its workspace. Verifies org ownership. */
    fun resolveService(serviceId: UUID, orgId: UUID): ServiceContext {
        // Try cache first
        val cached = cache.getService(serviceId)
        if (cached != null && cached.organizationId == orgId.toString()) {
            return ServiceContext(
                serviceId = UUID.fromString(cached.serviceId),
                projectId = UUID.fromString(cached.projectId),
                workspaceId = UUID.fromString(cached.workspaceId),
            )
        }

        // DB fallback. Self-wrapped: cache hits need no transaction, so call
        // sites may legitimately run outside one — the miss path must not
        // depend on that (nested transactions join the outer one).
        val ctx = transaction {
            val service = Services.selectAll()
                .where { (Services.id eq serviceId) and (Services.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            val project = Projects.selectAll()
                .where { (Projects.id eq service[Services.projectId]) and (Projects.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            val workspace = Workspaces.selectAll()
                .where {
                    (Workspaces.id eq project[Projects.workspaceId]) and
                        (Workspaces.organizationId eq orgId) and
                        (Workspaces.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            ServiceContext(
                serviceId = serviceId,
                projectId = project[Projects.id],
                workspaceId = workspace[Workspaces.id],
            )
        }

        // Populate cache
        cache.putService(CachedServiceContext(
            serviceId = ctx.serviceId.toString(),
            projectId = ctx.projectId.toString(),
            workspaceId = ctx.workspaceId.toString(),
            organizationId = orgId.toString(),
        ))

        return ctx
    }

    /** Resolves a project → its workspace. Verifies org ownership. */
    fun resolveProject(projectId: UUID, orgId: UUID): ProjectContext {
        // Try cache first
        val cached = cache.getProject(projectId)
        if (cached != null && cached.organizationId == orgId.toString()) {
            return ProjectContext(
                projectId = UUID.fromString(cached.projectId),
                workspaceId = UUID.fromString(cached.workspaceId),
            )
        }

        // DB fallback (self-wrapped — see resolveService)
        val ctx = transaction {
            val project = Projects.selectAll()
                .where { (Projects.id eq projectId) and (Projects.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()

            val workspace = Workspaces.selectAll()
                .where {
                    (Workspaces.id eq project[Projects.workspaceId]) and
                        (Workspaces.organizationId eq orgId) and
                        (Workspaces.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            ProjectContext(
                projectId = projectId,
                workspaceId = workspace[Workspaces.id],
            )
        }

        // Populate cache
        cache.putProject(CachedProjectContext(
            projectId = ctx.projectId.toString(),
            workspaceId = ctx.workspaceId.toString(),
            organizationId = orgId.toString(),
        ))

        return ctx
    }

    /** Verifies a workspace belongs to the org. No caching needed (single query). */
    fun resolveWorkspace(workspaceId: UUID, orgId: UUID) {
        val exists = transaction {
            Workspaces.selectAll()
                .where {
                    (Workspaces.id eq workspaceId) and
                        (Workspaces.organizationId eq orgId) and
                        (Workspaces.deleted eq false)
                }
                .any()
        }
        if (!exists) throw NotFoundException()
    }

    // ── Cache invalidation ──

    /** Invalidates cached hierarchy for a service. Call after service update/delete. */
    fun invalidateService(serviceId: UUID) {
        cache.invalidateService(serviceId)
    }

    /** Invalidates cached hierarchy for a project. Call after project update/delete. */
    fun invalidateProject(projectId: UUID) {
        cache.invalidateProject(projectId)
    }
}
