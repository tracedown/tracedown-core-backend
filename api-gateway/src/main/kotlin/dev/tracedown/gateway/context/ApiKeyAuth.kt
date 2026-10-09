package dev.tracedown.gateway.context

import dev.tracedown.common.auth.ApiKeyAuthenticator
import dev.tracedown.common.auth.ApiKeyResult
import dev.tracedown.common.auth.CachedPermissions
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.net.PathCanonicalizer
import dev.tracedown.gateway.routes.publicapi.PublicApi
import dev.tracedown.gateway.routes.publicapi.v1.EVENTS_PATH
import dev.tracedown.gateway.util.ApiRateLimit
import dev.tracedown.gateway.util.RateLimiter
import dev.tracedown.gateway.util.TooManyRequestsException
import dev.tracedown.gateway.util.UnauthorizedException
import dev.tracedown.gateway.util.clientIp
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a presented API key into the caller it acts as.
 *
 * Whether the key is usable is decided by the shared [ApiKeyAuthenticator];
 * this adds what belongs to the gateway — its error codes, the rate budgets
 * that stand in front of the lookup, and the `last_used_at` stamp.
 *
 * The order is the point. Both budgets are consulted **before** the database
 * is: first the address's limit on unknown tokens (skipped for a key
 * marked as having worked), then the key's own budget. Only a request that
 * passes both is looked up, so neither a stranger nor a key over its budget —
 * nor one that is about to be refused for any later reason — gets database
 * work for free.
 *
 * Only a token that names no key at all counts against its address. A key
 * that exists but is revoked, expired or without a user is somebody's
 * credential gone stale — a runner nobody updated — and is bounded by its own
 * budget; counting it against the address would let that one runner shut out
 * the working keys behind the same NAT.
 */
internal object ApiKeyAuth {

    private val log = LoggerFactory.getLogger(javaClass)

    /** How often, at most, one key's stamp or mark is rewritten. */
    private const val DEBOUNCE_SECONDS = 60L

    /** Past this many entries a debounce map is simply emptied; the cost is one early rewrite each. */
    private const val DEBOUNCE_MAP_LIMIT = 10_000

    /** When each key's `last_used_at` was last written, by key id. */
    private val lastUsedTouch = ConcurrentHashMap<UUID, Long>()

    /**
     * When each key was last marked as working, by digest. A write debounce
     * for the shared mark, and a cheap first look before asking the store
     * whether the mark is there.
     */
    private val markedGood = ConcurrentHashMap<String, Long>()

    /** The permissions the key's user held when the key was let in. */
    private val permissionsKey = AttributeKey<CachedPermissions>("ApiKeyPermissions")

    /** What [permissionsKey] holds for this call, if a key was let in. */
    fun permissionsOf(call: ApplicationCall): CachedPermissions? = call.attributes.getOrNull(permissionsKey)

    /** The presented key's digest, for spending its budget later in the call. */
    private val digestKey = AttributeKey<String>("ApiKeyDigest")

    /**
     * Spends one more unit of the calling key's request budget, for a call
     * that does more than one request's worth of work (the event feed). False,
     * with `Retry-After` set, when the budget is spent; true when there is no
     * key or no limiter.
     */
    fun spendAgain(call: ApplicationCall): Boolean {
        val digest = call.attributes.getOrNull(digestKey) ?: return true
        val budget = ApiRateLimit.spend(digest) ?: return true
        if (budget.allowed) return true
        if (!call.response.isCommitted) call.response.headers.append(HttpHeaders.RetryAfter, budget.retryAfterSeconds.toString())
        return false
    }

    fun authenticate(call: ApplicationCall, token: String): ResolvedCaller {
        val digest = TokenHasher.sha256Hex(token)
        call.attributes.put(digestKey, digest)
        val clientIp = call.clientIp()
        val now = Instant.now().epochSecond

        // An address that keeps sending unknown tokens is refused here, unless
        // this particular key has been seen to work: addresses are shared, and
        // the limit stands between the gateway and strangers, not between
        // it and keys it knows.
        var admittedByMark = false
        ApiRateLimit.unknownTokens(clientIp)?.takeIf { !it.allowed }?.let { spent ->
            admittedByMark = recentlyMarked(digest, now) || ApiRateLimit.isGood(digest)
            if (!admittedByMark) refuseAddress(call, spent)
        }

        // The event feed spends the budget itself (`spendAgain`): a read that
        // waits is one long request, and charging it here as well would make
        // a long-poll that returns the moment something happens cost in
        // proportion to how busy the organization is. What it does charge is
        // every answer given without waiting and every look after the first.
        // A key with nothing left is still refused here, before any lookup —
        // only without spending.
        if (isEventFeed(call)) {
            ApiRateLimit.peek(digest)?.takeIf { !it.allowed }?.let { budget ->
                call.response.headers.append(HttpHeaders.RetryAfter, budget.retryAfterSeconds.toString())
                throw TooManyRequestsException()
            }
        } else ApiRateLimit.spend(digest)?.let { budget ->
            call.response.headers.append("X-RateLimit-Limit", budget.limit.toString())
            call.response.headers.append("X-RateLimit-Remaining", budget.remaining.toString())
            if (!budget.allowed) {
                call.response.headers.append(HttpHeaders.RetryAfter, budget.retryAfterSeconds.toString())
                throw TooManyRequestsException()
            }
        }

        val ctx = when (val result = ApiKeyAuthenticator.authenticateDigest(digest)) {
            is ApiKeyResult.Valid -> result.context
            is ApiKeyResult.Invalid -> {
                // A key that stopped working loses its exemption with it — on
                // every instance, if the mark is what admitted it here.
                if (markedGood.remove(digest) != null || admittedByMark || result.reason != ApiKeyResult.Reason.NOT_FOUND) {
                    ApiRateLimit.forgetGood(digest)
                }
                if (result.reason == ApiKeyResult.Reason.NOT_FOUND) {
                    val unknown = ApiRateLimit.recordUnknownToken(clientIp)
                    if (unknown != null && unknown.allowed && unknown.remaining == 0) {
                        // Said once per window: the request that used the last of it.
                        log.warn(
                            "Address {} has sent {} unknown API keys; further unknown keys from it are refused unread",
                            clientIp, unknown.limit,
                        )
                    }
                }
                // Named by its digest, never by any part of the token itself.
                log.debug("Refused API key {}…: {}", digest.take(12), result.reason)
                throw UnauthorizedException(apiKeyRefusal(result.reason))
            }
        }

        ctx.permissions?.let { call.attributes.put(permissionsKey, it) }
        return ResolvedCaller(
            principal = AuthPrincipal(
                userId = ctx.userId,
                email = ctx.email,
                organizationId = ctx.organizationId,
                credential = Credential.ApiKey(ctx.keyId, ctx.access),
            ),
            totpEnabled = ctx.totpEnabled,
            // The stamp and the mark say "this key is in use", so they wait for
            // the one check that is still to come (TOTP enrolment): a key that
            // is refused there has not been used.
            onAdmitted = {
                if (debounced(markedGood, digest, now)) ApiRateLimit.markGood(digest)
                touchLastUsed(ctx.keyId, now)
            },
        )
    }

    /**
     * Refuses a request to the key-authenticated API that carries no key at
     * all — no `Authorization` header, or a blank bearer — and counts it
     * against its address exactly as a token that names no key is counted. A
     * flood without credentials costs no lookup either way, but uncounted it
     * would never meet the address's limit; counted, it meets
     * `too_many_unknown_keys` as a flood of made-up keys does. Throws
     * [refusal] while the address is within its limit.
     */
    fun refuseWithoutKey(call: ApplicationCall, refusal: UnauthorizedException): Nothing {
        val clientIp = call.clientIp()
        ApiRateLimit.unknownTokens(clientIp)?.takeIf { !it.allowed }?.let { refuseAddress(call, it) }
        ApiRateLimit.recordUnknownToken(clientIp)
        throw refusal
    }

    private fun refuseAddress(call: ApplicationCall, spent: RateLimiter.RateLimitResult): Nothing {
        // Retry-After alone: the X-RateLimit headers describe a key's budget,
        // and this refusal is not about the key.
        call.response.headers.append(HttpHeaders.RetryAfter, spent.retryAfterSeconds.toString())
        throw TooManyRequestsException(ErrorCodes.TOO_MANY_UNKNOWN_KEYS)
    }

    private fun isEventFeed(call: ApplicationCall): Boolean =
        PathCanonicalizer.canonicalize(call.request.local.uri) == PublicApi.V1 + EVENTS_PATH

    private fun recentlyMarked(digest: String, now: Long): Boolean =
        markedGood[digest]?.let { now - it < DEBOUNCE_SECONDS } ?: false

    /** True when [key] is due: not seen in the last [DEBOUNCE_SECONDS]. Records the visit when it is. */
    private fun <K : Any> debounced(seen: ConcurrentHashMap<K, Long>, key: K, now: Long): Boolean {
        if (seen.size >= DEBOUNCE_MAP_LIMIT) seen.clear()
        var due = false
        seen.compute(key) { _, last ->
            if (last == null || now - last >= DEBOUNCE_SECONDS) {
                due = true
                now
            } else {
                last
            }
        }
        return due
    }

    /** Stamps `last_used_at`, at most once a minute. Best-effort, and never inside somebody else's transaction. */
    private fun touchLastUsed(keyId: UUID, now: Long) {
        // Asked from inside an open transaction, this would join it: a failure
        // here would then poison the caller's work, and a success would hold
        // the key's row for as long as the caller stays open. Not recorded as
        // done, so the next request outside a transaction writes it.
        if (TransactionManager.currentOrNull() != null) return
        val last = lastUsedTouch[keyId]
        if (last != null && now - last < DEBOUNCE_SECONDS) return

        try {
            transaction {
                ApiKeys.update({ ApiKeys.id eq keyId }) { it[lastUsedAt] = Instant.now() }
            }
            if (lastUsedTouch.size >= DEBOUNCE_MAP_LIMIT) lastUsedTouch.clear()
            lastUsedTouch[keyId] = now
        } catch (e: Exception) {
            // Recorded as not done, so the next request tries again.
            log.debug("Could not stamp last_used_at for key {}: {}", keyId, e.message)
        }
    }
}

/** The code a key refused for [reason] is answered with. */
internal fun apiKeyRefusal(reason: ApiKeyResult.Reason): String = when (reason) {
    ApiKeyResult.Reason.NOT_FOUND -> ErrorCodes.INVALID_API_KEY
    ApiKeyResult.Reason.REVOKED -> ErrorCodes.API_KEY_REVOKED
    ApiKeyResult.Reason.EXPIRED -> ErrorCodes.API_KEY_EXPIRED
    ApiKeyResult.Reason.OWNER_GONE,
    ApiKeyResult.Reason.OWNER_INACTIVE,
    ApiKeyResult.Reason.NOT_MEMBER -> ErrorCodes.API_KEY_OWNER_INACTIVE
}
