package dev.tracedown.gateway.util

import dev.tracedown.common.errors.ErrorCodes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.parseQueryString
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readRemaining
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import kotlinx.coroutines.Job
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

/**
 * `Idempotency-Key` on the key-authenticated API's POSTs: a request that may
 * be sent twice — a retry after a timeout, a pipeline step run again — and
 * must never take effect twice.
 *
 * The first request with a key holds it, per API key, under its
 * *fingerprint* (method, path, query parameters in a fixed order, content
 * type, a hash of the body). What happens to a repeat depends on how the
 * first one ended:
 *
 *  - **succeeded (2xx), answer kept** — the answer is replayed for
 *    [TTL_SECONDS], with `Idempotent-Replayed: true`, and nothing runs again.
 *    The replay is the stored status, type and body; headers the handler set
 *    are not kept. The caller's membership is checked again first.
 *  - **refused or failed (4xx, 5xx)** — nothing is kept: a refusal did
 *    nothing, and a repeat runs again (and may succeed).
 *  - **outcome unknown** — 409 `idempotency_outcome_unknown` for
 *    [TTL_SECONDS]. Either the call was cut off after its handler started (the
 *    client went away), so whatever it did may have been done; or it
 *    succeeded with an answer too large to keep ([MAX_STORED_BODY_BYTES]),
 *    in which case that answer said so with `Idempotency-Status: not-kept`.
 *    It never runs a second time.
 *  - **still being answered** — 409 `idempotency_in_progress`, with
 *    `Retry-After`; once it has been answered for longer than
 *    [IN_FLIGHT_TTL_SECONDS], 409 `idempotency_outcome_unknown` instead. The
 *    marker stands a day, so a request that never came back is never run
 *    again.
 *
 * The same key with a different fingerprint is 422 `idempotency_key_reused`.
 *
 * Every record written down — a kept answer or an unknown outcome — is
 * charged, at its stored size, to a byte budget per organization
 * ([orgBudgetBytes]), whose window is fixed at [TTL_SECONDS] from its first
 * charge. Once it is spent, a request carrying a new key is refused before it
 * runs — 429 `idempotency_limit_reached`, with `Retry-After` (when the window
 * ends) — rather than run without the promise. A request carrying no key is
 * never affected.
 *
 * A replay re-checks that the caller is still a member of the organization;
 * it does not re-run the route's own permission check — the answer is what
 * the caller was given when they could ask.
 *
 * Kept in Redis A, shared by every gateway replica. When it does not answer,
 * a request carrying a key is refused, 503 `idempotency_unavailable` with
 * `Retry-After`, and its key is let go if it had been taken. If it stops
 * answering after a handler has run, nothing can be written down: the
 * marker stands, and a repeat is told the outcome is unknown.
 */
object Idempotency {

    const val HEADER = "Idempotency-Key"
    const val REPLAYED_HEADER = "Idempotent-Replayed"
    const val STATUS_HEADER = "Idempotency-Status"

    /** How long a kept answer, or an unknown outcome, is remembered. */
    const val TTL_SECONDS = 24 * 3600L

    /**
     * How long a request may be answered before a repeat stops waiting for it:
     * past this its marker still stands (for [TTL_SECONDS]), and a repeat is
     * told the outcome is unknown — never run again.
     */
    const val IN_FLIGHT_TTL_SECONDS = 300L

    /** The longest key accepted. */
    const val MAX_KEY_LENGTH = 128

    /** The largest answer body kept for a replay. */
    const val MAX_STORED_BODY_BYTES = 256 * 1024

    /** The default per-organization byte budget for kept answers, per [TTL_SECONDS]. */
    const val DEFAULT_ORG_BUDGET_BYTES = 64L * 1024 * 1024

    private const val IN_PROGRESS_RETRY_SECONDS = 1
    private const val UNAVAILABLE_RETRY_SECONDS = 5

    private val log = LoggerFactory.getLogger(Idempotency::class.java)

    private var redisProvider: (() -> RedisCommands<String, String>)? = null
    private var maxBodyBytes: Long = AppConfig.DEFAULT_MAX_REQUEST_BODY_BYTES

    /** Bytes of kept answers an organization may have per [TTL_SECONDS]. */
    @Volatile
    internal var orgBudgetBytes: Long = DEFAULT_ORG_BUDGET_BYTES

    /** Sets the store, the request-body cap the fingerprint's read holds to, and the budget. Call once at startup. */
    fun init(redis: (() -> RedisCommands<String, String>)?, maxBodyBytes: Long, orgBudgetBytes: Long = DEFAULT_ORG_BUDGET_BYTES) {
        this.redisProvider = redis
        this.maxBodyBytes = maxBodyBytes
        this.orgBudgetBytes = orgBudgetBytes
    }

    /** A POST into the key-authenticated API carrying an `Idempotency-Key`: its body is kept ([RequestBodyCache]). */
    internal fun carriesKey(call: ApplicationCall): Boolean =
        call.request.httpMethod == HttpMethod.Post &&
            call.request.headers[HEADER] != null &&
            ApiNamespace.isPublicUri(call.request.local.uri)

    /** A request being answered under a key. */
    private class InFlight(val storeKey: String, val fingerprint: String, val token: String, val orgId: UUID)

    private val inFlightKey = AttributeKey<InFlight>("IdempotencyInFlight")

    /**
     * Starts an idempotent request, before its handler: does nothing when
     * [call] carries no key; answers [call] itself with the kept answer when
     * there is one (the caller then stops); otherwise takes the key for this
     * call until it is decided ([capture], [cancelled]). Throws the refusals:
     * 400 for a malformed key, 409, 422, 429, 503.
     */
    internal suspend fun begin(call: ApplicationCall, apiKeyId: UUID, orgId: UUID, userId: UUID, canonicalPath: String) {
        val key = call.request.headers[HEADER] ?: return
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH || key.any { it !in ' '..'~' }) {
            throw fieldError(HEADER) { put("max", MAX_KEY_LENGTH) }
        }
        val redis = redisProvider ?: throw unavailable(call)
        val fingerprint = fingerprint(call, canonicalPath)
        val storeKey = "idempotency:$apiKeyId:${sha256(key.toByteArray())}"
        val token = UUID.randomUUID().toString()
        val marker = buildJsonObject {
            put("state", "pending")
            put("fp", fingerprint)
            put("token", token)
            put("startedAt", System.currentTimeMillis() / 1000)
        }.toString()

        // Twice at most: a record that lapses between the two calls below
        // leaves the key free, and the second try takes it.
        repeat(2) {
            val commands = try {
                redis()
            } catch (e: Exception) {
                throw unavailable(call)
            }
            val existing = try {
                commands.get(storeKey)
            } catch (e: Exception) {
                log.warn("idempotency store unavailable: {}", e.message)
                throw unavailable(call)
            }
            if (existing == null) {
                // A new key: refused before anything runs once the
                // organization's budget for kept answers is spent.
                budgetRetryAfter(commands, orgId)?.let { seconds ->
                    call.response.header(HttpHeaders.RetryAfter, seconds.toString())
                    throw ApiException(HttpStatusCode.TooManyRequests, ErrorCodes.IDEMPOTENCY_LIMIT_REACHED)
                }
                val taken = try {
                    // A day, not the in-flight bound: a marker that lapsed
                    // while its request was still running would let a repeat
                    // run it a second time.
                    commands.set(storeKey, marker, SetArgs().nx().ex(TTL_SECONDS)) != null
                } catch (e: Exception) {
                    log.warn("idempotency store unavailable: {}", e.message)
                    // It may have been taken before the error: let it go.
                    runCatching { release(commands, storeKey, token) }
                    throw unavailable(call)
                }
                if (taken) {
                    call.attributes.put(inFlightKey, InFlight(storeKey, fingerprint, token, orgId))
                    return
                }
                return@repeat
            }
            val held = runCatching { Json.parseToJsonElement(existing) as JsonObject }.getOrNull() ?: return@repeat
            if (held["fp"]?.jsonPrimitive?.contentOrNull != fingerprint) {
                throw ApiException(HttpStatusCode.UnprocessableEntity, ErrorCodes.IDEMPOTENCY_KEY_REUSED)
            }
            when (held["state"]?.jsonPrimitive?.contentOrNull) {
                "done" -> {
                    // The caller may have lost the right to see what it was
                    // given: asked again, as the handler would ask.
                    transaction { requireCachedPermissions(orgId, userId) }
                    replay(call, held)
                    return
                }
                "unknown" -> throw ConflictException(ErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
                else -> {
                    // Still marked as running, past the in-flight bound: it
                    // never came back, and may have done its work.
                    val startedAt = held["startedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                    if (System.currentTimeMillis() / 1000 - startedAt > IN_FLIGHT_TTL_SECONDS) {
                        throw ConflictException(ErrorCodes.IDEMPOTENCY_OUTCOME_UNKNOWN)
                    }
                    throw inProgress(call)
                }
            }
        }
        throw inProgress(call)
    }

    /**
     * Decides the key of [call], an idempotent request being answered with
     * [content] — before the engine sends a byte of it, so a repeat that
     * arrives the moment the answer does is replayed, and an answer the engine
     * then fails to deliver is still the answer. A success is kept (or, too
     * large to keep, marked [STATUS_HEADER] `not-kept` and remembered as an
     * unknown outcome); a refusal or failure lets the key go — except the
     * error page of a call cut off under its handler, which is an unknown
     * outcome. Never throws: the answer goes out either way.
     */
    internal fun capture(call: ApplicationCall, content: OutgoingContent) {
        val inFlight = call.attributes.getOrNull(inFlightKey) ?: return
        call.attributes.remove(inFlightKey)
        val redis = redisProvider ?: return
        val status = content.status ?: call.response.status() ?: HttpStatusCode.OK
        val cutOff = call.coroutineContext[Job]?.isCancelled == true
        try {
            val commands = redis()
            when {
                status.value in 200..299 -> {
                    val body = when (content) {
                        is OutgoingContent.ByteArrayContent -> content.bytes()
                        is OutgoingContent.NoContent -> ByteArray(0)
                        else -> null
                    }
                    if (body == null || body.size > MAX_STORED_BODY_BYTES) {
                        call.response.header(STATUS_HEADER, "not-kept")
                        decide(commands, inFlight, record("unknown", inFlight.fingerprint, null))
                        return
                    }
                    val answer = buildJsonObject {
                        put("state", "done")
                        put("fp", inFlight.fingerprint)
                        put("status", status.value)
                        content.contentType?.let { put("type", it.toString()) }
                        put("body", Base64.getEncoder().encodeToString(body))
                    }
                    decide(commands, inFlight, answer.toString())
                }
                // The error page of a call whose handler was cut off: what it
                // did may have been done.
                cutOff -> decide(commands, inFlight, record("unknown", inFlight.fingerprint, null))
                else -> release(commands, inFlight.storeKey, inFlight.token)
            }
        } catch (e: Exception) {
            log.warn("could not decide idempotency key {}: {}", inFlight.storeKey, e.message)
        }
    }

    /**
     * [call] was cut off with no answer at all — its client went away while
     * its handler ran. What the handler did may have been done: remembered as
     * an unknown outcome, never run again.
     */
    internal fun cancelled(call: ApplicationCall) {
        val inFlight = call.attributes.getOrNull(inFlightKey) ?: return
        call.attributes.remove(inFlightKey)
        val redis = redisProvider ?: return
        try {
            decide(redis(), inFlight, record("unknown", inFlight.fingerprint, null))
        } catch (e: Exception) {
            log.warn("could not decide idempotency key {}: {}", inFlight.storeKey, e.message)
        }
    }

    private fun record(state: String, fingerprint: String, token: String?): String = buildJsonObject {
        put("state", state)
        put("fp", fingerprint)
        token?.let { put("token", it) }
    }.toString()

    /**
     * Replaces this request's own marker with [value], for [TTL_SECONDS] —
     * never another request's — and charges the record's size to the
     * organization's budget in the same step, only when it was written.
     */
    private fun decide(commands: RedisCommands<String, String>, inFlight: InFlight, value: String) {
        commands.eval<Long>(
            DECIDE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(inFlight.storeKey, budgetKey(inFlight.orgId)),
            inFlight.token, value, TTL_SECONDS.toString(),
        )
    }

    /** Lets the key go — only while it is still this request's marker. */
    private fun release(commands: RedisCommands<String, String>, storeKey: String, token: String) {
        commands.eval<Long>(RELEASE_SCRIPT, ScriptOutputType.INTEGER, arrayOf(storeKey), token)
    }

    private fun budgetKey(orgId: UUID) = "idempotency_bytes:$orgId"

    /** Seconds until [orgId]'s budget comes back, when it is spent; null while there is some left. */
    private fun budgetRetryAfter(commands: RedisCommands<String, String>, orgId: UUID): Long? {
        val spent = try {
            commands.get(budgetKey(orgId))?.toLongOrNull() ?: return null
        } catch (e: Exception) {
            return null
        }
        if (spent < orgBudgetBytes) return null
        return runCatching { commands.ttl(budgetKey(orgId)) }.getOrNull()?.takeIf { it > 0 } ?: TTL_SECONDS
    }

    private suspend fun replay(call: ApplicationCall, record: JsonObject) {
        val status = HttpStatusCode.fromValue(record["status"]?.jsonPrimitive?.intOrNull ?: 200)
        val type = record["type"]?.jsonPrimitive?.contentOrNull?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        val body = record["body"]?.jsonPrimitive?.contentOrNull?.let { Base64.getDecoder().decode(it) } ?: ByteArray(0)
        call.response.header(REPLAYED_HEADER, "true")
        call.respondBytes(body, type, status)
    }

    /**
     * The request's fingerprint: method, path, query parameters (decoded and
     * sorted, so their order does not matter), the body's content type, and a
     * hash of the body. The body is read through the request-body cap, and
     * kept for the handler's own read ([RequestBodyCache]).
     */
    private suspend fun fingerprint(call: ApplicationCall, canonicalPath: String): String {
        val bytes = call.receiveChannel().readRemaining(maxBodyBytes + 1).readByteArray()
        val query = parseQueryString(call.request.local.uri.substringAfter('?', ""))
            .entries().flatMap { (name, values) -> values.map { "$name=$it" } }
            .sorted().joinToString("&")
        val type = call.request.headers[HttpHeaders.ContentType].orEmpty()
        return sha256("${call.request.local.method.value}\n$canonicalPath\n$query\n$type\n${sha256(bytes)}".toByteArray())
    }

    private fun inProgress(call: ApplicationCall): ApiException {
        call.response.header(HttpHeaders.RetryAfter, IN_PROGRESS_RETRY_SECONDS.toString())
        return ConflictException(ErrorCodes.IDEMPOTENCY_IN_PROGRESS)
    }

    private fun unavailable(call: ApplicationCall): ApiException {
        call.response.header(HttpHeaders.RetryAfter, UNAVAILABLE_RETRY_SECONDS.toString())
        return ApiException(HttpStatusCode.ServiceUnavailable, ErrorCodes.IDEMPOTENCY_UNAVAILABLE)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** KEYS[1] = the key, ARGV[1] = this request's token: delete it only while it is still this request's marker. */
    private val RELEASE_SCRIPT = """
        local v = redis.call('get', KEYS[1])
        if v and cjson.decode(v)['token'] == ARGV[1] then
            return redis.call('del', KEYS[1])
        end
        return 0
    """.trimIndent()

    /**
     * KEYS[1] = the key, KEYS[2] = the organization's budget counter; ARGV =
     * token, the new value, the TTL. Replaces only this request's own marker,
     * and only then charges the value's size to the budget, whose window
     * starts with its first charge and does not move after.
     */
    private val DECIDE_SCRIPT = """
        local v = redis.call('get', KEYS[1])
        if v and cjson.decode(v)['token'] == ARGV[1] then
            redis.call('set', KEYS[1], ARGV[2], 'EX', ARGV[3])
            redis.call('incrby', KEYS[2], string.len(ARGV[2]))
            if redis.call('ttl', KEYS[2]) < 0 then
                redis.call('expire', KEYS[2], ARGV[3])
            end
            return 1
        end
        return 0
    """.trimIndent()
}
