package dev.tracedown.gateway.routes.v1.agents

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.agents.AgentVisibility
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.gateway.controllers.agents.CaService
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.AgentHealthChecks
import dev.tracedown.common.models.ServiceAllowedAgents
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.agents.AgentEnrolmentAddress
import dev.tracedown.common.agents.FleetAudience
import dev.tracedown.common.validation.Validatable
import dev.tracedown.common.validation.Validators
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tracedown.gateway.routes.v1.auth.requireAuthWithOrg
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import dev.tracedown.gateway.util.tryReceive
import io.ktor.resources.Resource
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.patch
import io.ktor.server.resources.post
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Serializable
data class AgentSummary(
    val slug: String,
    val label: String,
    val agentUri: String,
    val isActive: Boolean,
    val lastStatus: String,
    val lastPing: String?,
    val lastPongDeltaMs: Int?,
    val encryptPayload: Boolean,
    val supportsEncryptedPayload: Boolean,
    val createdAt: String,
)

@Serializable
data class CreateBootstrapTokenRequest(val slug: String, val label: String? = null) : Validatable {
    override fun validate() = buildList {
        Validators.notBlank("slug", slug)?.let(::add)
        Validators.maxLen("slug", slug, 64)?.let(::add)
        Validators.maxLen("label", label, 64)?.let(::add)
    }
}

/**
 * The raw token is shown exactly once — only its hash is stored.
 *
 * `schedulerUrl` is the base URL the agent must dial with it
 * (`PROBE_AGENT_SCHEDULER_URL`), from [AgentEnrolmentAddress]; null when the
 * deployment has not configured one, in which case the dashboard shows a
 * placeholder rather than an address that is only right on one network.
 */
@Serializable
data class BootstrapTokenResponse(
    val slug: String,
    val token: String,
    val expiresAt: String,
    val schedulerUrl: String? = null,
)

/**
 * Partial update — an absent field is left as it is.
 *
 * `supportsEncryptedPayload` is deliberately not here: it is the agent's own
 * report of what it can do, refreshed by the health challenge, never a choice.
 */
@Serializable
data class UpdateAgentRequest(val isActive: Boolean? = null, val encryptPayload: Boolean? = null)

@Serializable
data class AgentHealthCheck(
    val challengedAt: String,
    val respondedAt: String?,
    val roundTripMs: Int?,
    val result: String,
)

private const val TOKEN_BYTES = 32
private const val TOKEN_TTL_HOURS = 1L
private val SLUG_RE = Regex("^[a-z0-9][a-z0-9-]{0,63}$")

/**
 * @OpenAPITag Agents
 * Probe-agent fleet management: registered-agent list, bootstrap-token
 * generation (the API twin of the `--agent-bootstrap` CLI), activation and
 * payload-sealing toggles. Agents are platform infrastructure — gated on org
 * settings.
 */
@Resource("/api/v1/agents")
class AgentAdmin {
    @Resource("list")
    class List(val parent: AgentAdmin = AgentAdmin())

    @Resource("bootstrap-token")
    class BootstrapToken(val parent: AgentAdmin = AgentAdmin())

    @Serializable
    @Resource("{slug}")
    class BySlug(val parent: AgentAdmin = AgentAdmin(), val slug: String) {
        @Serializable
        @Resource("checks")
        class Checks(val parent: BySlug, val hours: Int = 24)
    }
}

fun Route.agentAdminRoutes() {
    /**
     * Lists the registered agents the caller may see, active and inactive.
     *
     * Two gates, and both matter. The `settings` grant is the infrastructure
     * permission — an org member without it has no business reading the fleet's
     * configuration at all. [AgentVisibility] is the second: this response
     * carries each agent's `agentUri`, the address of the machine that runs
     * probes, so a deployment that gives agents owners must be able to narrow
     * the list before it is serialized. Core owns no agents and returns
     * everything; see [AgentVisibility] for what an overlay adds.
     */
    get<AgentAdmin.List> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val agents = transaction {
            requireOrgRead(orgId, principal.userId) { it.settings }
            val rows = ProbeAgents.selectAll()
                .where { ProbeAgents.deleted eq false }
                .orderBy(ProbeAgents.slug)
                .toList()
            val visible = AgentVisibility.visible(orgId, principal.userId, rows.map { it[ProbeAgents.slug] })
            rows.filter { it[ProbeAgents.slug] in visible }
                .map { row ->
                    AgentSummary(
                        slug = row[ProbeAgents.slug],
                        label = row[ProbeAgents.label],
                        agentUri = row[ProbeAgents.agentUri],
                        isActive = row[ProbeAgents.isActive],
                        lastStatus = row[ProbeAgents.lastStatus],
                        lastPing = row[ProbeAgents.lastPing].toString(),
                        lastPongDeltaMs = row[ProbeAgents.lastPongDeltaMs],
                        encryptPayload = row[ProbeAgents.encryptPayload],
                        supportsEncryptedPayload = row[ProbeAgents.supportsEncryptedPayload],
                        createdAt = row[ProbeAgents.createdAt].toString(),
                    )
                }
        }
        call.respond(agents)
    }

    /** Creates a one-time agent bootstrap token (1h TTL, shown once). */
    post<AgentAdmin.BootstrapToken> {
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<CreateBootstrapTokenRequest>(call)
        val slug = body.slug.trim()
        if (!SLUG_RE.matches(slug)) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        val label = body.label?.trim()?.ifBlank { null } ?: slug

        val token = ByteArray(TOKEN_BYTES).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        val tokenHash = BCrypt.withDefaults().hashToString(12, token.toCharArray())
        val expiresAt = Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS)

        // An external module intercepts the request to connect a new agent via
        // this key; a before-hook (with the requesting org/user in context) may
        // deny it, atomically with the token insert.
        val tokenId = UUID.randomUUID()
        Interceptors.injectableInTx(
            "agent.bootstrap.create",
            // Carry the slug and the created token's id so a hook can act on
            // exactly this token, never on another row that shares the slug.
            InterceptorContext(
                orgId = orgId, userId = principal.userId,
                extra = mutableMapOf("slug" to slug, "tokenId" to tokenId),
            ),
        ) {
            requireOrgWrite(orgId, principal.userId) { it.settings }
            // Registration refuses a slug that is already an agent, so a token
            // for one can never be redeemed — refuse it here, where the person
            // asking can still pick another name. Deleted agents do not hold
            // their slug (it is renamed to free it), so any row counts.
            val taken = ProbeAgents.selectAll().where { ProbeAgents.slug eq slug }.empty().not()
            if (taken) throw ConflictException(ErrorCodes.AGENT_SLUG_TAKEN)
            // The signing CA is created lazily on the very first bootstrap.
            CaService.ensureCaRoot()
            // A fresh token supersedes any outstanding one for the slug — the
            // token is shown once, so a lost one is re-issued, never reused.
            // The partial unique index (slug WHERE used = false) enforces the
            // one-outstanding invariant against concurrent creation.
            AgentBootstrapTokens.deleteWhere {
                (AgentBootstrapTokens.slug eq slug) and (AgentBootstrapTokens.used eq false)
            }
            AgentBootstrapTokens.insert {
                it[id] = tokenId
                it[AgentBootstrapTokens.slug] = slug
                it[AgentBootstrapTokens.label] = label
                it[AgentBootstrapTokens.tokenHash] = tokenHash
                // Indexed locator — enrolment looks the row up by this digest
                // instead of bcrypting every outstanding token.
                it[AgentBootstrapTokens.tokenLookup] = TokenHasher.sha256Hex(token)
                it[AgentBootstrapTokens.expiresAt] = expiresAt
                it[createdBy] = principal.userId
                it[createdAt] = Instant.now()
            }
            AuditService.log(orgId, principal.userId, "create.agent_bootstrap_token", "agent", slug, entityDisplayName = slug)
        }

        call.respond(
            BootstrapTokenResponse(
                slug = slug,
                token = token,
                expiresAt = expiresAt.toString(),
                schedulerUrl = AgentEnrolmentAddress.resolve(),
            ),
        )
    }

    /**
     * Health-check history for one agent, most recent window first-to-last.
     *
     * The slug is resolved against the whole fleet, so an agent the caller
     * cannot see must answer as though it does not exist — otherwise this
     * endpoint hands back, one slug at a time, exactly what the list gate
     * withholds. Not-found rather than forbidden: the absence of an agent and
     * the invisibility of one must be indistinguishable, or the 403 confirms
     * the slug.
     */
    get<AgentAdmin.BySlug.Checks> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val hours = resource.hours.coerceIn(1, 24 * 30)
        val cutoff = Instant.now().minus(hours.toLong(), ChronoUnit.HOURS)
        val checks = transaction {
            requireOrgRead(orgId, principal.userId) { it.settings }
            if (!AgentVisibility.canSee(orgId, principal.userId, resource.parent.slug)) throw NotFoundException()
            val agentId = ProbeAgents.selectAll()
                .where { ProbeAgents.slug eq resource.parent.slug }
                .firstOrNull()?.get(ProbeAgents.id) ?: throw NotFoundException()
            AgentHealthChecks.selectAll()
                .where { (AgentHealthChecks.probeAgentId eq agentId) and (AgentHealthChecks.challengedAt greaterEq cutoff) }
                .orderBy(AgentHealthChecks.challengedAt)
                .map { row ->
                    AgentHealthCheck(
                        challengedAt = row[AgentHealthChecks.challengedAt].toString(),
                        respondedAt = row[AgentHealthChecks.respondedAt]?.toString(),
                        roundTripMs = row[AgentHealthChecks.roundTripMs],
                        result = row[AgentHealthChecks.result],
                    )
                }
        }
        call.respond(checks)
    }

    /**
     * Decommissions an agent: history keeps its rows (probe_results FK), but
     * the agent leaves every list, selection pool and health cycle, and its
     * certificates are revoked so the identity is dead.
     */
    delete<AgentAdmin.BySlug> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        Interceptors.injectableInTx(
            "agent.delete",
            InterceptorContext(orgId = orgId, userId = principal.userId, extra = mutableMapOf("slug" to resource.slug)),
        ) {
            requireOrgWrite(orgId, principal.userId) { it.settings }
            val agent = ProbeAgents.selectAll()
                .where { (ProbeAgents.slug eq resource.slug) and (ProbeAgents.deleted eq false) }
                .firstOrNull() ?: throw NotFoundException()
            val agentId = agent[ProbeAgents.id]

            // Free the slug (unique index) for future re-bootstraps; keep it
            // recognizable in audit/history joins. varchar(64): trim the base
            // so the suffix always fits.
            val freedSlug = "${resource.slug.take(45)}-deleted-${Instant.now().epochSecond}"
            ProbeAgents.update({ ProbeAgents.id eq agentId }) {
                it[isActive] = false
                it[deleted] = true
                it[slug] = freedSlug
            }
            AgentCertificates.update({
                (AgentCertificates.probeAgentId eq agentId) and (AgentCertificates.revoked eq false)
            }) {
                it[revoked] = true
                it[revokedAt] = Instant.now()
                it[revokedReason] = "agent deleted"
            }
            ServiceAllowedAgents.deleteWhere { probeAgentId eq agentId }
            AgentBootstrapTokens.deleteWhere { (slug eq resource.slug) and (used eq false) }
            AuditService.log(orgId, principal.userId, "delete.agent", "agent", resource.slug, entityDisplayName = resource.slug)
        }

        // Same channels and the same audience the health feed uses — live
        // clients drop the agent without waiting for a poll. Addressed after the
        // row is gone, which is why the audience is resolved from the slug: a
        // deployment with agent ownership must be able to answer for an agent it
        // has just lost, and the slug is what both sides key on.
        val removedEvent = buildJsonObject { put("agentSlug", resource.slug) }
        FleetAudience.publish(resource.slug, "agent.removed", removedEvent)

        call.respond(mapOf("ok" to true))
    }

    /**
     * Updates the operator-set flags on an agent: activation (inactive agents
     * are never selected) and payload sealing. Sealing is accepted even for an
     * agent that reports no support — the scheduler warns and dispatches
     * unsealed, so an agent upgraded after the fact needs no second visit here.
     */
    patch<AgentAdmin.BySlug> { resource ->
        val (principal, orgId) = requireAuthWithOrg(call)
        val body = tryReceive<UpdateAgentRequest>(call)
        if (body.isActive == null && body.encryptPayload == null) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        Interceptors.injectableInTx(
            "agent.update",
            InterceptorContext(orgId = orgId, userId = principal.userId, extra = mutableMapOf("slug" to resource.slug)),
        ) {
            requireOrgWrite(orgId, principal.userId) { it.settings }
            val updated = ProbeAgents.update({ (ProbeAgents.slug eq resource.slug) and (ProbeAgents.deleted eq false) }) { stmt ->
                if (body.isActive != null) stmt[isActive] = body.isActive
                if (body.encryptPayload != null) stmt[encryptPayload] = body.encryptPayload
            }
            if (updated == 0) throw NotFoundException()
            if (body.isActive != null) {
                AuditService.log(
                    orgId, principal.userId,
                    if (body.isActive) "activate.agent" else "deactivate.agent",
                    "agent", resource.slug,
                    entityDisplayName = resource.slug,
                )
            }
            if (body.encryptPayload != null) {
                AuditService.log(
                    orgId, principal.userId, "update.agent", "agent", resource.slug,
                    entityDisplayName = resource.slug,
                )
            }
        }
        call.respond(mapOf("ok" to true))
    }
}
