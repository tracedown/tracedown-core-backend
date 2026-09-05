package dev.tracedown.common.variables

import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.variables.SystemVariables
import org.jetbrains.exposed.v1.jdbc.insert
import java.time.Instant
import java.util.UUID

/**
 * Seeds system variables (config type) when a resource is created.
 *
 * Must be called within the same transaction that creates the resource.
 */
object SystemVariableSeeder {

    /** Seeds override variables at org level (config type, platform defaults). */
    fun seedOrg(orgId: UUID, now: Instant = Instant.now()) {
        for (def in SystemVariables.OVERRIDES) {
            OrgVariables.insert {
                it[id] = UUID.randomUUID()
                it[OrgVariables.organizationId] = orgId
                it[createdBy] = null
                it[key] = def.key
                it[value] = def.platformDefault
                it[secret] = false
                it[encrypted] = false
                it[systemType] = "config"
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** Seeds system config variables for a new service. */
    fun seedService(serviceId: UUID, now: Instant = Instant.now()) {
        for (def in SystemVariables.SERVICE) {
            ServiceVariables.insert {
                it[id] = UUID.randomUUID()
                it[ServiceVariables.serviceId] = serviceId
                it[createdBy] = null
                it[key] = def.key
                it[value] = def.defaultValue
                it[secret] = false
                it[encrypted] = false
                it[systemType] = "config"
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** Seeds system config variables for a new workspace. */
    fun seedWorkspace(workspaceId: UUID, now: Instant = Instant.now()) {
        for (def in SystemVariables.WORKSPACE) {
            WorkspaceVariables.insert {
                it[id] = UUID.randomUUID()
                it[WorkspaceVariables.workspaceId] = workspaceId
                it[createdBy] = null
                it[key] = def.key
                it[value] = def.defaultValue
                it[secret] = false
                it[encrypted] = false
                it[systemType] = "config"
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }

    /** Seeds system config variables for a new project. */
    fun seedProject(projectId: UUID, now: Instant = Instant.now()) {
        for (def in SystemVariables.PROJECT) {
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = projectId
                it[createdBy] = null
                it[key] = def.key
                it[value] = def.defaultValue
                it[secret] = false
                it[encrypted] = false
                it[systemType] = "config"
                it[deleted] = false
                it[createdAt] = now
                it[updatedAt] = now
            }
        }
    }
}
