package dev.tracedown.gateway.controllers.integrations

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.auth.canAccessResource
import dev.tracedown.common.auth.canWriteResource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.GrafanaIntegrations
import dev.tracedown.common.models.Services
import dev.tracedown.gateway.data.integrations.CreateGrafanaIntegrationRequest
import dev.tracedown.gateway.data.integrations.GrafanaIntegrationState
import dev.tracedown.gateway.data.integrations.GrafanaIntegrationSummary
import dev.tracedown.gateway.data.integrations.ScopeConfig
import dev.tracedown.gateway.data.integrations.UpdateGrafanaIntegrationRequest
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.ResourceResolver
import dev.tracedown.gateway.util.requireCachedPermissions
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Per-project Grafana integration: one active integration per project,
 * carrying the scrape bearer token, service scope, and optional custom
 * labels. Gated by project write access (read access for viewing).
 */
object GrafanaIntegrationController {

    private val secureRandom = SecureRandom()

    /** Base URL the metrics service is reachable at, if advertised ("" = not set). */
    private var metricsPublicUrl: String = ""

    fun init(metricsPublicUrl: String) {
        this.metricsPublicUrl = metricsPublicUrl.trimEnd('/')
    }

    /** Returns the project's integration, or `integration: null` when unset. */
    fun getForProject(orgId: UUID, projectId: UUID, userId: UUID): GrafanaIntegrationState {
        return transaction {
            requireProjectAccess(orgId, projectId, userId, write = false)
            val row = findRow(projectId)
            GrafanaIntegrationState(row?.let { summaryFromRow(it) })
        }
    }

    /** Creates the project's integration. Returns the generated bearer token. */
    fun create(orgId: UUID, projectId: UUID, request: CreateGrafanaIntegrationRequest, userId: UUID): GrafanaIntegrationSummary {
        validateName(request.name)

        return transaction {
            requireProjectAccess(orgId, projectId, userId, write = true)
            if (findRow(projectId) != null) throw ConflictException()

            val scope = validateScope(projectId, request.scope)
            val id = UUID.randomUUID()
            val token = generateToken()

            GrafanaIntegrations.insert {
                it[GrafanaIntegrations.id] = id
                it[organizationId] = orgId
                it[GrafanaIntegrations.projectId] = projectId
                it[name] = request.name
                it[config] = buildConfig(TokenHasher.sha256Hex(token), scope, request.labels)
                it[enabled] = request.enabled
                it[deleted] = false
                it[createdAt] = Instant.now()
            }

            AuditService.log(
                orgId, userId, "create.grafana-integration", "grafana-integration", id.toString(),
                entityDisplayName = request.name,
                comment = "project $projectId",
            )

            // Return token only on create — only the hash is stored
            summaryFromRow(findRow(projectId)!!, plainToken = token)
        }
    }

    /** Updates the project's integration. */
    fun update(orgId: UUID, projectId: UUID, request: UpdateGrafanaIntegrationRequest, userId: UUID): GrafanaIntegrationSummary {
        request.name?.let { validateName(it) }

        return transaction {
            requireProjectAccess(orgId, projectId, userId, write = true)
            val existing = findRow(projectId) ?: throw NotFoundException()
            val existingConfig = existing[GrafanaIntegrations.config]
            val oldScope = parseScope(existingConfig["scope"]?.jsonObject)

            val newScope = request.scope?.let { validateScope(projectId, it) } ?: oldScope
            val newLabels = request.labels ?: parseLabels(existingConfig["labels"]?.jsonObject)
            // Carry the stored hash forward; a legacy plaintext token is
            // converted to its hash here (the issued token keeps working —
            // scrape auth compares hashes).
            val existingTokenHash = existingConfig["tokenHash"]?.jsonPrimitive?.content
                ?: existingConfig["token"]?.jsonPrimitive?.content?.let { TokenHasher.sha256Hex(it) }
                ?: TokenHasher.sha256Hex(generateToken())

            GrafanaIntegrations.update({ GrafanaIntegrations.id eq existing[GrafanaIntegrations.id] }) {
                request.name?.let { v -> it[name] = v }
                request.enabled?.let { v -> it[enabled] = v }
                it[config] = buildConfig(existingTokenHash, newScope, newLabels)
            }

            AuditService.log(
                orgId, userId, "update.grafana-integration", "grafana-integration",
                existing[GrafanaIntegrations.id].toString(),
                entityDisplayName = existing[GrafanaIntegrations.name],
                diff = auditDiff(
                    Triple("name", existing[GrafanaIntegrations.name], request.name ?: existing[GrafanaIntegrations.name]),
                    Triple("enabled", existing[GrafanaIntegrations.enabled], request.enabled ?: existing[GrafanaIntegrations.enabled]),
                    Triple("scope", scopeLabel(oldScope), scopeLabel(newScope)),
                ),
                comment = "project $projectId",
            )

            summaryFromRow(findRow(projectId)!!)
        }
    }

    /** Regenerates the bearer token. Returns the new token. */
    fun regenerateToken(orgId: UUID, projectId: UUID, userId: UUID): GrafanaIntegrationSummary {
        return transaction {
            requireProjectAccess(orgId, projectId, userId, write = true)
            val existing = findRow(projectId) ?: throw NotFoundException()
            val existingConfig = existing[GrafanaIntegrations.config]

            val newToken = generateToken()
            GrafanaIntegrations.update({ GrafanaIntegrations.id eq existing[GrafanaIntegrations.id] }) {
                it[config] = buildConfig(
                    TokenHasher.sha256Hex(newToken),
                    parseScope(existingConfig["scope"]?.jsonObject),
                    parseLabels(existingConfig["labels"]?.jsonObject),
                )
            }

            AuditService.log(
                orgId, userId, "regenerate-token.grafana-integration", "grafana-integration",
                existing[GrafanaIntegrations.id].toString(),
                entityDisplayName = existing[GrafanaIntegrations.name],
                comment = "project $projectId",
            )

            summaryFromRow(findRow(projectId)!!, plainToken = newToken)
        }
    }

    /** Soft-deletes the project's integration. */
    fun delete(orgId: UUID, projectId: UUID, userId: UUID) {
        transaction {
            requireProjectAccess(orgId, projectId, userId, write = true)
            val existing = findRow(projectId) ?: throw NotFoundException()

            GrafanaIntegrations.update({ GrafanaIntegrations.id eq existing[GrafanaIntegrations.id] }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(
                orgId, userId, "delete.grafana-integration", "grafana-integration",
                existing[GrafanaIntegrations.id].toString(),
                entityDisplayName = existing[GrafanaIntegrations.name],
                comment = "project $projectId",
            )
        }
    }

    // ── Internals ──

    private fun requireProjectAccess(orgId: UUID, projectId: UUID, userId: UUID, write: Boolean) {
        val ctx = ResourceResolver.resolveProject(projectId, orgId)
        val cached: CachedPermissions = requireCachedPermissions(orgId, userId)
        val parentChain = listOf("workspace::${ctx.workspaceId}")
        val allowed = if (write) {
            canWriteResource(cached, "project", projectId, parentChain)
        } else {
            canAccessResource(cached, "project", projectId, parentChain)
        }
        if (!allowed) throw NotFoundException()
    }

    private fun findRow(projectId: UUID): ResultRow? {
        return GrafanaIntegrations.selectAll()
            .where {
                (GrafanaIntegrations.projectId eq projectId) and
                    (GrafanaIntegrations.deleted eq false)
            }
            .firstOrNull()
    }

    private fun validateName(name: String) {
        if (name.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (name.length > 64) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
    }

    /** Normalizes scope: "services" with ids that must belong to the project; anything else = all. */
    private fun validateScope(projectId: UUID, scope: ScopeConfig?): ScopeConfig? {
        if (scope == null || scope.type != "services" || scope.ids.isNullOrEmpty()) return null
        val ids = scope.ids.map {
            try {
                UUID.fromString(it)
            } catch (_: IllegalArgumentException) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
        }
        val known = Services.selectAll()
            .where {
                (Services.projectId eq projectId) and
                    (Services.id inList ids) and
                    (Services.deleted eq false)
            }
            .map { it[Services.id] }
            .toSet()
        if (known.size != ids.toSet().size) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        return ScopeConfig(type = "services", ids = ids.map { it.toString() })
    }

    private fun scopeLabel(scope: ScopeConfig?): String =
        if (scope?.type == "services") "services (${scope.ids?.size ?: 0})" else "all"

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /** The config stores only the token's SHA-256 — a DB read never yields a live scrape token. */
    private fun buildConfig(tokenHash: String, scope: ScopeConfig?, labels: Map<String, String>?): JsonObject {
        return buildJsonObject {
            put("tokenHash", tokenHash)
            if (scope != null) {
                put("scope", buildJsonObject {
                    put("type", scope.type)
                    scope.ids?.let { ids ->
                        put("ids", JsonArray(ids.map { JsonPrimitive(it) }))
                    }
                })
            }
            if (!labels.isNullOrEmpty()) {
                put("labels", buildJsonObject {
                    labels.forEach { (k, v) -> put(k, v) }
                })
            }
        }
    }

    private fun parseScope(obj: JsonObject?): ScopeConfig? {
        if (obj == null) return null
        return ScopeConfig(
            type = obj["type"]?.jsonPrimitive?.content ?: "all",
            ids = obj["ids"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
    }

    private fun parseLabels(obj: JsonObject?): Map<String, String>? {
        if (obj == null) return null
        return obj.mapValues { (_, v) -> v.jsonPrimitive.content }
    }

    /** [plainToken] is passed only from create/regenerate — the stored config holds just the hash. */
    private fun summaryFromRow(row: ResultRow, plainToken: String? = null): GrafanaIntegrationSummary {
        val config = row[GrafanaIntegrations.config]
        val scrapePath = "/metrics/${row[GrafanaIntegrations.id]}"
        return GrafanaIntegrationSummary(
            id = row[GrafanaIntegrations.id].toString(),
            projectId = row[GrafanaIntegrations.projectId].toString(),
            name = row[GrafanaIntegrations.name],
            token = plainToken,
            scope = parseScope(config["scope"]?.jsonObject),
            labels = parseLabels(config["labels"]?.jsonObject),
            enabled = row[GrafanaIntegrations.enabled],
            createdAt = row[GrafanaIntegrations.createdAt].toString(),
            scrapePath = scrapePath,
            scrapeUrl = metricsPublicUrl.takeIf { it.isNotBlank() }?.let { "$it$scrapePath" },
        )
    }
}
