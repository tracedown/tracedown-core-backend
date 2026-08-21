package dev.tracedown.gateway.controllers.domains

import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.audit.auditDiff
import dev.tracedown.common.domain.DomainVerifier
import dev.tracedown.common.domain.dns.DnsProviderProfiles
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.pfs.Page
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.common.pfs.applyPfs
import dev.tracedown.gateway.data.domains.CreateDomainRequest
import dev.tracedown.gateway.data.domains.DnsHandoffDto
import dev.tracedown.gateway.data.domains.DomainSummary
import dev.tracedown.gateway.data.domains.UpdateDomainRequest
import dev.tracedown.gateway.data.domains.VerifyDomainResponse
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.gateway.util.BadRequestException
import dev.tracedown.gateway.util.ConflictException
import dev.tracedown.gateway.util.NotFoundException
import dev.tracedown.gateway.util.requireOrgRead
import dev.tracedown.gateway.util.requireOrgWrite
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import java.time.Instant
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object DomainController {

    private lateinit var aesKeyBytes: ByteArray
    private lateinit var verifier: DomainVerifier

    private val validVerificationTypes = setOf("http-01", "dns-01")
    private val domainRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?)*$")

    /** Initializes the controller with the AES key and domain verifier. */
    fun init(aesKeyHex: String, domainVerifier: DomainVerifier) {
        aesKeyBytes = aesKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        verifier = domainVerifier
    }

    // ── CRUD ──

    /** Adds a domain to the organization. Generates a challenge token. Requires domains.write. */
    fun create(orgId: UUID, request: CreateDomainRequest, userId: UUID): DomainSummary {
        val domain = request.domain.lowercase().trim()
        if (domain.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
        if (domain.length > 256) throw BadRequestException(ErrorCodes.FIELD_TOO_LONG)
        if (!domainRegex.matches(domain)) throw BadRequestException(ErrorCodes.FIELD_INVALID)
        if (request.verificationType !in validVerificationTypes) {
            throw BadRequestException(ErrorCodes.FIELD_INVALID)
        }

        return transaction {
            requireOrgWrite(orgId, userId) { it.domains }

            val exists = OrgDomains.selectAll()
                .where {
                    (OrgDomains.organizationId eq orgId) and
                    (OrgDomains.domain eq domain) and
                    (OrgDomains.deleted eq false)
                }
                .any()
            if (exists) throw ConflictException()

            val id = UUID.randomUUID()
            val now = Instant.now()
            val challenge = generateChallenge(orgId, domain)

            if (request.exceptions != null) {
                validateExceptions(request.exceptions, domain)
            }

            OrgDomains.insert {
                it[OrgDomains.id] = id
                it[organizationId] = orgId
                it[OrgDomains.domain] = domain
                it[OrgDomains.challenge] = challenge
                it[verificationType] = request.verificationType
                it[status] = "pending"
                it[wildcardEnabled] = request.wildcardEnabled
                it[exceptions] = request.exceptions
            }

            AuditService.log(orgId, userId, "create.domain", "domain", id.toString(), entityDisplayName = request.domain, comment = "${request.domain} (${request.verificationType})")

            domainSummary(id)
        }
    }

    /** Lists all domains in the organization. Requires domains.read. */
    fun list(orgId: UUID, userId: UUID, pfs: PfsParams): Page<DomainSummary> {
        return transaction {
            requireOrgRead(orgId, userId) { it.domains }

            val query = OrgDomains.selectAll()
                .where { (OrgDomains.organizationId eq orgId) and (OrgDomains.deleted eq false) }

            val (pagedQuery, total) = query.applyPfs(pfs)
            val items = pagedQuery.map { domainSummaryFromRow(it) }

            Page(items = items, total = total, page = pfs.page, pageSize = pfs.pageSize)
        }
    }

    /** Returns a single domain. Requires domains.read. */
    fun get(orgId: UUID, domainId: UUID, userId: UUID): DomainSummary {
        return transaction {
            requireOrgRead(orgId, userId) { it.domains }
            domainSummary(domainId, orgId)
        }
    }

    /** Updates a domain's settings (wildcard, exceptions). Requires domains.write. */
    fun update(orgId: UUID, domainId: UUID, request: UpdateDomainRequest, userId: UUID): DomainSummary {
        return transaction {
            requireOrgWrite(orgId, userId) { it.domains }
            requireDomainExists(domainId, orgId)

            val old = OrgDomains.selectAll()
                .where { (OrgDomains.id eq domainId) and (OrgDomains.deleted eq false) }
                .first()

            if (request.exceptions != null) {
                validateExceptions(request.exceptions, old[OrgDomains.domain])
            }

            OrgDomains.update({ (OrgDomains.id eq domainId) and (OrgDomains.organizationId eq orgId) }) {
                request.wildcardEnabled?.let { v -> it[wildcardEnabled] = v }
                request.exceptions?.let { v -> it[exceptions] = v }
            }

            AuditService.log(
                orgId, userId, "update.domain", "domain", domainId.toString(),
                entityDisplayName = old[OrgDomains.domain],
                diff = auditDiff(
                    Triple("wildcardEnabled", old[OrgDomains.wildcardEnabled], request.wildcardEnabled ?: old[OrgDomains.wildcardEnabled]),
                    Triple(
                        "exceptions",
                        (old[OrgDomains.exceptions] ?: emptyList()).joinToString(","),
                        (request.exceptions ?: old[OrgDomains.exceptions] ?: emptyList()).joinToString(","),
                    ),
                ),
            )

            domainSummary(domainId)
        }
    }

    /** Soft-deletes a domain. Requires domains.write. */
    fun delete(orgId: UUID, domainId: UUID, userId: UUID) {
        transaction {
            requireOrgWrite(orgId, userId) { it.domains }
            requireDomainExists(domainId, orgId)

            val deletedDomain = OrgDomains.selectAll()
                .where { OrgDomains.id eq domainId }
                .firstOrNull()?.get(OrgDomains.domain)

            OrgDomains.update({ OrgDomains.id eq domainId }) {
                it[deleted] = true
                it[deletedAt] = Instant.now()
            }

            AuditService.log(orgId, userId, "delete.domain", "domain", domainId.toString(), entityDisplayName = deletedDomain)
        }
    }

    /**
     * Where this domain's DNS records are edited, when the provider is one we
     * recognise from the zone's delegation. One DNS lookup, no credential —
     * the point is to save the user hunting for the right page, not to touch
     * their zone. Requires domains.read.
     */
    fun dnsHandoff(orgId: UUID, domainId: UUID, userId: UUID): DnsHandoffDto {
        val domain = transaction {
            requireOrgRead(orgId, userId) { it.domains }
            val row = OrgDomains.selectAll()
                .where {
                    (OrgDomains.id eq domainId) and
                        (OrgDomains.organizationId eq orgId) and
                        (OrgDomains.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()
            // Only a DNS challenge has a record to place; http-01 needs a file.
            if (row[OrgDomains.verificationType] != "dns-01") return@transaction null
            row[OrgDomains.domain]
        } ?: return DnsHandoffDto.NONE

        // Name-server lookup, so it stays outside the transaction.
        val detection = DnsProviderProfiles.detect(domain) ?: return DnsHandoffDto.NONE
        val url = detection.profile.dashboardUrl(detection.zone) ?: return DnsHandoffDto.NONE
        return DnsHandoffDto(mode = "dashboard", providerName = detection.profile.displayName, url = url)
    }

    // ── Verification ──

    /**
     * Triggers domain verification. Checks the challenge token via the configured method.
     * Updates status to "verified" on success, keeps "pending" on failure.
     * Requires domains.write.
     */
    fun verify(orgId: UUID, domainId: UUID, userId: UUID): VerifyDomainResponse {
        return transaction {
            requireOrgWrite(orgId, userId) { it.domains }

            val row = OrgDomains.selectAll()
                .where {
                    (OrgDomains.id eq domainId) and
                    (OrgDomains.organizationId eq orgId) and
                    (OrgDomains.deleted eq false)
                }
                .firstOrNull() ?: throw NotFoundException()

            val domain = row[OrgDomains.domain]
            val challenge = row[OrgDomains.challenge]
            val type = row[OrgDomains.verificationType]
            val now = Instant.now()

            val result = verifier.verify(domain, challenge, type)

            if (result.verified) {
                OrgDomains.update({ OrgDomains.id eq domainId }) {
                    it[status] = "verified"
                    it[verifiedAt] = now
                    it[lastCheckedAt] = now
                    it[lapsed] = false
                }
                AuditService.log(orgId, userId, "verify.domain", "domain", domainId.toString(), entityDisplayName = domain)
                VerifyDomainResponse(verified = true, status = "verified")
            } else {
                OrgDomains.update({ OrgDomains.id eq domainId }) {
                    it[lastCheckedAt] = now
                }
                VerifyDomainResponse(verified = false, status = "pending", error = result.error)
            }
        }
    }

    // ── Internals ──

    /** Generates an HMAC-SHA256 challenge token from orgId and domain. */
    private fun generateChallenge(orgId: UUID, domain: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(aesKeyBytes, "HmacSHA256"))
        val data = "$orgId:$domain".toByteArray(Charsets.UTF_8)
        val hash = mac.doFinal(data)
        return hash.joinToString("") { "%02x".format(it) }
    }

    private fun requireDomainExists(domainId: UUID, orgId: UUID) {
        val exists = OrgDomains.selectAll()
            .where {
                (OrgDomains.id eq domainId) and
                (OrgDomains.organizationId eq orgId) and
                (OrgDomains.deleted eq false)
            }
            .any()
        if (!exists) throw NotFoundException()
    }

    private fun domainSummary(id: UUID, orgId: UUID? = null): DomainSummary {
        val query = if (orgId != null) {
            OrgDomains.selectAll().where {
                (OrgDomains.id eq id) and (OrgDomains.organizationId eq orgId) and (OrgDomains.deleted eq false)
            }
        } else {
            OrgDomains.selectAll().where { (OrgDomains.id eq id) and (OrgDomains.deleted eq false) }
        }
        val row = query.firstOrNull() ?: throw NotFoundException()
        return domainSummaryFromRow(row)
    }

    /**
     * Validates exception entries. Each exception must be a subdomain of the parent domain.
     * For example, if the domain is "company.com", exceptions must end with ".company.com".
     */
    private fun validateExceptions(exceptions: List<String>, parentDomain: String) {
        val suffix = ".$parentDomain"
        for (entry in exceptions) {
            if (entry.isBlank()) throw BadRequestException(ErrorCodes.FIELD_REQUIRED)
            if (!entry.endsWith(suffix)) {
                throw BadRequestException(ErrorCodes.FIELD_INVALID)
            }
        }
    }

    private fun domainSummaryFromRow(row: org.jetbrains.exposed.sql.ResultRow) = DomainSummary(
        id = row[OrgDomains.id].toString(),
        domain = row[OrgDomains.domain],
        challenge = row[OrgDomains.challenge],
        verificationType = row[OrgDomains.verificationType],
        status = row[OrgDomains.status],
        verifiedAt = row[OrgDomains.verifiedAt]?.toString(),
        wildcardEnabled = row[OrgDomains.wildcardEnabled],
        exceptions = row[OrgDomains.exceptions] ?: emptyList(),
        lastCheckedAt = row[OrgDomains.lastCheckedAt]?.toString(),
        lapsed = row[OrgDomains.lapsed],
        dnsSetupMethod = row[OrgDomains.dnsSetupMethod],
        dnsSetupAt = row[OrgDomains.dnsSetupAt]?.toString(),
    )
}
