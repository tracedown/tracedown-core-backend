package dev.tracedown.gateway.controllers.agents

import at.favre.lib.crypto.bcrypt.BCrypt
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.agents.FleetAudience
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import dev.tracedown.gateway.data.agents.AgentRegisterRequest
import dev.tracedown.gateway.data.agents.AgentRegisterResponse
import dev.tracedown.gateway.data.agents.AgentRenewRequest
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ForbiddenException
import org.bouncycastle.openssl.PEMParser
import org.bouncycastle.pkcs.PKCS10CertificationRequest
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.io.StringReader
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Handles agent registration via bootstrap tokens.
 *
 * Flow:
 * 1. Agent sends bootstrap token + CSR.
 * 2. We validate the token (bcrypt, not expired, not used).
 * 3. Sign the CSR with the internal CA.
 * 4. Create the probe_agents row.
 * 5. Store the signed certificate in agent_certificates.
 * 6. Mark the token as used.
 * 7. Return the signed cert + CA root PEM.
 */
object AgentRegistrationController {

    /** Registers an agent using a bootstrap token. */
    fun register(request: AgentRegisterRequest, agentUri: String): AgentRegisterResponse {
        return transaction {
            // Find and validate the bootstrap token.
            //
            // This endpoint is unauthenticated AND rate-limit-exempt (all of
            // /internal/* is, deliberately — it carries enrolment and the
            // health-challenge token endpoint, which customer traffic must not
            // throttle). It therefore must not do work proportional to how many
            // tokens exist: the row is located by its indexed SHA-256 digest,
            // and expiry is settled in SQL, so a wrong or expired token costs a
            // single indexed lookup and NO bcrypt at all. Exactly one candidate
            // can match, and only it is bcrypt-verified — the digest locates,
            // the bcrypt hash still authenticates.
            val now = Instant.now()
            val tokenRow = AgentBootstrapTokens.selectAll()
                .where {
                    (AgentBootstrapTokens.tokenLookup eq TokenHasher.sha256Hex(request.bootstrapToken)) and
                        (AgentBootstrapTokens.used eq false) and
                        (AgentBootstrapTokens.expiresAt greater now)
                }
                .limit(1)
                .firstOrNull()
                ?.takeIf { row ->
                    BCrypt.verifyer().verify(
                        request.bootstrapToken.toCharArray(),
                        row[AgentBootstrapTokens.tokenHash],
                    ).verified
                }
                ?: throw ForbiddenException()

            val slug = tokenRow[AgentBootstrapTokens.slug]
            val label = tokenRow[AgentBootstrapTokens.label]

            // Check slug uniqueness (agent may have been partially registered).
            val existing = ProbeAgents.selectAll()
                .where { ProbeAgents.slug eq slug }
                .firstOrNull()
            if (existing != null) {
                throw BadRequestException(ErrorCodes.ALREADY_EXISTS)
            }

            // Sign the CSR. Identity is bound to the token's slug server-side —
            // the CSR's own subject is ignored (see CaService.signCsr).
            val (agentCertPem, caCertPem) = try {
                CaService.signCsr(request.csrPem, slug)
            } catch (e: CaService.CsrValidationException) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }

            // Extract public key from the signed certificate.
            val agentCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(agentCertPem.byteInputStream()) as X509Certificate
            val publicKeyPem = pemEncodePublicKey(agentCert.publicKey.encoded)

            // Create the probe agent.
            val agentId = ProbeAgents.insert {
                it[ProbeAgents.slug] = slug
                it[ProbeAgents.label] = label
                it[ProbeAgents.agentUri] = agentUri
                it[publicKey] = publicKeyPem
                it[isActive] = true
                it[lastPing] = now
                it[lastStatus] = "success"
                it[lastPingDelayMs] = 0
                it[lastPongDeltaMs] = 0
                it[createdAt] = now
            } get ProbeAgents.id

            // Store the signed certificate.
            val fingerprint = CaService.fingerprint(agentCertPem)
            AgentCertificates.insert {
                it[id] = UUID.randomUUID()
                it[probeAgentId] = agentId
                it[certificatePem] = agentCertPem
                it[AgentCertificates.fingerprint] = fingerprint
                it[issuedAt] = now
                it[expiresAt] = agentCert.notAfter.toInstant()
                it[createdAt] = now
            }

            // Mark the bootstrap token as used.
            AgentBootstrapTokens.update({
                AgentBootstrapTokens.id eq tokenRow[AgentBootstrapTokens.id]
            }) {
                it[used] = true
                it[usedAt] = now
            }

            // Publish agent registered event for live dashboard updates
            val eventData = buildJsonObject {
                put("agentSlug", slug)
                put("status", "success")
                put("lastCheck", now.toString())
                put("lastResponseMs", 0)
            }
            FleetAudience.publish(slug, "health.updated", eventData)

            AgentRegisterResponse(
                certificatePem = agentCertPem,
                caRootPem = caCertPem,
                slug = slug,
            )
        }
    }

    /**
     * Rotates an already-registered agent's certificate.
     *
     * Proof-of-possession: the agent signs the new CSR bytes with its CURRENT
     * private key. We verify that signature against the public key stored for
     * the agent, sign the new CSR with the CA, persist the new certificate,
     * and roll the stored public key forward. No bootstrap token is involved —
     * this path is for existing agents only.
     */
    fun renew(request: AgentRenewRequest): AgentRegisterResponse {
        return transaction {
            // Look up the live agent by slug.
            val agentRow = ProbeAgents.selectAll()
                .where {
                    (ProbeAgents.slug eq request.slug) and
                        (ProbeAgents.deleted eq false) and
                        (ProbeAgents.isActive eq true)
                }
                .firstOrNull()
                ?: throw ForbiddenException()

            val agentId = agentRow[ProbeAgents.id]

            // Verify proof-of-possession: signature over the new CSR bytes must
            // validate against the agent's currently stored public key.
            val currentPublicKey = parsePublicKeyPem(agentRow[ProbeAgents.publicKey])
            val signatureValid = try {
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(currentPublicKey)
                    update(request.csrPem.toByteArray(Charsets.UTF_8))
                    verify(Base64.getDecoder().decode(request.signature))
                }
            } catch (_: Exception) {
                false
            }
            if (!signatureValid) {
                throw ForbiddenException()
            }

            // Sign the new CSR with the CA. Identity stays bound to this agent's
            // slug — the CSR subject is ignored (see CaService.signCsr).
            val (agentCertPem, caCertPem) = try {
                CaService.signCsr(request.csrPem, request.slug)
            } catch (e: CaService.CsrValidationException) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }

            val newCert = CertificateFactory.getInstance("X.509")
                .generateCertificate(agentCertPem.byteInputStream()) as X509Certificate
            val newPublicKeyPem = pemEncodePublicKey(newCert.publicKey.encoded)

            val now = Instant.now()
            val newCertId = UUID.randomUUID()

            // Store the new certificate first so trust never gaps.
            AgentCertificates.insert {
                it[id] = newCertId
                it[probeAgentId] = agentId
                it[certificatePem] = agentCertPem
                it[fingerprint] = CaService.fingerprint(agentCertPem)
                it[issuedAt] = now
                it[expiresAt] = newCert.notAfter.toInstant()
                it[createdAt] = now
            }

            // Roll the stored public key forward to the newly issued key.
            ProbeAgents.update({ ProbeAgents.id eq agentId }) {
                it[publicKey] = newPublicKeyPem
            }

            // Only after the new cert is persisted, supersede the prior ones.
            AgentCertificates.update({
                (AgentCertificates.probeAgentId eq agentId) and
                    (AgentCertificates.id neq newCertId) and
                    (AgentCertificates.revoked eq false)
            }) {
                it[revoked] = true
                it[revokedAt] = now
                it[revokedReason] = "superseded"
            }

            AgentRegisterResponse(
                certificatePem = agentCertPem,
                caRootPem = caCertPem,
                slug = request.slug,
            )
        }
    }


    private fun parsePublicKeyPem(pem: String): PublicKey {
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
    }

    private fun pemEncodePublicKey(encoded: ByteArray): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(encoded)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----\n"
    }
}
