package dev.tracedown.gateway.util

/**
 * Metering for the key-authenticated API.
 *
 * It cannot ride the per-address plugin the rest of the API uses, because what
 * it meters is a key, whatever address it came from — automation shares
 * addresses, and a per-address budget would have a CI fleet behind one NAT
 * spend each other's. Three things are kept, all in the limiter's store so
 * every gateway instance sees the same:
 *
 *  - a budget per presented key, by its digest, spent **before** the key is
 *    looked up: a request that is going to be refused costs budget too, and one
 *    over budget costs the database nothing;
 *  - a count per address of tokens that named no key at all, which bounds
 *    how many lookups a caller without a key can cause (a key that exists but
 *    is unusable is bounded by its own budget and does not count — see
 *    `ApiKeyAuth`);
 *  - a mark on each key that recently authenticated, which exempts it from the
 *    address count above — so one runner with a stale key cannot shut out the
 *    working keys behind the same address.
 *
 * Wired once at startup; until then — and whenever rate limiting is switched
 * off — every check passes.
 */
object ApiRateLimit {

    /** How long a key that authenticated stays exempt from its address's failure count. */
    private const val KNOWN_GOOD_SECONDS = 300L

    @Volatile
    private var limiter: RateLimiter? = null

    /** Hands over the gateway's limiter, or null when rate limiting is disabled. */
    internal fun init(limiter: RateLimiter?) {
        this.limiter = limiter
    }

    /** Spends one request of the budget of the key with this [digest]. Null when rate limiting is off. */
    fun spend(digest: String): RateLimiter.RateLimitResult? =
        limiter?.check(digest, RateLimiter.Tier.API)

    /** Where [ip] stands on its limit on unknown tokens. Null when rate limiting is off. */
    fun unknownTokens(ip: String): RateLimiter.RateLimitResult? =
        limiter?.peek(ip, RateLimiter.Tier.API_FAILURE)

    /** Counts one request from [ip] whose token named no key. */
    fun recordUnknownToken(ip: String): RateLimiter.RateLimitResult? =
        limiter?.check(ip, RateLimiter.Tier.API_FAILURE)

    /** Marks the key with this [digest] as having authenticated, for every instance to see. */
    fun markGood(digest: String) {
        limiter?.remember("good:$digest", KNOWN_GOOD_SECONDS)
    }

    /** Whether the key with this [digest] authenticated recently, on any instance. */
    fun isGood(digest: String): Boolean = limiter?.remembered("good:$digest") ?: false

    /** Drops the mark: the key stopped working. */
    fun forgetGood(digest: String) {
        limiter?.forget("good:$digest")
    }
}
