package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.domain.TargetOptOut
import dev.tracedown.common.domain.dns.TxtLookup
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * Asks, once per host per hour, whether a target publishes the do-not-probe
 * record (see [TargetOptOut]).
 *
 * The lookup runs on the dispatch worker, like the address resolution
 * [dev.tracedown.common.net.ProbeTargetPolicy] already does there, so a target
 * whose resolver is slow costs that one worker and nothing else. Redis A holds
 * the answer between ticks: a service on a one-minute schedule would otherwise
 * ask the same question sixty times an hour, and a fleet of them would make the
 * scheduler look like a DNS flood to the very operator who asked to be left
 * alone.
 *
 * **Both answers are cached**, for the same [ttlSeconds]. Caching only the
 * refusals would leave every probed host paying for a lookup on every tick,
 * which is the cost the cache exists to remove; an hour is also how long it
 * takes for a newly published record to be honoured, which is the trade the
 * TTL makes.
 *
 * **A Redis outage falls through to the resolver.** The cache is an
 * optimisation, never a gate: dispatch must keep working when Redis does not,
 * and neither a read nor a write failure may propagate out of here.
 *
 * @param redis Redis A, or null to check the record without caching at all.
 * @param lookup TXT values for a name; injectable for tests.
 */
class TargetOptOutChecker(
    private val redis: RedisCommands<String, String>?,
    private val lookup: (String) -> List<String> = TxtLookup::txtOrEmpty,
    private val ttlSeconds: Long = CACHE_TTL_SECONDS,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** Whether [host], or a parent of it, asks not to be probed. */
    fun optedOut(host: String): Boolean {
        val key = "$CACHE_PREFIX$host"
        when (cached(key)) {
            CACHED_YES -> return true
            CACHED_NO -> return false
        }
        val answer = TargetOptOut.published(host, lookup)
        remember(key, answer)
        return answer
    }

    private fun cached(key: String): String? = try {
        redis?.get(key)
    } catch (e: Exception) {
        log.debug("target opt-out cache unavailable for {}: {}", key, e.message)
        null
    }

    private fun remember(key: String, answer: Boolean) {
        try {
            redis?.set(key, if (answer) CACHED_YES else CACHED_NO, SetArgs().ex(ttlSeconds))
        } catch (e: Exception) {
            log.debug("target opt-out cache write failed for {}: {}", key, e.message)
        }
    }

    companion object {
        /** Redis A key prefix; the rest of the key is the host asked about. */
        const val CACHE_PREFIX = "target_optout:"

        /** How long an answer — either answer — is reused. */
        const val CACHE_TTL_SECONDS = 3600L

        /** Cached value for a host that opted out. */
        const val CACHED_YES = "1"

        /** Cached value for a host that did not. */
        const val CACHED_NO = "0"
    }
}
