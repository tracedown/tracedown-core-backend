package dev.tracedown.metrics.auth

import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.models.GrafanaIntegrations
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID

/**
 * Resolved integration with parsed config fields.
 */
data class ResolvedIntegration(
    val id: UUID,
    val organizationId: UUID,
    val projectId: UUID,
    val config: JsonObject,
)

/**
 * Authenticates scrape requests against grafana_integrations.
 *
 * Validates the bearer token from the Authorization header against the
 * token hash stored in the integration's config JSONB (`tokenHash`, SHA-256).
 * Rows written before hashing landed carry a plaintext `token` instead and
 * are still accepted; the gateway converts them to a hash on their next
 * update or regeneration.
 */
object IntegrationAuth {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Authenticates a scrape request.
     *
     * @param integrationId the integration UUID from the URL path
     * @param bearerToken the token from the Authorization header
     * @return the resolved integration, or null if auth fails
     */
    suspend fun authenticate(integrationId: UUID, bearerToken: String): ResolvedIntegration? {
        val row = ioTransaction {
            GrafanaIntegrations.selectAll()
                .where {
                    (GrafanaIntegrations.id eq integrationId) and
                        (GrafanaIntegrations.enabled eq true) and
                        (GrafanaIntegrations.deleted eq false)
                }
                .firstOrNull()
        } ?: return null

        val config = row[GrafanaIntegrations.config]
        val storedHash = config["tokenHash"]?.jsonPrimitive?.content
        val legacyToken = config["token"]?.jsonPrimitive?.content

        // Constant-time comparison to prevent timing attacks
        val matches = when {
            storedHash != null -> constantTimeEquals(TokenHasher.sha256Hex(bearerToken), storedHash)
            legacyToken != null -> constantTimeEquals(bearerToken, legacyToken)
            else -> return null
        }
        if (!matches) {
            log.debug("token mismatch for integration {}", integrationId)
            return null
        }

        return ResolvedIntegration(
            id = row[GrafanaIntegrations.id],
            organizationId = row[GrafanaIntegrations.organizationId],
            projectId = row[GrafanaIntegrations.projectId],
            config = config,
        )
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        return MessageDigest.isEqual(a.toByteArray(), b.toByteArray())
    }
}
