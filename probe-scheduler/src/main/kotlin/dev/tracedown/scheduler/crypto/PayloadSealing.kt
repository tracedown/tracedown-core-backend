package dev.tracedown.scheduler.crypto

import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.ProbeAgents
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.PrivateKey
import java.time.Instant

/**
 * Decides, per agent, whether a dispatch travels sealed — and does the sealing.
 *
 * Split from [PayloadEnvelope] (which only knows bytes and keys) because the
 * decision needs the certificate store and the mode, and neither belongs in a
 * crypto primitive.
 *
 * The agent's public key comes from [AgentCertificates] rather than the TLS
 * session: it is the same certificate the handshake pins, and reading it here
 * means the rest of this class needs no plumbing into the client's socket.
 * Revoked and expired rows are excluded — sealing to a certificate the agent no
 * longer holds would produce a payload it cannot open.
 */
class PayloadSealing(
    /** The scheduler's own certificate, sealed into the request so the agent can answer. */
    private val schedulerCertPem: String,
    /** The matching private key, for opening the agent's sealed answer. */
    private val schedulerKey: PrivateKey,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Returns the body to put on the wire: sealed when this agent is set to it
     * and can take it, the original otherwise.
     *
     * It never refuses to dispatch. A misconfiguration here — the toggle on, the
     * agent downgraded — would otherwise stop that agent probing entirely, which
     * is a worse outcome than a warned-about plaintext run inside a tunnel that
     * is still mutually authenticated.
     */
    fun seal(agentSlug: String, body: JsonObject): JsonObject {
        val agent = sealingStateFor(agentSlug) ?: return body
        if (!agent.enabled) return body

        // The operator asked for it, but the agent says it cannot open one.
        // Sending it anyway would fail every probe to this agent with nothing
        // pointing at why, so send plain and say so loudly — the UI refuses to
        // enable the toggle for an agent in this state, which means seeing this
        // is a downgrade after the fact.
        if (!agent.supported) {
            log.warn(
                "agent {} has payload encryption enabled but reports it cannot open a sealed dispatch " +
                    "— dispatching unsealed; upgrade the agent or turn the setting off",
                agentSlug,
            )
            return body
        }

        val certPem = agent.certificatePem ?: run {
            log.warn("agent {} has payload encryption enabled but no usable certificate — dispatching unsealed", agentSlug)
            return body
        }

        // The reply certificate rides inside the sealed payload rather than
        // beside it: the tunnel already proved who is calling, and keeping it
        // under the GCM tag means nothing about the exchange travels in clear.
        val withReply = JsonObject(body.toMutableMap().apply {
            putAll(buildJsonObject { put("replyCert", schedulerCertPem) })
        })
        return PayloadEnvelope.seal(withReply, certPem)
    }

    /** Opens a sealed answer; a plain answer passes through untouched. */
    fun open(body: JsonObject): JsonObject =
        if (PayloadEnvelope.isEnvelope(body)) PayloadEnvelope.open(body, schedulerKey) else body

    private data class SealingState(
        val enabled: Boolean,
        val supported: Boolean,
        val certificatePem: String?,
    )

    /**
     * The agent's setting, its reported capability, and the certificate to seal
     * to — read together so one dispatch costs one query.
     */
    private fun sealingStateFor(agentSlug: String): SealingState? = transaction {
        val agent = ProbeAgents.selectAll()
            .where { ProbeAgents.slug eq agentSlug }
            .limit(1)
            .firstOrNull() ?: return@transaction null

        if (!agent[ProbeAgents.encryptPayload]) {
            return@transaction SealingState(enabled = false, supported = false, certificatePem = null)
        }

        val now = Instant.now()
        val certPem = AgentCertificates.selectAll()
            .where {
                (AgentCertificates.probeAgentId eq agent[ProbeAgents.id]) and
                    (AgentCertificates.revoked eq false) and
                    (AgentCertificates.expiresAt greater now)
            }
            .orderBy(AgentCertificates.issuedAt to SortOrder.DESC)
            .limit(1)
            .firstOrNull()
            ?.get(AgentCertificates.certificatePem)

        SealingState(
            enabled = true,
            supported = agent[ProbeAgents.supportsEncryptedPayload],
            certificatePem = certPem,
        )
    }
}
