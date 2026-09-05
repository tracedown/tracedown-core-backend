package dev.tracedown.metrics.scrape

import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Workspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.experimental.newSuspendedTransaction
import java.util.UUID

/**
 * Service info with labels for Prometheus output.
 */
data class ServiceInfo(
    val id: UUID,
    val name: String,
    val projectName: String,
    val workspaceName: String,
)

/**
 * Resolves the scope config from a grafana_integration into a list of
 * service IDs with their label metadata.
 */
object ScopeResolver {

    /**
     * Resolves scope to a list of non-deleted services in the integration's
     * project, optionally narrowed to specific service IDs.
     *
     * @param projectId the project the integration belongs to
     * @param scopeConfig the "scope" object from integration config
     * @return list of services in scope
     */
    suspend fun resolve(projectId: UUID, scopeConfig: JsonObject?): List<ServiceInfo> {
        val type = scopeConfig?.get("type")?.jsonPrimitive?.content ?: "all"
        val ids = scopeConfig?.get("ids")?.jsonArray?.map {
            UUID.fromString(it.jsonPrimitive.content)
        } ?: emptyList()

        return newSuspendedTransaction(Dispatchers.IO) {
            Services
                .innerJoin(Projects)
                .innerJoin(Workspaces)
                .selectAll()
                .where {
                    val base = (Projects.id eq projectId) and
                        (Services.deleted eq false) and
                        (Projects.deleted eq false) and
                        (Workspaces.deleted eq false)

                    if (type == "services" && ids.isNotEmpty()) {
                        base and (Services.id inList ids)
                    } else {
                        base
                    }
                }
                .map { row ->
                    ServiceInfo(
                        id = row[Services.id],
                        name = row[Services.name],
                        projectName = row[Projects.name],
                        workspaceName = row[Workspaces.name],
                    )
                }
        }
    }
}
