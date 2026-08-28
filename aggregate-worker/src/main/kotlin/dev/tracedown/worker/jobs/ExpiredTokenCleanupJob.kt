package dev.tracedown.worker.jobs

import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.PasswordResetTokens
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.ExpiredTokenCleanupJob")

/**
 * Deletes expired password-reset tokens and expired, never-redeemed agent
 * bootstrap tokens. Expired tokens are dead credential material with no
 * operational or historical value — they only linger as hashes tied to an
 * account or a slug. Used-but-unexpired reset tokens are kept until expiry
 * (the `used` flag is what blocks replay); they fall out here shortly after.
 *
 * Redeemed bootstrap tokens are NOT purged: their `used_at` is the record of
 * when an agent enrolled, and being marked used they can never be redeemed
 * again. Only the unredeemed, expired ones go — leaving them would also keep
 * the slug's one-outstanding-token slot occupied by a credential nothing can
 * ever present.
 *
 * Purging matters beyond tidiness: both tables are read by unauthenticated
 * endpoints, so every row that lingers is a row those endpoints carry.
 *
 * No retention knob: there is no reason to keep an expired token, ever.
 */
class ExpiredTokenCleanupJob(
    override val intervalSeconds: Long = 3600L,
) : ScheduledJob {

    override val name = "ExpiredTokenCleanupJob"

    override suspend fun execute() {
        val now = Instant.now()
        val (resets, bootstraps) = newSuspendedTransaction(Dispatchers.IO) {
            val resets = PasswordResetTokens.deleteWhere { expiresAt less now }
            val bootstraps = AgentBootstrapTokens.deleteWhere {
                (expiresAt less now) and (used eq false)
            }
            resets to bootstraps
        }
        if (resets > 0 || bootstraps > 0) {
            log.info(
                "Expired token cleanup: {} password reset tokens, {} agent bootstrap tokens deleted",
                resets, bootstraps,
            )
        }
    }
}
