package dev.tracedown.gateway.util

import dev.tracedown.common.net.PathCanonicalizer
import io.ktor.http.HttpMethod
import io.lettuce.core.api.sync.RedisCommands
import org.slf4j.LoggerFactory

/**
 * Redis-backed sliding-window rate limiter using INCR + EXPIRE.
 *
 * Each request increments a key `rate:{tier}:{ip}:{windowKey}` where
 * windowKey is `epoch / windowSeconds`. The key auto-expires after the window.
 * If the count exceeds the limit, the request is rejected with 429.
 */
class RateLimiter(
    private val redis: () -> RedisCommands<String, String>,
    private val config: RateLimitConfig,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    data class RateLimitResult(
        val allowed: Boolean,
        val limit: Int,
        val remaining: Int,
        val retryAfterSeconds: Long,
    )

    /**
     * Counts one request by [subject] against [tier] and says whether it is
     * allowed. The subject is whatever the tier is keyed on — a client address
     * for most, a key's digest for [Tier.API]. Returns the result with
     * limit/remaining for response headers.
     */
    fun check(subject: String, tier: Tier): RateLimitResult {
        val tierConfig = tierConfig(tier)

        val nowSeconds = System.currentTimeMillis() / 1000
        val windowKey = nowSeconds / tierConfig.windowSeconds
        val key = "rate:${tier.name.lowercase()}:$subject:$windowKey"

        return try {
            val count = redis().incr(key)

            // Set TTL on first increment so the key auto-expires
            if (count == 1L) {
                redis().expire(key, tierConfig.windowSeconds)
            }

            if (count > tierConfig.maxRequests) {
                val secondsIntoWindow = nowSeconds % tierConfig.windowSeconds
                val retryAfter = tierConfig.windowSeconds - secondsIntoWindow
                RateLimitResult(
                    allowed = false,
                    limit = tierConfig.maxRequests,
                    remaining = 0,
                    retryAfterSeconds = retryAfter,
                )
            } else {
                RateLimitResult(
                    allowed = true,
                    limit = tierConfig.maxRequests,
                    remaining = (tierConfig.maxRequests - count).toInt(),
                    retryAfterSeconds = 0,
                )
            }
        } catch (e: Exception) {
            // Redis is the source of truth for counts. The general tier fails
            // OPEN (availability over a brief limiter outage), but the auth tier
            // fails CLOSED: login / password-reset / export must never become
            // unthrottled just because the limiter store is unreachable, or an
            // attacker could knock Redis over to unlock brute forcing.
            when (tier) {
                Tier.AUTH -> {
                    // ERROR, not WARN: a security control has stopped working
                    // and every login on this instance is now being refused.
                    log.error("Rate limiter Redis error on auth tier, failing closed: {}", e.message)
                    RateLimitResult(
                        allowed = false,
                        limit = tierConfig.maxRequests,
                        remaining = 0,
                        retryAfterSeconds = tierConfig.windowSeconds,
                    )
                }
                Tier.GENERAL, Tier.INTERNAL, Tier.API, Tier.API_FAILURE -> {
                    // Open, like the general tier: an agent fleet that cannot
                    // enrol or renew its certificates is an outage, and the
                    // limiter store being down is not a reason to cause one.
                    // The same goes for the key-authenticated API.
                    log.warn("Rate limiter Redis error on {} tier, failing open: {}", tier, e.message)
                    RateLimitResult(
                        allowed = true,
                        limit = tierConfig.maxRequests,
                        remaining = tierConfig.maxRequests,
                        retryAfterSeconds = 0,
                    )
                }
            }
        }
    }

    /**
     * Where [subject] stands on [tier] in the current window, without spending
     * any of it: `allowed` is false once the budget is used up. For a budget
     * that counts failures — the caller looks first, and records a failure with
     * [check] only once it knows the attempt was one. Fails open, like the
     * tiers it is used for.
     */
    fun peek(subject: String, tier: Tier): RateLimitResult {
        val tierConfig = tierConfig(tier)
        val nowSeconds = System.currentTimeMillis() / 1000
        val windowKey = nowSeconds / tierConfig.windowSeconds
        val count = try {
            redis().get("rate:${tier.name.lowercase()}:$subject:$windowKey")?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            log.warn("Rate limiter Redis error reading {} tier, failing open: {}", tier, e.message)
            0L
        }
        val spent = count >= tierConfig.maxRequests
        return RateLimitResult(
            allowed = !spent,
            limit = tierConfig.maxRequests,
            remaining = (tierConfig.maxRequests - count).coerceAtLeast(0).toInt(),
            retryAfterSeconds = if (spent) tierConfig.windowSeconds - nowSeconds % tierConfig.windowSeconds else 0,
        )
    }

    /**
     * A short-lived mark the limiter's store keeps on behalf of its callers —
     * state that has to be the same on every instance sharing the store, which
     * a counter in one process's memory is not. Best-effort: a store that is
     * away remembers nothing and [remembered] answers false.
     */
    fun remember(mark: String, seconds: Long) {
        try {
            redis().set("rate:mark:$mark", "1", io.lettuce.core.SetArgs.Builder.ex(seconds))
        } catch (e: Exception) {
            log.warn("Rate limiter Redis error writing a mark: {}", e.message)
        }
    }

    fun remembered(mark: String): Boolean =
        try {
            redis().exists("rate:mark:$mark") > 0
        } catch (e: Exception) {
            log.warn("Rate limiter Redis error reading a mark: {}", e.message)
            false
        }

    fun forget(mark: String) {
        try {
            redis().del("rate:mark:$mark")
        } catch (e: Exception) {
            log.warn("Rate limiter Redis error clearing a mark: {}", e.message)
        }
    }

    private fun tierConfig(tier: Tier): TierConfig = when (tier) {
        Tier.GENERAL -> config.general
        Tier.AUTH -> config.auth
        Tier.INTERNAL -> config.internal
        Tier.API -> config.api
        Tier.API_FAILURE -> config.apiFailure
    }

    enum class Tier {
        GENERAL,
        AUTH,

        /**
         * The key-authenticated API, metered per key rather than per address:
         * automation tends to share one — a CI fleet behind a single NAT — and
         * a per-address budget would have its runners starve each other.
         */
        API,

        /**
         * Requests to the key-authenticated API whose token named no key at
         * all, metered per address. A key that exists spends none of it; this
         * only bounds how many lookups a caller without one can make the
         * gateway perform.
         */
        API_FAILURE,

        /**
         * Endpoints that are unauthenticated by design — agent enrolment and
         * certificate renewal. They were exempt from metering entirely, on the
         * reasoning that each one proves possession of a bootstrap token or a
         * private key. That reasoning is about authentication and says nothing
         * about cost: a registration runs a bcrypt-12 verification and signs an
         * RSA CSR before it can decide the caller was a stranger. Their own
         * budget keeps that bounded without spending the login tier a fleet
         * coming up at once would otherwise exhaust.
         */
        INTERNAL,
    }
}

/**
 * Which budget a request path is metered against.
 *
 * `/ping` is exempt, because a liveness probe that a limiter can refuse is not
 * a liveness probe. The key-authenticated API answers null here too, but is
 * not exempt: it is metered per key where the key is read, rather than per
 * address (see [ApiRateLimit]). Everything else is metered here, including
 * the `/internal/` routes: they are unauthenticated by design — they carry a
 * bootstrap token or proof of possession of an agent's private key — and they
 * used to be exempt from metering as well, on reasoning that was about
 * authentication rather than cost. Each registration spends a bcrypt-12
 * verification and an RSA signature before it can tell that the caller was a
 * stranger, and on a deployment with no reverse proxy in front they are
 * published straight to the internet.
 */
fun rateLimitTierFor(rawPath: String, method: HttpMethod = HttpMethod.Get): RateLimiter.Tier? {
    // Classify on the canonical path, so `//api/v1/auth/login` (which routes to
    // the login handler all the same) is metered on the strict AUTH budget
    // rather than the fail-open GENERAL one it lands on when the raw URI is read
    // verbatim. A path that plays games with dot-segments cannot be canonicalized
    // and is put on the strictest bucket rather than being handed the lenient one.
    val path = PathCanonicalizer.canonicalize(rawPath) ?: return RateLimiter.Tier.AUTH
    return when {
    path == "/ping" -> null
    // Metered where the key is read, per key — see ApiRateLimit.
    ApiNamespace.isPublic(path) -> null
    path.startsWith("/internal/") -> RateLimiter.Tier.INTERNAL
    // The data export fans out over many per-user queries, so it shares the
    // stricter auth tier rather than the general one.
    // Email change checks a password and mails an address of the caller's
    // choosing; its confirm link is a credential, like a reset link.
    path.startsWith("/api/v1/auth/login") ||
        path.startsWith("/api/v1/auth/password-reset") ||
        path.startsWith("/api/v1/me/email") ||
        path.startsWith("/api/v1/me/export") -> RateLimiter.Tier.AUTH
    // Minting a key re-checks the password, so a stolen session could guess
    // at it here; the login budget is the one for guessing passwords.
    method == HttpMethod.Post && path == "/api/v1/me/api-keys" -> RateLimiter.Tier.AUTH
        else -> RateLimiter.Tier.GENERAL
    }
}

data class TierConfig(
    val maxRequests: Int,
    val windowSeconds: Long,
)

/**
 * Derives the real client IP for rate-limit keying, resistant to a spoofed
 * `X-Forwarded-For`.
 *
 * The hop list, from the gateway outward, is `[directPeer] + XFF.reversed()`:
 * each trusted proxy appended the address it received from, so the genuine
 * client sits exactly [trustedProxies] hops out. Anything a client injects into
 * XFF lands further left than that position and is ignored.
 *
 * [trustedProxies] = 0 keys on the direct peer and ignores XFF entirely. If the
 * chain is shorter than expected (misconfig or a client that bypassed the
 * proxy), it falls back to the direct peer rather than trusting a claimed hop.
 */
fun resolveClientIp(xff: String?, directPeer: String, trustedProxies: Int): String {
    if (trustedProxies <= 0) return directPeer
    val forwarded = xff
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    val hops = listOf(directPeer) + forwarded.asReversed()
    return hops.getOrNull(trustedProxies) ?: directPeer
}

data class RateLimitConfig(
    val enabled: Boolean,
    val general: TierConfig,
    val auth: TierConfig,
    /** Budget for the unauthenticated-by-design agent enrolment endpoints. */
    val internal: TierConfig = TierConfig(maxRequests = 60, windowSeconds = 60),
    /** Budget of one API key. */
    val api: TierConfig = TierConfig(maxRequests = 300, windowSeconds = 60),
    /** Budget of one address for requests to the key-authenticated API that carried no usable key. */
    val apiFailure: TierConfig = TierConfig(maxRequests = 30, windowSeconds = 60),
    /**
     * Number of trusted reverse proxies in front of the gateway. The client IP
     * used for rate-limit keys is taken this many hops back from the TCP peer,
     * so a client-supplied `X-Forwarded-For` cannot spoof the key. 0 disables
     * XFF entirely (key on the direct peer). Default 1 (the bundled nginx).
     */
    val trustedProxies: Int,
) {
    companion object {
        /**
         * A configured window, never below one second. The window is a divisor:
         * a zero from a mistyped variable would turn every metered request into
         * an arithmetic error instead of a limit.
         */
        private fun window(configured: Long?): Long = (configured ?: 60L).coerceAtLeast(1)

        fun load(config: io.ktor.server.config.ApplicationConfig): RateLimitConfig {
            return RateLimitConfig(
                enabled = config.propertyOrNull("rateLimit.enabled")
                    ?.getString()?.toBoolean() ?: true,
                trustedProxies = config.propertyOrNull("rateLimit.trustedProxies")
                    ?.getString()?.toIntOrNull()?.coerceAtLeast(0) ?: 1,
                general = TierConfig(
                    maxRequests = config.propertyOrNull("rateLimit.general.maxRequests")
                        ?.getString()?.toInt() ?: 120,
                    windowSeconds = window(config.propertyOrNull("rateLimit.general.windowSeconds")
                        ?.getString()?.toLong()),
                ),
                auth = TierConfig(
                    maxRequests = config.propertyOrNull("rateLimit.auth.maxRequests")
                        ?.getString()?.toInt() ?: 15,
                    windowSeconds = window(config.propertyOrNull("rateLimit.auth.windowSeconds")
                        ?.getString()?.toLong()),
                ),
                // Generous next to the auth tier on purpose: a whole fleet may
                // bootstrap in the same minute, and one agent retrying a failed
                // renewal must not lock its neighbours out.
                internal = TierConfig(
                    maxRequests = config.propertyOrNull("rateLimit.internal.maxRequests")
                        ?.getString()?.toInt() ?: 60,
                    windowSeconds = window(config.propertyOrNull("rateLimit.internal.windowSeconds")
                        ?.getString()?.toLong()),
                ),
                api = TierConfig(
                    maxRequests = config.propertyOrNull("rateLimit.api.maxRequests")
                        ?.getString()?.toInt() ?: 300,
                    windowSeconds = window(config.propertyOrNull("rateLimit.api.windowSeconds")
                        ?.getString()?.toLong()),
                ),
                apiFailure = TierConfig(
                    maxRequests = config.propertyOrNull("rateLimit.apiFailure.maxRequests")
                        ?.getString()?.toInt() ?: 30,
                    windowSeconds = window(config.propertyOrNull("rateLimit.apiFailure.windowSeconds")
                        ?.getString()?.toLong()),
                ),
            )
        }
    }
}
