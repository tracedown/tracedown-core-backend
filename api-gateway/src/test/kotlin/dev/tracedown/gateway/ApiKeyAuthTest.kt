package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.audit.AuditService
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.EmailChangeRequests
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.AccountLifecycle
import dev.tracedown.common.pfs.PfsParams
import dev.tracedown.gateway.controllers.apikeys.ApiKeyController
import dev.tracedown.gateway.controllers.audit.AuditController
import dev.tracedown.gateway.controllers.auth.TotpUtil
import dev.tracedown.gateway.util.SystemLimitsConfig
import dev.tracedown.gateway.data.apikeys.CreateApiKeyRequest
import dev.tracedown.gateway.routes.publicapi.PublicApi
import dev.tracedown.gateway.routes.publicapi.apiCaller
import dev.tracedown.gateway.routes.v1.auth.requireAuth
import dev.tracedown.gateway.data.apikeys.ApiKeySummary
import dev.tracedown.gateway.util.ApiRateLimit
import dev.tracedown.gateway.util.ForbiddenException
import io.lettuce.core.RedisClient
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.response.respond
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.head
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * API keys as a working credential: which requests one authenticates, as whom,
 * and where it stops.
 *
 * The key-authenticated API has one endpoint of its own at this point, so the
 * tests that need a permissioned read or a write mount them through the same
 * seam a host uses ([PublicApi.routes]). That is deliberate double duty: what
 * is under test is what the tree enforces around a handler, and a route added
 * from outside has to get exactly that.
 *
 * Every test sends from an address of its own (see [address]), so the
 * per-address failure count of one cannot refuse another.
 */
@Testcontainers
class ApiKeyAuthTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_api_key_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val PASSWORD = "ApiKeyTest123!"

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private val aesKeyBytes = AES_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        /** The cap this suite runs under — small enough to reach. */
        private const val MAX_KEYS_PER_USER = 3

        /** One key's request budget, and one address's limit on unknown keys. */
        private const val KEY_BUDGET = 20
        private const val FAILURE_BUDGET = 25

        /**
         * One window for the whole run. The limiter's windows are aligned to the
         * epoch, so any shorter one can end in the middle of a test; ten years
         * cannot.
         */
        private const val ONE_WINDOW = "315360000"

        /** The one account the creation hook refuses. */
        private val refusedCreator: UUID = UUID.randomUUID()

        /** The one account the stand-in host gate refuses. */
        private val gatedUser: UUID = UUID.randomUUID()

        /**
         * Accounts whose creations are held up after their transaction has
         * taken its snapshot — so that concurrent ones are guaranteed to have
         * all looked before any of them writes. See the cap test.
         */
        private val racers: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** What the last `api-key.create` after-hook was handed. */
        @Volatile
        private var lastCreated: ApiKeySummary? = null

        /** Hands each test its own client address, from two documentation ranges. */
        private val addresses = AtomicInteger(0)

        private fun nextAddress(): String {
            val n = addresses.incrementAndGet()
            return if (n < 250) "198.51.100.$n" else "203.0.113.${n - 249}"
        }

        private suspend fun act(call: ApplicationCall) {
            val caller = call.apiCaller
            transaction { AuditService.log(caller.orgId, caller.userId, "test.act", "test", caller.keyId.toString()) }
            call.respond(mapOf("ok" to true))
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            // What a host would register, before the module is installed.
            PublicApi.clearAll()
            PublicApi.routes {
                // A read behind a real permission check: the audit log needs
                // `settings` read, through the controller the dashboard uses.
                get("/test/audit") {
                    val caller = call.apiCaller
                    call.respond(AuditController.list(caller.orgId, caller.userId, PfsParams(page = 1, pageSize = 50)))
                }
                // The same audited action under every method, so the read-only
                // ceiling can be asked about each. The entry names the key the
                // handler saw, so a row can be checked against itself.
                post("/test/act") { act(call) }
                put("/test/act") { act(call) }
                patch("/test/act") { act(call) }
                delete("/test/act") { act(call) }
                head("/test/act") { call.respond(HttpStatusCode.OK) }
                // An audited action that suspends and finishes on another
                // thread first — the path where a credential kept in a bare
                // thread-local would be lost, or found by the wrong request.
                post("/test/act-later") {
                    val caller = call.apiCaller
                    delay(40)
                    withContext(Dispatchers.IO) {
                        transaction { AuditService.log(caller.orgId, caller.userId, "test.later", "test", caller.keyId.toString()) }
                    }
                    call.respond(mapOf("ok" to true))
                }
            }
            PublicApi.guard { _, call ->
                if (call.request.headers["X-Test-Refuse"] != null) throw ForbiddenException("guard_refused")
            }
            Interceptors.before("api-key.create") { ctx ->
                if (ctx.userId == refusedCreator) throw ForbiddenException("creation_refused")
                if (ctx.userId in racers) {
                    // The hook runs inside the creation's transaction. A read
                    // here is its first statement — the moment a REPEATABLE
                    // READ transaction would fix its snapshot — and the pause
                    // keeps every contender at that point until all have read.
                    Users.selectAll().where { Users.id eq ctx.userId!! }.count()
                    Thread.sleep(300)
                }
            }
            Interceptors.after("api-key.create") { ctx, result ->
                // Still inside the transaction, after the insert and before the
                // commit: a contender that was not made to wait for the lock
                // counts here while the others' rows are still invisible.
                if (ctx.userId in racers) Thread.sleep(300)
                lastCreated = result as? ApiKeySummary
                result
            }

            serverPort = ServerSocket(0).use { it.localPort }

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
                "systemLimits.maxApiKeysPerUser" to MAX_KEYS_PER_USER.toString(),
                // On, because the key budgets are under test — with the
                // per-address tiers out of the way of the suite's own traffic.
                "rateLimit.enabled" to "true",
                "platform.allowEmailChange" to "true",
                "rateLimit.general.maxRequests" to "100000",
                "rateLimit.auth.maxRequests" to "100000",
                "rateLimit.api.maxRequests" to KEY_BUDGET.toString(),
                "rateLimit.api.windowSeconds" to ONE_WINDOW,
                "rateLimit.apiFailure.maxRequests" to FAILURE_BUDGET.toString(),
                "rateLimit.apiFailure.windowSeconds" to ONE_WINDOW,
            ))
            val mergedConfig = overrides.withFallback(ConfigFactory.load())

            val env = applicationEnvironment {
                config = HoconApplicationConfig(mergedConfig)
            }

            server = embeddedServer(Netty, env, configure = {
                connector { port = serverPort }
            })

            // A stand-in for what a host installs ahead of routing: a gate that
            // asks who is calling and refuses one account. It lets a caller it
            // cannot identify through, to the route's own 401 — which is the
            // shape that would wave every key past it if `requireAuth` did not
            // answer for keys.
            server.application.intercept(ApplicationCallPipeline.Plugins) {
                val principal = try {
                    requireAuth(call, checkTotpEnrollment = false)
                } catch (_: Exception) {
                    return@intercept
                }
                if (principal.userId == gatedUser) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "gated"))
                    finish()
                }
            }
            // A route in the key namespace that was not mounted through the
            // seam and is not under a version — what a careless host, or a
            // later version of this API, would add.
            server.application.routing {
                get("/api/public/outside") { call.respond(mapOf("ok" to true)) }
            }

            server.start(wait = false)
            Thread.sleep(2000)
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            PublicApi.clearAll()
            Interceptors.clearAll()
            // Nothing of this gateway's for the next one in this process.
            ApiRateLimit.init(null)
            ApiKeyController.init(SystemLimitsConfig.DEFAULT_MAX_API_KEYS_PER_USER)
        }
    }

    private val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build()
    private val jsonType = "application/json".toMediaType()

    /**
     * This test's client address. The gateway trusts one proxy hop, so the
     * last `X-Forwarded-For` entry is the address it keys on.
     */
    private val address = nextAddress()

    // ── Fixtures ──

    private class Account(val userId: UUID, val orgId: UUID, val email: String)

    /** A fresh account that owns a fresh organization. */
    private fun newOwner(userId: UUID = UUID.randomUUID()): Account = transaction {
        val email = "owner-${userId.toString().take(8)}@tracedown.dev"
        insertUser(userId, email)
        val orgId = UUID.randomUUID()
        Organizations.insert {
            it[id] = orgId
            it[name] = "Key Test Org ${orgId.toString().take(6)}"
            it[ownerId] = userId
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
        insertMembership(orgId, userId)
        Users.update({ Users.id eq userId }) { it[selectedOrgId] = orgId }
        Account(userId, orgId, email)
    }

    /** A fresh account that is a plain member of [orgId], holding no section at all. */
    private fun newMember(orgId: UUID, displayName: String? = null): Account = transaction {
        val userId = UUID.randomUUID()
        val email = "member-${userId.toString().take(8)}@tracedown.dev"
        insertUser(userId, email, displayName)
        insertMembership(orgId, userId)
        Users.update({ Users.id eq userId }) { it[selectedOrgId] = orgId }
        Account(userId, orgId, email)
    }

    private fun insertUser(userId: UUID, email: String, name: String? = null) {
        Users.insert {
            it[id] = userId
            it[Users.email] = email
            it[passwordHash] = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray())
            it[displayName] = name ?: email.substringBefore("@")
            it[isActive] = true
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
    }

    private fun insertMembership(orgId: UUID, userId: UUID) {
        OrgUsers.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = orgId
            it[OrgUsers.userId] = userId
            it[joinedAt] = Instant.now()
            it[status] = "active"
            it[isActive] = true
            it[deleted] = false
            it[inviteToken] = ""
        }
    }

    private fun login(account: Account): String {
        val (status, raw) = post("/api/v1/auth/login", """{"email":"${account.email}","password":"$PASSWORD"}""")
        assertEquals(200, status, "Login response: $raw")
        return json(raw).str("token")
    }

    /** Mints a key for the session's user and returns the creation response. */
    private fun createKey(
        session: String,
        access: String = "read",
        name: String = "test key",
        expiresInDays: Int? = null,
    ): JsonObject {
        val expiry = expiresInDays?.let { ""","expiresInDays":$it""" } ?: ""
        val (status, raw) = post(
            "/api/v1/me/api-keys",
            """{"name":"$name","access":"$access","password":"$PASSWORD"$expiry}""",
            session,
        )
        assertEquals(201, status, "Create response: $raw")
        return json(raw)
    }

    private fun mintBody(name: String = "k") = """{"name":"$name","password":"$PASSWORD"}"""

    private fun JsonObject.str(field: String): String = this[field]!!.jsonPrimitive.content

    private fun JsonObject.isNull(field: String): Boolean = this[field] == null || this[field] is JsonNull

    private fun keyRow(id: String) = transaction {
        ApiKeys.selectAll().where { ApiKeys.id eq UUID.fromString(id) }.first()
    }

    private fun auditRows(orgId: UUID, action: String) = transaction {
        OrgAuditLog.selectAll()
            .where { (OrgAuditLog.organizationId eq orgId) and (OrgAuditLog.action eq action) }
            .toList()
    }

    // ── HTTP ──

    private fun request(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): okhttp3.Response {
        val builder = Request.Builder().url("http://localhost:$serverPort$path")
        when (method) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
        }
        builder.header("X-Forwarded-For", address)
        token?.let { builder.header("Authorization", "Bearer $it") }
        headers.forEach { (name, value) -> builder.header(name, value) }
        return client.newCall(builder.build()).execute()
    }

    private fun send(
        method: String,
        path: String,
        token: String? = null,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): Pair<Int, String> = request(method, path, token, body, headers).use { it.code to (it.body?.string() ?: "") }

    private fun get(path: String, token: String? = null, headers: Map<String, String> = emptyMap()) =
        send("GET", path, token, headers = headers)

    private fun post(path: String, body: String = "{}", token: String? = null, headers: Map<String, String> = emptyMap()) =
        send("POST", path, token, body, headers)

    private fun delete(path: String, token: String) = send("DELETE", path, token)

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    /** Asserts the response is a refusal with exactly this status and error code. */
    private fun assertRefused(status: Int, code: String, response: Pair<Int, String>) {
        assertEquals(status, response.first, response.second)
        assertEquals(code, json(response.second).str("error"), response.second)
    }

    private fun items(raw: String) = json(raw)["items"]!!.jsonArray.map { it.jsonObject }

    // ── A key is minted once and stored as a digest ──

    @Test
    fun `a key is shown once and only its digest is stored`() {
        val owner = newOwner()
        val session = login(owner)

        val response = request(
            "POST", "/api/v1/me/api-keys",
            session, """{"name":"deploy","access":"write","password":"$PASSWORD"}""",
        )
        val created = response.use {
            assertEquals(201, it.code)
            assertEquals("no-store", it.header("Cache-Control"), "The response is a credential")
            json(it.body!!.string())
        }
        val key = created.str("key")
        assertTrue(Regex("^td_[A-Za-z0-9_-]{43}$").matches(key), "Unexpected key shape: $key")
        assertEquals(key.take(11), created.str("prefix"))
        assertEquals("write", created.str("access"))
        assertEquals("active", created.str("state"))
        assertEquals(owner.orgId.toString(), created.str("organizationId"))

        val stored = keyRow(created.str("id"))[ApiKeys.keyHash]
        assertEquals(TokenHasher.sha256Hex(key), stored)
        assertNotEquals(key, stored)

        val (status, raw) = get("/api/v1/me/api-keys", session)
        assertEquals(200, status, raw)
        val listed = items(raw).single()
        assertEquals(created.str("id"), listed.str("id"))
        assertTrue(listed.isNull("key"), "The list must never carry the key: $raw")
        assertTrue(key !in raw)
    }

    @Test
    fun `minting a key asks for the password again, and the second factor when there is one`() {
        val owner = newOwner()
        val session = login(owner)

        // A session is not enough to leave a credential behind. (A body that
        // fails validation is answered with the one code every route gives.)
        assertRefused(400, "invalid_request_body", post("/api/v1/me/api-keys", """{"name":"k"}""", session))
        assertRefused(
            400, "invalid_request_body",
            post("/api/v1/me/api-keys", """{"name":"k","access":"admin","password":"$PASSWORD"}""", session),
        )
        assertRefused(400, "incorrect_password", post("/api/v1/me/api-keys", """{"name":"k","password":"nope"}""", session))

        // Enrolled in a second factor: the password alone no longer does it.
        val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val (encrypted, iv) = TotpUtil.encryptSecret(secret, aesKeyBytes)
        transaction {
            Users.update({ Users.id eq owner.userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnrolledAt] = Instant.now()
            }
        }
        assertRefused(400, "invalid_totp_code", post("/api/v1/me/api-keys", mintBody(), session))
        assertRefused(
            400, "invalid_totp_code",
            post("/api/v1/me/api-keys", """{"name":"k","password":"$PASSWORD","code":"000000"}""", session),
        )
        val held = transaction { ApiKeys.selectAll().where { ApiKeys.createdBy eq owner.userId }.count() }
        assertEquals(0, held, "A refused request must mint nothing")

        val code = TotpUtil.generateCode(secret)
        val (status, raw) = post("/api/v1/me/api-keys", """{"name":"k","password":"$PASSWORD","code":"$code"}""", session)
        assertEquals(201, status, raw)
        // Asking for no access level gives the narrower one.
        assertEquals("read", json(raw).str("access"))

        // Guessing codes here is held to the same lockout as at login: enough
        // wrong ones and even the right one is refused until the lock lifts.
        repeat(5) {
            assertRefused(
                400, "invalid_totp_code",
                post("/api/v1/me/api-keys", """{"name":"k","password":"$PASSWORD","code":"111111"}""", session),
            )
        }
        transaction { Users.update({ Users.email eq owner.email }) { it[totpLastStep] = null } }
        val fresh = TotpUtil.generateCode(secret)
        assertRefused(
            400, "invalid_totp_code",
            post("/api/v1/me/api-keys", """{"name":"k","password":"$PASSWORD","code":"$fresh"}""", session),
        )
    }

    @Test
    fun `a refusal the mint could see coming does not cost the second factor`() {
        val owner = newOwner()
        val session = login(owner)
        repeat(MAX_KEYS_PER_USER) { createKey(session, name = "key $it") }

        // Enrolled, with one recovery code — the situation of someone who lost
        // their phone and is about to find out they are at the cap.
        val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val (encrypted, iv) = TotpUtil.encryptSecret(secret, aesKeyBytes)
        transaction {
            Users.update({ Users.id eq owner.userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnrolledAt] = Instant.now()
            }
        }
        val code = TotpUtil.generateCode(secret)
        assertRefused(
            400, "api_key_limit_reached",
            post("/api/v1/me/api-keys", """{"name":"one more","password":"$PASSWORD","code":"$code"}""", session),
        )
        // The code was never looked at, so the same one still works once there is room.
        assertEquals(200, delete("/api/v1/me/api-keys/${items(get("/api/v1/me/api-keys", session).second).first().str("id")}", session).first)
        val (status, raw) = post("/api/v1/me/api-keys", """{"name":"one more","password":"$PASSWORD","code":"$code"}""", session)
        assertEquals(201, status, raw)
    }

    @Test
    fun `a password reset or email change does not revoke keys`() {
        val owner = newOwner()
        val session = login(owner)
        val key = createKey(session).str("key")

        val newPassword = "Changed${PASSWORD}"
        assertEquals(
            200,
            post("/api/v1/auth/change-password", """{"currentPassword":"$PASSWORD","newPassword":"$newPassword"}""", session).first,
        )
        assertEquals(200, get("/api/public/v1/key", key).first)

        val fresh = run {
            val (status, raw) = post("/api/v1/auth/login", """{"email":"${owner.email}","password":"$newPassword"}""")
            assertEquals(200, status, raw)
            json(raw).str("token")
        }
        val renamed = "renamed-${owner.userId.toString().take(8)}@tracedown.dev"
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"$renamed","currentPassword":"$newPassword"}""",
            fresh,
        )
        assertEquals(200, status, raw)
        // The link goes to the new address by mail; stand in for it with a token of our own.
        val token = "confirm-${UUID.randomUUID()}"
        transaction {
            EmailChangeRequests.update({ EmailChangeRequests.userId eq owner.userId }) {
                it[tokenHash] = TokenHasher.sha256Hex(token)
            }
        }
        val (confirmed, confirmedRaw) = post("/api/v1/me/email/confirm", """{"token":"$token"}""")
        assertEquals(200, confirmed, confirmedRaw)
        assertEquals(renamed, transaction { Users.selectAll().where { Users.id eq owner.userId }.first()[Users.email] })
        assertEquals(200, get("/api/public/v1/key", key).first)
        assertEquals(false, keyRow(json(get("/api/public/v1/key", key).second).str("id"))[ApiKeys.revoked])
    }

    @Test
    fun `a key authenticates the public API and can describe itself`() {
        val owner = newOwner()
        val member = newMember(owner.orgId)
        val created = createKey(login(member), name = "describe me", expiresInDays = 30)
        val key = created.str("key")

        val (status, raw) = get("/api/public/v1/key", key)
        assertEquals(200, status, raw)
        val info = json(raw)
        assertEquals(created.str("id"), info.str("id"))
        assertEquals("describe me", info.str("name"))
        assertEquals(key.take(11), info.str("prefix"))
        assertEquals("read", info.str("access"))
        assertEquals(owner.orgId.toString(), info["organization"]!!.jsonObject.str("id"))
        // The key acts as the member who minted it — not as the organization's owner.
        assertEquals(member.userId.toString(), info["user"]!!.jsonObject.str("id"))
        assertEquals(member.email, info["user"]!!.jsonObject.str("email"))
        val expiresAt = Instant.parse(info.str("expiresAt"))
        val expected = Instant.now().plus(Duration.ofDays(30))
        assertTrue(Duration.between(expiresAt, expected).abs() < Duration.ofMinutes(5), "expiresAt=$expiresAt")

        val firstUse = keyRow(created.str("id"))[ApiKeys.lastUsedAt]
        assertNotNull(firstUse, "A key that authenticated must be stamped as used")
        // …and not on every request: the stamp is rewritten at most once a minute.
        assertEquals(200, get("/api/public/v1/key", key).first)
        assertEquals(firstUse, keyRow(created.str("id"))[ApiKeys.lastUsedAt])
    }

    // ── One credential per namespace ──

    @Test
    fun `a key is refused by the session API and a session by the public API`() {
        val owner = newOwner()
        val session = login(owner)
        val created = createKey(session, access = "write")
        val key = created.str("key")

        // The key is valid — and still is not a session.
        assertRefused(401, "session_required", get("/api/v1/auth/me", key))
        // Nor may it mint, list, revoke or delete keys, which only the session API does.
        assertRefused(401, "session_required", post("/api/v1/me/api-keys", mintBody("second"), key))
        assertRefused(401, "session_required", get("/api/v1/me/api-keys", key))
        assertRefused(401, "session_required", post("/api/v1/me/api-keys/${created.str("id")}/revoke", token = key))
        assertRefused(401, "session_required", delete("/api/v1/api-keys/${created.str("id")}", key))
        assertEquals(200, get("/api/public/v1/key", key).first)

        // The session is valid — and still is not a key.
        assertRefused(401, "invalid_api_key", get("/api/public/v1/key", session))
        assertRefused(401, "missing_auth_header", get("/api/public/v1/key"))
        // Something that is neither is told the plain thing, not that it looks like a key.
        assertRefused(401, "invalid_token", get("/api/v1/auth/me", "not-a-session"))
    }

    @Test
    fun `an equivalent spelling of a path does not change its namespace`() {
        val owner = newOwner()
        val session = login(owner)
        val key = createKey(session).str("key")

        // Doubled slashes and an escaped letter route to the same handlers, so
        // the credential rule has to follow the route, not the raw string. Each
        // spelling is asked with the credential that would only work if it were
        // classified correctly.
        assertEquals(200, get("//api/public/v1/key", key).first)
        assertEquals(200, get("/api/public//v1/key", key).first)
        assertEquals(200, get("/api/%70ublic/v1/key", key).first)
        assertEquals(200, get("//api/v1/auth/me", session).first)

        // And with the wrong one.
        assertRefused(401, "invalid_api_key", get("//api/public/v1/key", session))
        assertRefused(401, "session_required", get("//api/v1/auth/me", key))

        // An encoded slash is one path segment to the router and belongs to
        // neither namespace: no credential opens it.
        assertNotEquals(200, get("/api/public%2Fv1/key", key).first)
        assertNotEquals(200, get("/api/v1%2Fauth/me", session).first)
    }

    // ── A key never does more than its user ──

    @Test
    fun `a read-only key is refused everything but a read, before the handler runs`() {
        val owner = newOwner()
        val session = login(owner)
        val readKey = createKey(session, access = "read").str("key")
        val writeKey = createKey(session, access = "write").str("key")

        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertRefused(403, "api_key_read_only", send(method, "/api/public/v1/test/act", readKey))
        }
        assertTrue(auditRows(owner.orgId, "test.act").isEmpty(), "A refused write must not have reached its handler")

        assertEquals(200, get("/api/public/v1/test/audit", readKey).first)
        assertEquals(200, send("HEAD", "/api/public/v1/test/act", readKey).first)

        for (method in listOf("POST", "PUT", "PATCH", "DELETE")) {
            assertEquals(200, send(method, "/api/public/v1/test/act", writeKey).first, method)
        }
        assertEquals(4, auditRows(owner.orgId, "test.act").size)
    }

    @Test
    fun `the read-only ceiling comes before the guards`() {
        val key = createKey(login(newOwner())).str("key")
        assertRefused(
            403, "api_key_read_only",
            post("/api/public/v1/test/act", token = key, headers = mapOf("X-Test-Refuse" to "1")),
        )
    }

    @Test
    fun `a key meets its user's permissions as they are now`() {
        val owner = newOwner()
        val ownerSession = login(owner)
        val member = newMember(owner.orgId)
        // Write access on the key gives it nothing the member does not hold.
        val key = createKey(login(member), access = "write").str("key")

        assertRefused(403, "insufficient_permissions", get("/api/public/v1/test/audit", key))

        // The owner grants the member the section, the way the dashboard does:
        // the key has it on its next request.
        setSettings(ownerSession, member, 1)
        assertEquals(200, get("/api/public/v1/test/audit", key).first)

        // And takes it away: so does the key, with nothing done to the key itself.
        setSettings(ownerSession, member, 0)
        assertRefused(403, "insufficient_permissions", get("/api/public/v1/test/audit", key))
    }

    /** Sets the member's `settings` section through the permissions endpoint, cache and all. */
    private fun setSettings(ownerSession: String, member: Account, level: Int) {
        val (status, raw) = send(
            "PATCH", "/api/v1/users/${member.userId}/permissions", ownerSession,
            """{"org":{"users":0,"settings":$level,"domains":0,"webhooks":0,"notifications":0,"admin":0,"workspaces":0}}""",
        )
        assertEquals(200, status, raw)
    }

    private fun setMemberActive(ownerSession: String, member: Account, active: Boolean) {
        val (status, raw) = post("/api/v1/users/${member.userId}/toggle", """{"isActive":$active}""", ownerSession)
        assertEquals(200, status, raw)
    }

    @Test
    fun `a key stops working while its user cannot act and is revoked when they are removed`() {
        val owner = newOwner()
        val ownerSession = login(owner)
        val member = newMember(owner.orgId)
        val memberSession = login(member)
        val created = createKey(memberSession)
        val key = created.str("key")
        assertEquals(200, get("/api/public/v1/key", key).first)

        // Membership disabled: dead, and both lists say so. Enabled again:
        // alive, nothing reissued.
        setMemberActive(ownerSession, member, false)
        assertRefused(401, "api_key_owner_inactive", get("/api/public/v1/key", key))
        assertEquals("inactive", items(get("/api/v1/api-keys", ownerSession).second).single().str("state"))
        assertEquals("inactive", items(get("/api/v1/me/api-keys", memberSession).second).single().str("state"))
        // A member who cannot act cannot mint a key to act with, live session or not.
        assertRefused(403, "not_org_member", post("/api/v1/me/api-keys", mintBody(), memberSession))

        setMemberActive(ownerSession, member, true)
        assertEquals(200, get("/api/public/v1/key", key).first)
        assertEquals("active", items(get("/api/v1/api-keys", ownerSession).second).single().str("state"))

        // Account deactivated.
        transaction { Users.update({ Users.id eq member.userId }) { it[isActive] = false } }
        assertRefused(401, "api_key_owner_inactive", get("/api/public/v1/key", key))
        assertEquals("inactive", items(get("/api/v1/api-keys", ownerSession).second).single().str("state"))
        transaction { Users.update({ Users.id eq member.userId }) { it[isActive] = true } }
        assertEquals(200, get("/api/public/v1/key", key).first)

        // Removed from the organization: the key is revoked for good, so a
        // later re-invite cannot hand the old credential back — and the audit
        // log says why it is revoked, with nobody as the actor.
        assertEquals(200, delete("/api/v1/users/${member.userId}", ownerSession).first)
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", key))
        val revocation = auditRows(owner.orgId, "revoke.api-key").single()
        assertEquals(created.str("id"), revocation[OrgAuditLog.entityId])
        assertNull(revocation[OrgAuditLog.userId])
    }

    @Test
    fun `a key has nobody to act as once its user is deleted or erased, or its organization is gone`() {
        val owner = newOwner()
        val ownerSession = login(owner)
        val member = newMember(owner.orgId)

        // The account is deleted while its membership row still stands.
        val deletedUsers = createKey(login(member)).str("key")
        transaction { Users.update({ Users.id eq member.userId }) { it[deleted] = true } }
        assertRefused(401, "api_key_owner_inactive", get("/api/public/v1/key", deletedUsers))
        assertEquals(
            "inactive",
            items(get("/api/v1/api-keys", ownerSession).second).single { it.str("createdBy") == member.userId.toString() }.str("state"),
        )

        // The account is erased: what ON DELETE SET NULL leaves behind.
        val orphan = createKey(ownerSession)
        transaction { ApiKeys.update({ ApiKeys.id eq UUID.fromString(orphan.str("id")) }) { it[createdBy] = null } }
        assertRefused(401, "api_key_owner_inactive", get("/api/public/v1/key", orphan.str("key")))
        val listed = items(get("/api/v1/api-keys", ownerSession).second).single { it.str("id") == orphan.str("id") }
        assertEquals("inactive", listed.str("state"))
        assertTrue(listed.isNull("createdBy") && listed.isNull("createdByEmail"), "An orphaned key names nobody: $listed")

        // The organization is deleted: its owner's key goes with it.
        val other = newOwner()
        val orgless = createKey(login(other)).str("key")
        assertEquals(200, get("/api/public/v1/key", orgless).first)
        transaction { Organizations.update({ Organizations.id eq other.orgId }) { it[deleted] = true } }
        assertRefused(401, "api_key_owner_inactive", get("/api/public/v1/key", orgless))
        assertEquals("inactive", items(get("/api/v1/me/api-keys", login(other)).second).single().str("state"))
    }

    @Test
    fun `a key is held to the TOTP enrolment its user is held to, and told so`() {
        val owner = newOwner()
        val session = login(owner)
        val key = createKey(session).str("key")
        assertEquals(200, get("/api/public/v1/key", key).first)

        // The organization starts requiring a second factor the user has not
        // enrolled: their session would be sent to enrol, and their key is
        // refused with the reason — it has no screen to be sent to.
        val (status, raw) = send("PATCH", "/api/v1/org/settings", session, """{"totpRequired":true}""")
        assertEquals(200, status, raw)
        assertRefused(403, "totp_enrollment_required", get("/api/public/v1/key", key))
        // The stand-in gate asked first, without the enrolment check; the
        // tree's own ask still applied it. Asking once does not answer for both.
        // The session gets the refusal it always got…
        assertRefused(403, "forbidden", get("/api/v1/workspaces", session))

        // Enrolled: both are admitted again.
        val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
        val (encrypted, iv) = TotpUtil.encryptSecret(secret, aesKeyBytes)
        transaction {
            Users.update({ Users.id eq owner.userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnrolledAt] = Instant.now()
            }
        }
        assertEquals(200, get("/api/public/v1/key", key).first)
        assertEquals(200, get("/api/v1/workspaces", session).first)
    }

    @Test
    fun `a revived account does not inherit the previous holder's keys`() {
        val owner = newOwner()
        val created = createKey(login(owner))
        transaction { AccountLifecycle.revive(owner.userId) }

        val row = keyRow(created.str("id"))
        assertTrue(row[ApiKeys.deleted] && row[ApiKeys.revoked], "The prior holder's key must be gone with them")
        assertNotNull(row[ApiKeys.deletedAt])
        assertNotNull(row[ApiKeys.purgeAfter], "Gone the way every deletion goes: kept for the retention, then purged")
        assertRefused(401, "invalid_api_key", get("/api/public/v1/key", created.str("key")))
    }

    // ── Lifecycle ──

    @Test
    fun `revoked, expired and deleted keys are refused, each with its reason`() {
        val owner = newOwner()
        val session = login(owner)

        val revoked = createKey(session)
        assertEquals(200, post("/api/v1/me/api-keys/${revoked.str("id")}/revoke", token = session).first)
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", revoked.str("key")))
        // Revoking what is already revoked is not a second event.
        assertEquals(200, post("/api/v1/me/api-keys/${revoked.str("id")}/revoke", token = session).first)
        assertEquals(1, auditRows(owner.orgId, "revoke.api-key").size)

        val expired = createKey(session)
        transaction {
            ApiKeys.update({ ApiKeys.id eq UUID.fromString(expired.str("id")) }) {
                it[expiresAt] = Instant.now().minusSeconds(60)
            }
        }
        assertRefused(401, "api_key_expired", get("/api/public/v1/key", expired.str("key")))

        val deleted = createKey(session)
        assertEquals(200, delete("/api/v1/me/api-keys/${deleted.str("id")}", session).first)
        assertRefused(401, "invalid_api_key", get("/api/public/v1/key", deleted.str("key")))

        // The list tells the same story the authenticator does.
        val states = items(get("/api/v1/me/api-keys", session).second).associate { it.str("id") to it.str("state") }
        assertEquals("revoked", states[revoked.str("id")])
        assertEquals("expired", states[expired.str("id")])
        assertNull(states[deleted.str("id")])
    }

    @Test
    fun `a user holds at most the configured number of keys, whatever their state`() {
        val owner = newOwner()
        val session = login(owner)
        val keys = (1..MAX_KEYS_PER_USER).map { createKey(session, name = "key $it") }

        assertRefused(400, "api_key_limit_reached", post("/api/v1/me/api-keys", mintBody("one too many"), session))

        // Neither revoking nor expiring frees a slot — the key is still in the list.
        assertEquals(200, post("/api/v1/me/api-keys/${keys[0].str("id")}/revoke", token = session).first)
        transaction {
            ApiKeys.update({ ApiKeys.id eq UUID.fromString(keys[1].str("id")) }) {
                it[expiresAt] = Instant.now().minusSeconds(60)
            }
        }
        assertRefused(400, "api_key_limit_reached", post("/api/v1/me/api-keys", mintBody("still too many"), session))

        // Deleting does.
        assertEquals(200, delete("/api/v1/me/api-keys/${keys[0].str("id")}", session).first)
        createKey(session, name = "replacement")
    }

    @Test
    fun `concurrent requests cannot take a user past the cap`() {
        val owner = newOwner()
        val session = login(owner)
        repeat(MAX_KEYS_PER_USER - 1) { createKey(session, name = "key $it") }

        // One slot left, eight requests for it at once — each made to read
        // before any has written, which is the interleaving that lets a count
        // taken from a transaction-long snapshot admit them all.
        racers += owner.userId
        val pool = Executors.newFixedThreadPool(8)
        val statuses = try {
            pool.invokeAll((1..8).map { n ->
                Callable { post("/api/v1/me/api-keys", mintBody("racer $n"), session).first }
            }).map { it.get() }
        } finally {
            pool.shutdown()
        }

        assertEquals(1, statuses.count { it == 201 }, "statuses=$statuses")
        assertEquals(7, statuses.count { it == 400 }, "statuses=$statuses")
        val held = transaction {
            ApiKeys.selectAll().where { (ApiKeys.createdBy eq owner.userId) and (ApiKeys.deleted eq false) }.count()
        }
        assertEquals(MAX_KEYS_PER_USER.toLong(), held)
    }

    @Test
    fun `a mint that waited on a removal is refused by the check it makes under the lock`() {
        val owner = newOwner()
        val member = newMember(owner.orgId)
        val session = login(member)
        val membershipId = transaction {
            OrgUsers.selectAll().where { (OrgUsers.organizationId eq member.orgId) and (OrgUsers.userId eq member.userId) }
                .single()[OrgUsers.id]
        }

        // Only the membership row: marked gone and stripped of its levels, and
        // held uncommitted. Nothing touches the account, so the mint passes
        // every check it makes before locking, blocks on the membership row
        // (`FOR SHARE`), and only the check it makes once it holds the lock
        // can refuse it.
        val holding = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        val remover = pool.submit {
            transaction {
                OrgUsers.update({ OrgUsers.id eq membershipId }) {
                    it[deleted] = true
                    it[orgUserList] = 0
                    it[orgSettings] = 0
                    it[orgDomains] = 0
                    it[orgWebhooks] = 0
                    it[orgNotifications] = 0
                    it[orgAdmin] = 0
                    it[orgWorkspaces] = 0
                    it[permissionCache] = null
                }
                holding.countDown()
                assertTrue(release.await(10, java.util.concurrent.TimeUnit.SECONDS), "The mint never reached the lock")
            }
        }
        try {
            assertTrue(holding.await(10, java.util.concurrent.TimeUnit.SECONDS), "The removal never started")
            val mint = pool.submit(Callable { post("/api/v1/me/api-keys", mintBody("raced"), session) })
            // Released only once the mint is waiting on the membership row.
            var blockedOn: String? = null
            val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
            while (System.nanoTime() < deadline && blockedOn == null) {
                blockedOn = transaction {
                    exec("SELECT query FROM pg_stat_activity WHERE wait_event_type = 'Lock'") { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
                }
                if (blockedOn == null) Thread.sleep(20)
            }
            release.countDown()
            remover.get(10, java.util.concurrent.TimeUnit.SECONDS)
            assertNotNull(blockedOn, "The mint never waited on a lock")
            assertTrue(
                "org_users" in blockedOn!!.lowercase() && "for share" in blockedOn.lowercase(),
                "The mint should wait on the membership row: $blockedOn",
            )
            assertRefused(403, "not_org_member", mint.get(10, java.util.concurrent.TimeUnit.SECONDS))
        } finally {
            release.countDown()
            pool.shutdown()
        }
        val written = transaction { ApiKeys.selectAll().where { ApiKeys.createdBy eq member.userId }.count() }
        assertEquals(0, written, "No key may be written for a membership that ended while the mint waited")
    }

    @Test
    fun `deleting a key never moves its purge date later`() {
        val previous = dev.tracedown.common.config.DeletionRetention.days()
        dev.tracedown.common.config.DeletionRetention.init(30)
        try {
            val owner = newOwner()
            val session = login(owner)
            val early = createKey(session, name = "already due").str("id")
            val fresh = createKey(session, name = "fresh").str("id")
            val soon = Instant.now().plusSeconds(86_400).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            transaction { ApiKeys.update({ ApiKeys.id eq UUID.fromString(early) }) { it[purgeAfter] = soon } }

            assertEquals(200, delete("/api/v1/me/api-keys/$early", session).first)
            assertEquals(200, delete("/api/v1/me/api-keys/$fresh", session).first)

            assertEquals(soon, keyRow(early)[ApiKeys.purgeAfter], "An earlier purge date is kept")
            val retained = keyRow(fresh)[ApiKeys.purgeAfter]!!
            assertTrue(
                Duration.between(Instant.now().plus(Duration.ofDays(30)), retained).abs() < Duration.ofMinutes(5),
                "A key with no purge date gets the retention: $retained",
            )

            // A later purge date is brought forward to the retention.
            val late = createKey(session, name = "due late").str("id")
            transaction {
                ApiKeys.update({ ApiKeys.id eq UUID.fromString(late) }) { it[purgeAfter] = Instant.now().plus(Duration.ofDays(90)) }
            }
            assertEquals(200, delete("/api/v1/me/api-keys/$late", session).first)
            val brought = keyRow(late)[ApiKeys.purgeAfter]!!
            assertTrue(
                Duration.between(Instant.now().plus(Duration.ofDays(30)), brought).abs() < Duration.ofMinutes(5),
                "A later purge date becomes the retention: $brought",
            )

            // The same for the keys a new holder of the account sweeps away.
            val kept = createKey(session, name = "swept").str("id")
            transaction { ApiKeys.update({ ApiKeys.id eq UUID.fromString(kept) }) { it[purgeAfter] = soon } }
            transaction { AccountLifecycle.wipePriorHolder(owner.userId) }
            assertTrue(keyRow(kept)[ApiKeys.deleted])
            assertEquals(soon, keyRow(kept)[ApiKeys.purgeAfter])
        } finally {
            dev.tracedown.common.config.DeletionRetention.init(previous)
        }
    }

    @Test
    fun `the cap, the list and the removal each see every organization a user is in`() {
        val home = newOwner()
        val session = login(home)
        val elsewhere = newOwner()
        transaction { insertMembership(elsewhere.orgId, home.userId) }

        val homeKey = createKey(session, name = "home")
        // Minted in the other organization; the session's own is `home`.
        val awayKey = ApiKeyController.create(
            elsewhere.orgId, CreateApiKeyRequest("away", password = PASSWORD), home.userId,
        )
        val awayToo = ApiKeyController.create(
            elsewhere.orgId, CreateApiKeyRequest("away too", password = PASSWORD), home.userId,
        )

        // The cap counts across organizations…
        assertRefused(400, "api_key_limit_reached", post("/api/v1/me/api-keys", mintBody(), session))
        // …and the user's own list shows each key with where it acts.
        val mine = items(get("/api/v1/me/api-keys", session).second).associate { it.str("name") to it.str("organizationId") }
        assertEquals(
            mapOf("home" to home.orgId.toString(), "away" to elsewhere.orgId.toString(), "away too" to elsewhere.orgId.toString()),
            mine,
        )
        // An organization's list shows only what acts in it.
        assertEquals(
            setOf("away", "away too"),
            items(get("/api/v1/api-keys", login(elsewhere)).second).map { it.str("name") }.toSet(),
        )

        // Leaving one organization revokes the keys there and no others.
        assertEquals(200, delete("/api/v1/users/${home.userId}", login(elsewhere)).first)
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", awayKey.key!!))
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", awayToo.key!!))
        assertEquals(200, get("/api/public/v1/key", homeKey.str("key")).first)
    }

    @Test
    fun `the user's own list is paged like any other`() {
        val session = login(newOwner())
        json(get("/api/v1/me/api-keys", session).second).let { empty ->
            assertEquals("0", empty.str("total"))
            assertNotEquals("0", empty.str("pageSize"), "An empty list still has a page size")
        }
        createKey(session, name = "older")
        createKey(session, name = "newer")
        val second = json(get("/api/v1/me/api-keys?page=2&pageSize=1", session).second)
        assertEquals("2", second.str("total"))
        assertEquals("older", second["items"]!!.jsonArray.single().jsonObject.str("name"), "Newest first")
    }

    // ── Who manages which keys ──

    @Test
    fun `a user manages their own keys and an administrator oversees the organization's`() {
        val owner = newOwner()
        val ownerSession = login(owner)
        val member = newMember(owner.orgId)
        val memberSession = login(member)
        val stranger = newOwner()
        val strangerSession = login(stranger)

        val memberKey = createKey(memberSession, name = "member key")
        val keyId = memberKey.str("id")
        val ownerKey = createKey(ownerSession, name = "owner key")

        // Somebody else's key is not there to be revoked or deleted, whether
        // asked for as one's own or through another organization.
        assertEquals(404, post("/api/v1/me/api-keys/$keyId/revoke", token = strangerSession).first)
        assertEquals(404, delete("/api/v1/me/api-keys/$keyId", strangerSession).first)
        assertEquals(404, post("/api/v1/api-keys/$keyId/revoke", token = strangerSession).first)
        assertEquals(404, delete("/api/v1/api-keys/$keyId", strangerSession).first)
        assertEquals(404, post("/api/v1/me/api-keys/$keyId/revoke", token = ownerSession).first)
        assertEquals(200, get("/api/public/v1/key", memberKey.str("key")).first)

        // The organization's surface needs `settings`; the member has none —
        // not to see it, and not to cut off a colleague's key.
        assertRefused(403, "insufficient_permissions", get("/api/v1/api-keys", memberSession))
        assertRefused(
            403, "insufficient_permissions",
            post("/api/v1/api-keys/${ownerKey.str("id")}/revoke", token = memberSession),
        )
        assertRefused(403, "insufficient_permissions", delete("/api/v1/api-keys/${ownerKey.str("id")}", memberSession))
        // Reading the section shows the list and nothing more.
        setSettings(ownerSession, member, 1)
        assertEquals(200, get("/api/v1/api-keys", memberSession).first)
        assertRefused(
            403, "insufficient_permissions",
            post("/api/v1/api-keys/${ownerKey.str("id")}/revoke", token = memberSession),
        )
        assertRefused(403, "insufficient_permissions", delete("/api/v1/api-keys/${ownerKey.str("id")}", memberSession))
        assertEquals(200, get("/api/public/v1/key", ownerKey.str("key")).first)
        // Nor is there a way to mint a key from the organization's side.
        assertEquals(405, post("/api/v1/api-keys", mintBody("for someone else"), ownerSession).first)

        // The owner sees the member's key, attributed to the member…
        val listed = items(get("/api/v1/api-keys", ownerSession).second).single { it.str("id") == keyId }
        assertEquals(member.email, listed.str("createdByEmail"))
        assertEquals(member.userId.toString(), listed.str("createdBy"))
        assertTrue(listed.isNull("key"))

        // …and can cut it off, or remove it.
        assertEquals(200, post("/api/v1/api-keys/$keyId/revoke", token = ownerSession).first)
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", memberKey.str("key")))
        assertEquals(200, delete("/api/v1/api-keys/$keyId", ownerSession).first)
        assertRefused(401, "invalid_api_key", get("/api/public/v1/key", memberKey.str("key")))
    }

    @Test
    fun `the organization's list is grouped by user unless asked otherwise, and can be filtered`() {
        val owner = newOwner()
        val ownerSession = login(owner)
        val zoe = newMember(owner.orgId, displayName = "Zoe")
        val adam = newMember(owner.orgId, displayName = "Adam")
        transaction { Users.update({ Users.id eq owner.userId }) { it[displayName] = "Mia" } }

        createKey(login(zoe), name = "z1")
        createKey(ownerSession, name = "m1")
        createKey(login(adam), name = "a1")
        createKey(login(zoe), name = "z2")

        // By user, and each user's newest first.
        assertEquals(
            listOf("a1", "m1", "z2", "z1"),
            items(get("/api/v1/api-keys", ownerSession).second).map { it.str("name") },
        )

        fun query(name: String, value: String) = "$name=" + URLEncoder.encode(value, Charsets.UTF_8)

        // A sorter replaces the default.
        val byName = query("sorters", """[{"table":"api_keys","column":"name","order":"desc"}]""")
        assertEquals(
            listOf("z2", "z1", "m1", "a1"),
            items(get("/api/v1/api-keys?$byName", ownerSession).second).map { it.str("name") },
        )

        // "Whose keys are these" is a filter.
        val zoes = query("filters", """[{"table":"api_keys","column":"created_by","operator":"eq","value":"${zoe.userId}"}]""")
        assertEquals(
            setOf("z1", "z2"),
            items(get("/api/v1/api-keys?$zoes", ownerSession).second).map { it.str("name") }.toSet(),
        )

        // The digest is not a column anyone may ask about.
        val onHash = query("filters", """[{"table":"api_keys","column":"key_hash","operator":"eq","value":"x"}]""")
        assertEquals(400, get("/api/v1/api-keys?$onHash", ownerSession).first)
    }

    // ── Audit ──

    @Test
    fun `an action taken through a key is audited against the user with the key beside them`() {
        val owner = newOwner()
        val session = login(owner)
        val created = createKey(session, access = "write", name = "auditor")
        val keyId = UUID.fromString(created.str("id"))
        val bystander = createKey(session, access = "write", name = "bystander")

        assertEquals(200, post("/api/public/v1/test/act", token = created.str("key")).first)
        assertEquals(200, post("/api/public/v1/test/act", token = bystander.str("key")).first)

        val viaKey = auditRows(owner.orgId, "test.act").single { it[OrgAuditLog.apiKeyId] == keyId }
        assertEquals(owner.userId, viaKey[OrgAuditLog.userId])
        // Minting the keys was done in a session, and says so by saying nothing.
        val minted = auditRows(owner.orgId, "create.api-key")
        assertEquals(2, minted.size)
        assertTrue(minted.all { it[OrgAuditLog.apiKeyId] == null && it[OrgAuditLog.userId] == owner.userId })
        // So is deleting one.
        assertEquals(200, delete("/api/v1/me/api-keys/${bystander.str("id")}", session).first)
        val deleted = auditRows(owner.orgId, "delete.api-key").single()
        assertEquals(bystander.str("id"), deleted[OrgAuditLog.entityId])
        assertNull(deleted[OrgAuditLog.apiKeyId])
        assertTrue(keyRow(bystander.str("id"))[ApiKeys.revoked], "Deleted implies revoked")

        val shown = items(get("/api/v1/audit-log", session).second)
            .single { it.str("action") == "test.act" && it.str("apiKeyId") == keyId.toString() }
        assertEquals("auditor", shown.str("apiKeyName"))

        // "What did this key do?" is one filter.
        val filter = "filters=" + URLEncoder.encode(
            """[{"table":"org_audit_log","column":"api_key_id","operator":"eq","value":"$keyId"}]""", Charsets.UTF_8,
        )
        val (status, raw) = get("/api/v1/audit-log?$filter", session)
        assertEquals(200, status, raw)
        assertEquals(listOf("test.act"), items(raw).map { it.str("action") })
    }

    @Test
    fun `the key stays with its own request across suspension and under concurrency`() {
        val owner = newOwner()
        val session = login(owner)
        val one = createKey(session, access = "write", name = "one")
        val two = createKey(session, access = "write", name = "two")
        val toRevoke = createKey(session, name = "revoked meanwhile")

        // Two keys act at once through a handler that suspends and finishes on
        // another thread, while a session-authenticated action is audited in
        // the middle of them.
        val pool = Executors.newFixedThreadPool(13)
        val statuses = try {
            pool.invokeAll(
                (1..6).map { Callable { post("/api/public/v1/test/act-later", token = one.str("key")).first } } +
                    (1..6).map { Callable { post("/api/public/v1/test/act-later", token = two.str("key")).first } } +
                    Callable { post("/api/v1/me/api-keys/${toRevoke.str("id")}/revoke", token = session).first },
            ).map { it.get() }
        } finally {
            pool.shutdown()
        }
        assertEquals(List(13) { 200 }, statuses)

        // Each entry names, as its entity, the key its handler was called with:
        // the credential recorded beside it has to be that one.
        val later = auditRows(owner.orgId, "test.later")
        assertEquals(12, later.size)
        for (row in later) assertEquals(row[OrgAuditLog.entityId], row[OrgAuditLog.apiKeyId]?.toString())
        // And the session's action picked up nobody's key from a shared thread.
        assertNull(auditRows(owner.orgId, "revoke.api-key").single()[OrgAuditLog.apiKeyId])
    }

    // ── The seam ──

    @Test
    fun `a route added through the seam is behind the same door`() {
        assertRefused(401, "missing_auth_header", get("/api/public/v1/test/audit"))
        assertRefused(401, "invalid_api_key", get("/api/public/v1/test/audit", login(newOwner())))
    }

    @Test
    fun `a route in the key namespace is behind the door wherever it was mounted`() {
        // Not registered through the seam, not under a version — and still
        // inside the namespace, so still inside the enforcement.
        assertRefused(401, "missing_auth_header", get("/api/public/outside"))
        val session = login(newOwner())
        assertRefused(401, "invalid_api_key", get("/api/public/outside", session))
        val key = createKey(session).str("key")
        assertEquals(200, get("/api/public/outside", key).first)
        assertRefused(403, "guard_refused", get("/api/public/outside", key, headers = mapOf("X-Test-Refuse" to "1")))
    }

    @Test
    fun `routes cannot be registered once the gateway is running`() {
        assertThrows<IllegalStateException> { PublicApi.routes { } }
    }

    @Test
    fun `a guard can refuse a call and a hook can refuse a key`() {
        val owner = newOwner()
        val key = createKey(login(owner)).str("key")

        assertRefused(403, "guard_refused", get("/api/public/v1/key", key, headers = mapOf("X-Test-Refuse" to "1")))
        // The guard covers what a host mounted as it covers what this module did.
        assertRefused(403, "guard_refused", get("/api/public/v1/test/audit", key, headers = mapOf("X-Test-Refuse" to "1")))
        assertEquals(200, get("/api/public/v1/key", key).first)

        // What a hook is handed describes the key; it never holds the key.
        val handed = lastCreated
        assertNotNull(handed)
        assertEquals(key.take(11), handed!!.prefix)
        assertNull(handed.key)

        val refused = newOwner(refusedCreator)
        assertRefused(403, "creation_refused", post("/api/v1/me/api-keys", mintBody("not allowed"), login(refused)))
        val held = transaction { ApiKeys.selectAll().where { ApiKeys.createdBy eq refusedCreator }.count() }
        assertEquals(0, held, "A refused creation must leave no key behind")
    }

    @Test
    fun `a gate that runs ahead of routing sees the user behind a key`() {
        val gated = newOwner(gatedUser)
        // Minted before the gate knows them — directly, since their session is gated too.
        val key = "td_" + "g".repeat(43)
        transaction {
            ApiKeys.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = gated.orgId
                it[createdBy] = gated.userId
                it[name] = "gated"
                it[keyHash] = TokenHasher.sha256Hex(key)
                it[createdAt] = Instant.now()
            }
        }

        assertRefused(403, "gated", get("/api/public/v1/key", key))
        val session = login(gated)
        assertRefused(403, "gated", get("/api/v1/auth/me", session))
        // The gate is not handed a session on a key path: it finds nobody, lets
        // the call through, and the tree refuses it for what it is.
        assertRefused(401, "invalid_api_key", get("/api/public/v1/key", session))
    }

    // ── Budgets ──

    @Test
    fun `a key has its own request budget, and says how much is left`() {
        val owner = newOwner()
        val session = login(owner)
        val busy = createKey(session, name = "busy").str("key")
        val quiet = createKey(session, name = "quiet").str("key")

        val seen = (1..KEY_BUDGET + 1).map {
            request("GET", "/api/public/v1/key", busy).use { response ->
                Triple(response.code, response.header("X-RateLimit-Remaining"), response.header("Retry-After"))
            }
        }
        assertEquals(List(KEY_BUDGET) { 200 } + 429, seen.map { it.first })
        assertEquals((KEY_BUDGET - 1 downTo 0).map { it.toString() }, seen.take(KEY_BUDGET).map { it.second })
        val retryAfter = seen.last().third
        assertNotNull(retryAfter, "A refusal says when to come back")
        assertTrue(retryAfter!!.toLong() in 1..ONE_WINDOW.toLong(), "Retry-After=$retryAfter")
        request("GET", "/api/public/v1/key", busy).use { assertEquals(KEY_BUDGET.toString(), it.header("X-RateLimit-Limit")) }
        assertRefused(429, "rate_limited", get("/api/public/v1/key", busy))

        // Spent by one key, not by its user, its organization or its address.
        assertEquals(200, get("/api/public/v1/key", quiet).first)
    }

    @Test
    fun `a request that is refused has still spent the key's budget`() {
        val key = createKey(login(newOwner())).str("key")

        // A read-only key hammering a write is refused every time — and pays
        // for every one, so the refusals run out like anything else.
        val statuses = (1..KEY_BUDGET + 1).map { post("/api/public/v1/test/act", token = key).first }
        assertEquals(List(KEY_BUDGET) { 403 } + 429, statuses)
        assertRefused(429, "rate_limited", get("/api/public/v1/key", key))
    }

    @Test
    fun `a key over budget is refused before it is looked up`() {
        val owner = newOwner()
        val session = login(owner)
        val created = createKey(session)
        val key = created.str("key")
        repeat(KEY_BUDGET) { assertEquals(200, get("/api/public/v1/key", key).first) }
        // Revoked now: a lookup would say so. Over budget says nothing about the key.
        assertEquals(200, post("/api/v1/me/api-keys/${created.str("id")}/revoke", token = session).first)
        assertRefused(429, "rate_limited", get("/api/public/v1/key", key))
    }

    @Test
    fun `an address that keeps sending unknown keys is refused, without locking out keys that exist`() {
        val owner = newOwner()
        val session = login(owner)
        val inUse = createKey(session, name = "in use").str("key")
        val neverUsed = createKey(session, name = "never used").str("key")
        val revokedLater = createKey(session, name = "revoked later")
        val elsewhere = mapOf("X-Forwarded-For" to "203.0.113.250")

        assertEquals(200, get("/api/public/v1/key", inUse).first)
        assertTrue(ApiRateLimit.isGood(TokenHasher.sha256Hex(inUse)), "The mark is in the shared store, not in this process")

        // A key that exists but stopped working is its holder's stale
        // credential, bounded by its own budget; it does not count against
        // the address its neighbours share.
        assertEquals(200, get("/api/public/v1/key", revokedLater.str("key")).first)
        assertEquals(200, post("/api/v1/me/api-keys/${revokedLater.str("id")}/revoke", token = session).first)
        repeat(3) { assertRefused(401, "api_key_revoked", get("/api/public/v1/key", revokedLater.str("key"))) }
        assertFalse(ApiRateLimit.isGood(TokenHasher.sha256Hex(revokedLater.str("key"))), "A key that stopped working loses its mark")

        // Tokens that name no key do. The stand-in gate asks who is calling
        // before the enforcement does, on every request: each is still counted
        // once, or the refusals below would start early.
        val statuses = (1..FAILURE_BUDGET + 1).map { get("/api/public/v1/key", "td_not-a-key-$it").first }
        assertEquals(List(FAILURE_BUDGET) { 401 } + 429, statuses)

        request("GET", "/api/public/v1/key", "td_one-more").use { response ->
            assertEquals(429, response.code)
            assertEquals("too_many_unknown_keys", json(response.body!!.string()).str("error"))
            val retryAfter = response.header("Retry-After")
            assertNotNull(retryAfter, "A refusal says when to come back")
            assertTrue(retryAfter!!.toLong() in 1..ONE_WINDOW.toLong())
            // This is not the key's budget, so it does not describe one.
            assertNull(response.header("X-RateLimit-Limit"))
        }

        // A runner sharing that address with whatever sent those is unaffected…
        assertEquals(200, get("/api/public/v1/key", inUse).first)
        // …a key the gateway has not seen work is not looked up from there…
        assertRefused(429, "too_many_unknown_keys", get("/api/public/v1/key", neverUsed))
        // …nor is one that stopped working…
        assertRefused(429, "too_many_unknown_keys", get("/api/public/v1/key", revokedLater.str("key")))
        // …and from anywhere else the unseen key is fine.
        assertEquals(200, get("/api/public/v1/key", neverUsed, headers = elsewhere).first)
    }

    @Test
    fun `requests with no key at all are counted against their address too`() {
        // Half with no header, half with a blank bearer: neither names a key,
        // and neither may be sent without limit.
        val statuses = (1..FAILURE_BUDGET).map { n ->
            if (n % 2 == 0) {
                val response = get("/api/public/v1/key")
                if (response.first == 401) assertEquals("missing_auth_header", json(response.second).str("error"))
                response.first
            } else {
                send("GET", "/api/public/v1/key", headers = mapOf("Authorization" to "Bearer  ")).first
            }
        }
        assertEquals(List(FAILURE_BUDGET) { 401 }, statuses)
        assertRefused(429, "too_many_unknown_keys", get("/api/public/v1/key"))
        // The dashboard's API is not this budget's business.
        assertRefused(401, "missing_auth_header", get("/api/v1/auth/me"))
    }

    @Test
    fun `the mark that exempts a key is shared between instances`() {
        val owner = newOwner()
        val session = login(owner)
        val seenElsewhere = createKey(session, name = "seen elsewhere")
        val digest = TokenHasher.sha256Hex(seenElsewhere.str("key"))

        // Another instance saw this key work: it says so in the store, and this
        // instance — which has never looked the key up — honours it.
        ApiRateLimit.markGood(digest)
        repeat(FAILURE_BUDGET + 1) { get("/api/public/v1/key", "td_junk-$it") }
        assertRefused(429, "too_many_unknown_keys", get("/api/public/v1/key", createKey(session, name = "unseen").str("key")))
        assertEquals(200, get("/api/public/v1/key", seenElsewhere.str("key")).first)

        // The mark lives as long as the exemption: five minutes from the last use.
        RedisClient.create(TestRedis.url).use { client ->
            client.connect().use { connection ->
                val ttl = connection.sync().ttl("rate:mark:good:$digest")
                assertTrue(ttl in 240..300, "ttl=$ttl")
            }
        }

        // When the key stops working, the mark goes with it for every instance,
        // whichever one finds out.
        ApiRateLimit.markGood(digest)
        assertEquals(200, post("/api/v1/me/api-keys/${seenElsewhere.str("id")}/revoke", token = session).first)
        assertRefused(401, "api_key_revoked", get("/api/public/v1/key", seenElsewhere.str("key")))
        assertFalse(ApiRateLimit.isGood(digest))
    }
}
