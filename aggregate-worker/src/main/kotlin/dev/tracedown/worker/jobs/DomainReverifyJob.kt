package dev.tracedown.worker.jobs

import dev.tracedown.common.config.ioTransaction
import dev.tracedown.common.domain.DomainVerifier
import dev.tracedown.common.models.OrgDomains
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Re-checks verified domains against their challenge tokens (spec §18.4).
 * A domain whose token disappeared is marked `lapsed` — it reverts to
 * unverified probe limits until the token is restored and it passes again.
 * Disabled in trustedDomainMode (everything auto-verifies).
 */
class DomainReverifyJob(
    private val verifier: DomainVerifier,
    private val enabled: Boolean,
    override val intervalSeconds: Long = 86_400L,
) : ScheduledJob {

    override val name = "DomainReverifyJob"

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute() {
        if (!enabled) {
            log.debug("Domain re-verification disabled (trustedDomainMode)")
            return
        }

        val domains = ioTransaction {
            OrgDomains.selectAll()
                .where { (OrgDomains.status eq "verified") and (OrgDomains.deleted eq false) }
                .map { Triple(it[OrgDomains.id], it[OrgDomains.domain], it[OrgDomains.challenge] to it[OrgDomains.verificationType]) }
        }

        var lapsed = 0
        var restored = 0
        for ((id, domain, challengeAndType) in domains) {
            val (challenge, type) = challengeAndType
            val result = try {
                verifier.verify(domain, challenge, type)
            } catch (e: Exception) {
                log.warn("re-verification errored for {}: {}", domain, e.message)
                continue // transient errors must not lapse a domain
            }

            ioTransaction {
                val wasLapsed = OrgDomains.selectAll()
                    .where { OrgDomains.id eq id }
                    .firstOrNull()?.get(OrgDomains.lapsed) ?: false

                OrgDomains.update({ OrgDomains.id eq id }) {
                    it[lastCheckedAt] = Instant.now()
                    it[OrgDomains.lapsed] = !result.verified
                }
                if (!result.verified && !wasLapsed) lapsed++
                if (result.verified && wasLapsed) restored++
            }
        }
        if (lapsed > 0 || restored > 0) {
            log.info("Domain re-verification: {} lapsed, {} restored ({} checked)", lapsed, restored, domains.size)
        }
    }
}
