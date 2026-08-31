package dev.tracedown.gateway.util

import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicLong

/**
 * Watches the forwarded chains the gateway actually receives, and says so when
 * they contradict the configured hop count.
 *
 * `rateLimit.trustedProxies` has to equal the number of proxies between the
 * internet and this process. Set too low — one, say, when a platform edge sits
 * in front of a proxy tier — every request resolves to the *edge's* address,
 * the whole deployment shares one rate-limit bucket, and the 16th login in a
 * minute from anybody 429s everybody. Nothing about that is visible: the
 * limiter works exactly as designed, on the wrong key.
 *
 * The tell is a collapse of cardinality. Under a correct hop count the resolved
 * client IP varies with the caller; under one that is too low it is a constant,
 * while the *claimed* leftmost entry of the forwarded header — the address the
 * outermost proxy recorded — keeps varying. One resolved address across many
 * distinct claimed clients is therefore the signature, and it is not a shape
 * correct configuration produces.
 *
 * Deliberately not a security control: a client can put anything in
 * `X-Forwarded-For`, so a determined caller can provoke this warning. It costs
 * a log line and changes no decision — [resolveClientIp] still ignores every
 * hop further out than the configured count.
 */
class ProxyChainObserver(
    private val trustedProxies: Int,
    private val sampleSize: Int = DEFAULT_SAMPLE_SIZE,
    private val distinctClaimedThreshold: Int = DEFAULT_DISTINCT_CLAIMED,
    private val warnEveryMillis: Long = DEFAULT_WARN_INTERVAL_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Where a finding goes. Injectable so a test can read what was said. */
    private val warn: (String) -> Unit = DEFAULT_WARN,
) {

    private val lock = Any()
    private val resolvedSeen = HashSet<String>()
    private val claimedSeen = HashSet<String>()
    private var sampled = 0
    private var longestChain = 0
    /**
     * When the last finding was reported. Starts far enough in the past that the
     * first one is always reported, without an is-this-the-first special case
     * that a zero-based test clock would have to work around.
     */
    private val lastWarnedAt = AtomicLong(Long.MIN_VALUE / 2)

    /**
     * Records one request. [forwarded] is the `X-Forwarded-For` value split into
     * its entries, outermost claim first; [resolvedIp] is what
     * [resolveClientIp] made of it.
     */
    fun observe(resolvedIp: String, forwarded: List<String>) {
        val suspicious = synchronized(lock) {
            resolvedSeen.add(resolvedIp)
            forwarded.firstOrNull()?.let { claimedSeen.add(it) }
            if (forwarded.size > longestChain) longestChain = forwarded.size
            sampled++
            if (sampled < sampleSize) {
                null
            } else {
                val verdict = if (resolvedSeen.size == 1 && claimedSeen.size >= distinctClaimedThreshold) {
                    Triple(resolvedSeen.first(), claimedSeen.size, longestChain)
                } else {
                    null
                }
                // Start a fresh window either way: the question is about the
                // traffic now, not since boot.
                resolvedSeen.clear()
                claimedSeen.clear()
                longestChain = 0
                sampled = 0
                verdict
            }
        } ?: return

        val (resolved, distinctClaimed, chain) = suspicious
        val now = clock()
        val previous = lastWarnedAt.get()
        if (now - previous < warnEveryMillis) return
        if (!lastWarnedAt.compareAndSet(previous, now)) return

        warn(
            "Rate limiting is keying every request on $resolved — $distinctClaimed distinct forwarded " +
                "clients in the last $sampleSize requests all resolved to one address, with chains up to " +
                "$chain hop(s) against TRUSTED_PROXIES=$trustedProxies. The whole deployment is " +
                "sharing one rate-limit bucket; set TRUSTED_PROXIES to the real number of " +
                "proxies in front of the gateway.",
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ProxyChainObserver::class.java)

        /** Default sink: the service log. */
        val DEFAULT_WARN: (String) -> Unit = { log.warn(it) }

        /** Requests per window. Small enough to notice quickly, large enough not to fire on noise. */
        const val DEFAULT_SAMPLE_SIZE = 200

        /**
         * Distinct claimed clients needed before one resolved address is
         * evidence rather than a coincidence. Eight separate callers all landing
         * on one key is not something a correct configuration does.
         */
        const val DEFAULT_DISTINCT_CLAIMED = 8

        /** Warn at most this often — the condition persists until it is fixed. */
        const val DEFAULT_WARN_INTERVAL_MS = 15 * 60 * 1000L

        /**
         * Says, at startup, that the hop count was never configured.
         *
         * The default of 1 is right for exactly one deployment shape — the
         * bundled nginx and nothing else. Any platform edge in front of it makes
         * the default wrong, and wrong here is silent, so a production boot that
         * never named the variable says so once.
         */
        fun warnIfDefaultInProduction(
            production: Boolean,
            explicitlySet: Boolean,
            trustedProxies: Int,
            warn: (String) -> Unit = DEFAULT_WARN,
        ): Boolean {
            if (!production || explicitlySet) return false
            warn(
                "TRUSTED_PROXIES is not set — defaulting to $trustedProxies proxy hop(s), which " +
                    "is correct only for the bundled reverse proxy and nothing in front of it. With an " +
                    "additional edge or CDN hop every request resolves to that edge's address and the whole " +
                    "deployment shares one rate-limit bucket. Set it to the real number of proxies between " +
                    "the internet and this gateway (0 to ignore X-Forwarded-For entirely).",
            )
            return true
        }
    }
}
