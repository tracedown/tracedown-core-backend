package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.OrgAuditLog
import org.jetbrains.exposed.v1.core.and
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ResourcePermissions
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.controllers.auth.TotpUtil
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import dev.tracedown.common.models.EmailChangeRequests
import org.junit.jupiter.api.Assertions.assertNull
import kotlinx.serialization.json.boolean
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/** HTTP tests for /api/v1/me: personal data export and email change. */
@Testcontainers
class MeRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_me_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private val aesKeyBytes = AES_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private const val EXPORT_USER_EMAIL = "export-user@tracedown.dev"
        private const val EXPORT_USER_PASSWORD = "ExportTest123!"
        private const val CHANGE_USER_EMAIL = "change-user@tracedown.dev"
        private const val CHANGE_USER_PASSWORD = "ChangeTest123!"
        private const val TOTP_CHANGE_USER_EMAIL = "totp-change@tracedown.dev"
        private const val TOTP_CHANGE_USER_PASSWORD = "TotpChange123!"
        private const val TAKEN_EMAIL = "taken@tracedown.dev"
        private const val API_KEY_HASH = "supersecret-api-key-hash"
        private const val SECRET_VARIABLE_VALUE = "supersecret-variable-value"

        /** The workspace the export user holds a direct grant on. */
        private val GRANTED_RESOURCE_ID: UUID = UUID.randomUUID()

        /** Whoever invited the export user — the ACTOR on the subject-side entry. */
        private lateinit var inviterUserId: UUID

        private lateinit var totpSecret: ByteArray

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            serverPort = ServerSocket(0).use { it.localPort }

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
                // The export endpoint shares the strict auth tier; keep the
                // suite's repeated calls from tripping it.
                "rateLimit.enabled" to "false",
                // Off by default; this suite is about what it does when on.
                // EmailChangeDisabledTest covers the default.
                "platform.allowEmailChange" to "true",
            ))
            val mergedConfig = overrides.withFallback(ConfigFactory.load())

            val env = applicationEnvironment {
                config = HoconApplicationConfig(mergedConfig)
            }

            server = embeddedServer(Netty, env, configure = {
                connector { port = serverPort }
            })

            server.start(wait = false)
            Thread.sleep(2000)

            transaction {
                createExportUser()
                createUser(CHANGE_USER_EMAIL, CHANGE_USER_PASSWORD)
                createTotpChangeUser()
                createUser(TAKEN_EMAIL, "Taken12345!")
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
        }

        /** Creates a plain user with its own org and active membership. */
        private fun createUser(email: String, password: String): UUID {
            val userId = UUID.randomUUID()
            Users.insert {
                it[id] = userId
                it[Users.email] = email
                it[passwordHash] = BCrypt.withDefaults().hashToString(12, password.toCharArray())
                it[displayName] = email.substringBefore("@")
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "Me Test Org ${orgId.toString().take(6)}"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
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
            Users.update({ Users.id eq userId }) { it[selectedOrgId] = orgId }
            return userId
        }

        /** Export user carries every kind of secret the export must not leak. */
        private fun createExportUser() {
            val userId = createUser(EXPORT_USER_EMAIL, EXPORT_USER_PASSWORD)
            totpSecret = ByteArray(20).also { SecureRandom().nextBytes(it) }
            val (encrypted, iv) = TotpUtil.encryptSecret(totpSecret, aesKeyBytes)
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnrolledAt] = Instant.now()
            }
            val orgId = Users.selectAll().where { Users.id eq userId }.first()[Users.selectedOrgId]!!
            ApiKeys.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[createdBy] = userId
                it[name] = "export-test-key"
                it[keyHash] = API_KEY_HASH
                it[createdAt] = Instant.now()
            }
            OrgVariables.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[createdBy] = userId
                it[key] = "EXPORT_TEST_SECRET"
                it[value] = SECRET_VARIABLE_VALUE
                it[secret] = true
                it[OrgVariables.encrypted] = true
                it[valueIv] = "00000000000000000000000000000000"
                it[createdAt] = Instant.now()
                it[updatedAt] = Instant.now()
            }

            // A direct per-resource grant. resource_permissions never keys on an
            // account: the principal is the MEMBERSHIP, under principal_type
            // 'org_user' (the column's CHECK allows nothing else). The export
            // used to look for principal_type 'user' and the account id, which
            // the constraint makes impossible — so this row has to be here for
            // the assertion to mean anything.
            val membershipId = OrgUsers.selectAll()
                .where { OrgUsers.userId eq userId }
                .first()[OrgUsers.id]
            // `orgId` and `userId` are also column names on the tables below, and
            // the insert lambda's receiver shadows the outer values — bind them
            // first or the statement silently writes the Column, not the value.
            val grantOrgId = orgId
            val subjectId = userId
            ResourcePermissions.insert {
                it[id] = UUID.randomUUID()
                it[ResourcePermissions.orgId] = grantOrgId
                it[principalType] = "org_user"
                it[principalId] = membershipId
                it[resourceType] = "workspace"
                it[resourceId] = GRANTED_RESOURCE_ID
                it[permissions] = 2
            }

            // Two entries ABOUT the export user, written by somebody else — the
            // shape the export used to miss, since the actor column names the
            // inviter. They are found the two ways the subject is resolvable:
            inviterUserId = createUser("export-inviter@tracedown.dev", "Inviter12345!")
            // (a) the entity IS the account, so entity_id identifies them.
            OrgAuditLog.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = grantOrgId
                it[OrgAuditLog.userId] = inviterUserId
                it[action] = "invite.user"
                it[entityType] = "user"
                it[entityId] = subjectId.toString()
                it[entityDisplayName] = EXPORT_USER_EMAIL
                it[comment] = "Invited $EXPORT_USER_EMAIL"
                it[createdAt] = Instant.now()
            }
            // (b) the entity is the INVITE, not the person — only the address
            // in the payload ties this row to them.
            OrgAuditLog.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = grantOrgId
                it[OrgAuditLog.userId] = inviterUserId
                it[action] = "revoke.invite"
                it[entityType] = "invite"
                it[entityId] = UUID.randomUUID().toString()
                it[entityDisplayName] = EXPORT_USER_EMAIL
                it[createdAt] = Instant.now()
            }
        }

        private fun createTotpChangeUser() {
            val userId = createUser(TOTP_CHANGE_USER_EMAIL, TOTP_CHANGE_USER_PASSWORD)
            // Reuses the export user's secret material generator pattern.
            val secret = ByteArray(20).also { SecureRandom().nextBytes(it) }
            totpChangeSecret = secret
            val (encrypted, iv) = TotpUtil.encryptSecret(secret, aesKeyBytes)
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
                it[totpEnrolledAt] = Instant.now()
            }
        }

        private lateinit var totpChangeSecret: ByteArray
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String, token: String? = null): Pair<Int, String> {
        val builder = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .post(body.toRequestBody(jsonType))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.newCall(builder.build()).execute()
        return response.code to response.body!!.string()
    }

    private fun get(path: String, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .header("Authorization", "Bearer $token")
            .build()
        val response = client.newCall(request).execute()
        return response.code to response.body!!.string()
    }

    private fun delete(path: String, body: String, token: String): Pair<Int, String> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .header("Authorization", "Bearer $token")
            .delete(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        return response.code to response.body!!.string()
    }

    private fun json(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun login(email: String, password: String): String {
        val (status, raw) = post("/api/v1/auth/login", """{"email":"$email","password":"$password"}""")
        assertEquals(200, status, "Login response: $raw")
        return json(raw)["token"]!!.jsonPrimitive.content
    }

    /**
     * A code the account has not spent yet.
     *
     * TotpPolicy.consumes accepts only a STRICTLY newer time step than the one
     * the account last consumed, so a code is single-use and so is its whole
     * 30-second window: a real user presenting a second code has necessarily
     * waited for the window to roll. This suite runs several second factors per
     * account within one window, so it clears the marker instead of sleeping —
     * the same state a rolled window would leave, without the 30 seconds. The
     * guard itself is covered by TotpPolicyTest.
     */
    private fun freshTotpCode(email: String, secret: ByteArray): String {
        transaction {
            Users.update({ Users.email eq email }) { it[totpLastStep] = null }
        }
        return TotpUtil.generateCode(secret)
    }

    private fun loginTotp(email: String, password: String, secret: ByteArray): String {
        val (_, loginRaw) = post("/api/v1/auth/login", """{"email":"$email","password":"$password"}""")
        val challenge = json(loginRaw)["challenge"]!!.jsonPrimitive.content
        val code = freshTotpCode(email, secret)
        val (status, raw) = post("/api/v1/auth/login/totp", """{"challenge":"$challenge","code":"$code"}""")
        assertEquals(200, status, "TOTP login response: $raw")
        return json(raw)["token"]!!.jsonPrimitive.content
    }

    // ── Export ──

    @Test
    fun `export returns all sections and never leaks secrets`() {
        val token = loginTotp(EXPORT_USER_EMAIL, EXPORT_USER_PASSWORD, totpSecret)
        val (status, raw) = get("/api/v1/me/export", token)
        assertEquals(200, status, "Export response: $raw")

        val body = json(raw)
        assertEquals(2, body["exportVersion"]!!.jsonPrimitive.content.toInt())
        assertNotNull(body["generatedAt"])
        for (section in listOf(
            "profile", "sessions", "orgMemberships", "resourceGrants", "auditLog",
            "apiKeys", "notificationSilences", "variables", "sentInvites", "notificationLog",
        )) {
            assertNotNull(body[section], "missing export section: $section")
        }

        val profile = body["profile"]!!.jsonObject
        assertEquals(EXPORT_USER_EMAIL, profile["email"]!!.jsonPrimitive.content)
        assertFalse(profile.containsKey("passwordHash"), "profile must not carry the password hash")
        assertFalse(profile.containsKey("totpSecretEncrypted"), "profile must not carry the TOTP secret")

        // A section that can never return a row is indistinguishable from
        // "you hold none of these" — assert the contents, not the key.
        val grants = body["resourceGrants"]!!.jsonArray
        assertEquals(1, grants.size, "the user's direct resource grant must be disclosed")
        assertEquals(
            GRANTED_RESOURCE_ID.toString(),
            grants.single().jsonObject["resourceId"]!!.jsonPrimitive.content,
        )

        // Entries about the caller count, not only the ones they caused: the
        // actor column names the inviter, so filtering on it hid the entry that
        // holds the caller's own address.
        val audit = body["auditLog"]!!.jsonArray
        val subjectSide = audit
            .filter { it.jsonObject["role"]!!.jsonPrimitive.content == "subject" }
            .map { it.jsonObject["action"]!!.jsonPrimitive.content }
        assertTrue(
            subjectSide.contains("invite.user"),
            "the entry whose ENTITY is the caller must be disclosed: $subjectSide",
        )
        assertTrue(
            subjectSide.contains("revoke.invite"),
            "the entry that names the caller only by address must be disclosed too: $subjectSide",
        )

        val apiKeys = body["apiKeys"]!!.jsonArray
        assertEquals("export-test-key", apiKeys.single().jsonObject["name"]!!.jsonPrimitive.content)
        val variables = body["variables"]!!.jsonArray
        assertEquals("EXPORT_TEST_SECRET", variables.single().jsonObject["key"]!!.jsonPrimitive.content)
        assertTrue(body["sessions"]!!.jsonArray.isNotEmpty(), "the current session must be listed")

        // Raw-body sweep: no secret material of any kind may appear anywhere.
        assertFalse(raw.contains(API_KEY_HASH), "API key material leaked into export")
        assertFalse(raw.contains(SECRET_VARIABLE_VALUE), "variable value leaked into export")
        assertFalse(raw.contains(token), "session token leaked into export")
        assertFalse(raw.contains("\$2a\$"), "a bcrypt hash leaked into export")
    }

    @Test
    fun `export requires authentication`() {
        val request = Request.Builder().url("http://localhost:$serverPort/api/v1/me/export").build()
        assertEquals(401, client.newCall(request).execute().code)
    }

    // ── Email change ──

    /**
     * The mails the gateway queued, newest FIRST (the publisher pushes to the
     * head of the list email-service consumes). Each is a JSON job.
     */
    private fun queuedMails(): List<JsonObject> {
        val redis = io.lettuce.core.RedisClient.create(TestRedis.url).connect()
        return redis.use { conn ->
            conn.sync().lrange("email_queue", 0, -1).map { Json.parseToJsonElement(it).jsonObject }
        }
    }

    private fun newestMail(to: String, type: String? = null): JsonObject? = queuedMails().firstOrNull {
        it["to"]?.jsonPrimitive?.content == to && (type == null || it["type"]?.jsonPrimitive?.content == type)
    }

    /** The confirmation token in the newest change-request mail sent to [address]. */
    private fun confirmTokenFor(address: String): String {
        val mail = newestMail(address, "system.email-change")
            ?: throw AssertionError("no confirmation mail queued for $address")
        return mail["vars"]!!.jsonObject["confirmLink"]!!.jsonPrimitive.content.substringAfterLast("/confirm-email/")
    }

    private fun userIdOf(email: String): UUID = transaction { Users.selectAll().where { Users.email eq email }.first()[Users.id] }

    private fun request(token: String, newEmail: String, password: String): Pair<Int, String> =
        post("/api/v1/me/email", """{"newEmail":"$newEmail","currentPassword":"$password"}""", token)

    private fun confirm(token: String): Pair<Int, String> =
        post("/api/v1/me/email/confirm", """{"token":"$token"}""")

    @Test
    fun `an email change is written only when the new address confirms it, and then signs every session out`() {
        val otherToken = login(CHANGE_USER_EMAIL, CHANGE_USER_PASSWORD)
        val currentToken = login(CHANGE_USER_EMAIL, CHANGE_USER_PASSWORD)

        val newEmail = "changed-user@tracedown.dev"
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"$newEmail","currentPassword":"$CHANGE_USER_PASSWORD"}""",
            currentToken,
        )
        assertEquals(200, status, "Request response: $raw")
        assertEquals(newEmail, json(raw)["newEmail"]!!.jsonPrimitive.content)

        // Nothing has changed yet: the old address signs in, the new one does not,
        // and both sessions are still live.
        assertEquals(CHANGE_USER_EMAIL, json(get("/api/v1/auth/me", currentToken).second)["user"]!!.jsonObject["email"]!!.jsonPrimitive.content)
        assertEquals(200, get("/api/v1/auth/me", otherToken).first)
        assertEquals(401, post("/api/v1/auth/login", """{"email":"$newEmail","password":"$CHANGE_USER_PASSWORD"}""").first)

        // The new address got the link — and nothing the account typed, since
        // the recipient may be a stranger; the old one got a notice with no
        // link in it.
        val toNew = newestMail(newEmail)!!
        assertEquals("system.email-change", toNew["type"]?.jsonPrimitive?.content)
        assertNull(toNew["vars"]!!.jsonObject["userName"], "no account-chosen text in mail to an unknown address")
        val toOld = newestMail(CHANGE_USER_EMAIL)!!
        assertEquals("system.email-change-notice", toOld["type"]?.jsonPrimitive?.content)
        assertEquals(newEmail, toOld["vars"]!!.jsonObject["newEmail"]!!.jsonPrimitive.content)
        assertNull(toOld["vars"]!!.jsonObject["confirmLink"], "the old address must not be able to confirm")

        // Following the link writes the change and ends every session.
        val (confirmStatus, confirmRaw) = confirm(confirmTokenFor(newEmail))
        assertEquals(200, confirmStatus, "Confirm response: $confirmRaw")
        assertEquals(newEmail, json(confirmRaw)["email"]!!.jsonPrimitive.content)
        assertEquals(401, get("/api/v1/auth/me", currentToken).first)
        assertEquals(401, get("/api/v1/auth/me", otherToken).first)

        // The old address is told it went through; the change is on the record
        // of the organization the account is in.
        assertEquals("system.email-changed", newestMail(CHANGE_USER_EMAIL)!!["type"]?.jsonPrimitive?.content)
        val userId = userIdOf(newEmail)
        val audited = transaction {
            OrgAuditLog.selectAll().where { (OrgAuditLog.userId eq userId) and (OrgAuditLog.action eq "update.email") }.count()
        }
        assertEquals(1, audited)

        // The new email is the login identity now, the old one is nobody's, and the link is spent.
        assertNotNull(login(newEmail, CHANGE_USER_PASSWORD))
        assertEquals(401, post("/api/v1/auth/login", """{"email":"$CHANGE_USER_EMAIL","password":"$CHANGE_USER_PASSWORD"}""").first)
        assertEquals(400, confirm(confirmTokenFor(newEmail)).first)
    }

    @Test
    fun `email change rejects a wrong password, and mails nothing`() {
        val token = login(TAKEN_EMAIL, "Taken12345!")
        val before = queuedMails().size
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"nope@tracedown.dev","currentPassword":"WrongPass123!"}""",
            token,
        )
        assertEquals(400, status)
        assertEquals("incorrect_password", json(raw)["error"]!!.jsonPrimitive.content)
        assertEquals(before, queuedMails().size)
    }

    @Test
    fun `email change rejects an address already in use, case-insensitively`() {
        val token = loginTotp(EXPORT_USER_EMAIL, EXPORT_USER_PASSWORD, totpSecret)
        val code = freshTotpCode(EXPORT_USER_EMAIL, totpSecret)
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"${TAKEN_EMAIL.uppercase()}","currentPassword":"$EXPORT_USER_PASSWORD","code":"$code"}""",
            token,
        )
        assertEquals(400, status)
        assertEquals("email_taken", json(raw)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an address taken between the request and the link is refused at the link`() {
        val email = "race-${UUID.randomUUID()}@tracedown.dev"
        val password = "RaceTest123!"
        transaction { createUser(email, password) }
        val token = login(email, password)
        val wanted = "wanted-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, post("/api/v1/me/email", """{"newEmail":"$wanted","currentPassword":"$password"}""", token).first)

        // Somebody else gets there first.
        transaction { createUser(wanted, "Other12345!") }

        val (status, raw) = confirm(confirmTokenFor(wanted))
        assertEquals(400, status)
        assertEquals("email_taken", json(raw)["error"]!!.jsonPrimitive.content)
        assertEquals(email, json(get("/api/v1/auth/me", token).second)["user"]!!.jsonObject["email"]!!.jsonPrimitive.content, "unchanged")
    }

    @Test
    fun `a second request supersedes the first and is rate limited`() {
        val email = "again-${UUID.randomUUID()}@tracedown.dev"
        val password = "AgainTest123!"
        transaction { createUser(email, password) }
        val token = login(email, password)
        val first = "first-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, post("/api/v1/me/email", """{"newEmail":"$first","currentPassword":"$password"}""", token).first)
        val firstToken = confirmTokenFor(first)

        // A second one straight away is too soon — and says so by name, before
        // the password is looked at.
        val second = "second-${UUID.randomUUID()}@tracedown.dev"
        val (tooSoon, tooSoonRaw) = request(token, second, "not-even-checked")
        assertEquals(429, tooSoon)
        assertEquals("email_change_cooldown", json(tooSoonRaw)["error"]!!.jsonPrimitive.content)

        // …so age the first; the second then supersedes it.
        transaction {
            EmailChangeRequests.update({ EmailChangeRequests.userId eq Users.selectAll().where { Users.email eq email }.first()[Users.id] }) {
                it[createdAt] = Instant.now().minusSeconds(120)
            }
        }
        assertEquals(200, post("/api/v1/me/email", """{"newEmail":"$second","currentPassword":"$password"}""", token).first)
        assertEquals(400, confirm(firstToken).first, "the earlier link is dead")
        assertEquals(200, confirm(confirmTokenFor(second)).first)
        assertNotNull(login(second, password))
    }

    @Test
    fun `an unknown or expired link changes nothing`() {
        assertEquals(400, confirm("never-issued").first)

        val email = "late-${UUID.randomUUID()}@tracedown.dev"
        val password = "LateTest123!"
        transaction { createUser(email, password) }
        val token = login(email, password)
        val wanted = "late-wanted-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, post("/api/v1/me/email", """{"newEmail":"$wanted","currentPassword":"$password"}""", token).first)
        transaction {
            EmailChangeRequests.update({ EmailChangeRequests.newEmail eq wanted }) { it[expiresAt] = Instant.now().minusSeconds(1) }
        }

        val (status, raw) = confirm(confirmTokenFor(wanted))
        assertEquals(400, status)
        assertEquals("invalid_token", json(raw)["error"]!!.jsonPrimitive.content)
        assertEquals(200, get("/api/v1/auth/me", token).first, "the session is untouched")
    }

    @Test
    fun `one address is mailed at most three times an hour, whoever asks`() {
        val wanted = "popular-${UUID.randomUUID()}@tracedown.dev"
        val password = "Popular123!"
        val tokens = (1..4).map {
            val email = "asker-$it-${UUID.randomUUID()}@tracedown.dev"
            transaction { createUser(email, password) }
            login(email, password)
        }
        tokens.take(3).forEach { assertEquals(200, request(it, wanted, password).first) }

        val (status, raw) = request(tokens[3], wanted, password)
        assertEquals(429, status)
        assertEquals("email_change_cooldown", json(raw)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a password change voids the pending request`() {
        val email = "cancel-${UUID.randomUUID()}@tracedown.dev"
        val password = "Cancel12345!"
        transaction { createUser(email, password) }
        val token = login(email, password)
        val wanted = "cancel-wanted-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, request(token, wanted, password).first)
        val link = confirmTokenFor(wanted)

        // The holder reads the notice and does what it says.
        val (status, raw) = post(
            "/api/v1/auth/change-password",
            """{"currentPassword":"$password","newPassword":"Changed12345!"}""",
            token,
        )
        assertEquals(200, status, raw)

        assertEquals(400, confirm(link).first, "the link minted under the old password is dead")
        assertEquals(email, json(get("/api/v1/auth/me", token).second)["user"]!!.jsonObject["email"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a password reset voids the pending request`() {
        val email = "reset-${UUID.randomUUID()}@tracedown.dev"
        val password = "Reset12345!"
        transaction { createUser(email, password) }
        val token = login(email, password)
        val wanted = "reset-wanted-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, request(token, wanted, password).first)
        val link = confirmTokenFor(wanted)

        assertEquals(200, post("/api/v1/auth/password-reset", """{"email":"$email"}""").first)
        val resetMail = newestMail(email, "system.password-reset")!!
        val resetToken = resetMail["vars"]!!.jsonObject["resetLink"]!!.jsonPrimitive.content.substringAfterLast("/")
        assertEquals(200, post("/api/v1/auth/password-reset/confirm", """{"token":"$resetToken","newPassword":"Reset67890!"}""").first)

        assertEquals(400, confirm(link).first)
        assertNotNull(login(email, "Reset67890!"), "still the same address")
    }

    @Test
    fun `an account switched off or closed after asking cannot be moved by its link`() {
        val email = "off-${UUID.randomUUID()}@tracedown.dev"
        val password = "OffTest12345!"
        val userId = transaction { createUser(email, password) }
        val token = login(email, password)
        val wanted = "off-wanted-${UUID.randomUUID()}@tracedown.dev"
        assertEquals(200, request(token, wanted, password).first)
        val link = confirmTokenFor(wanted)

        transaction { Users.update({ Users.id eq userId }) { it[isActive] = false } }
        assertEquals(400, confirm(link).first)
        assertEquals(email, transaction { Users.selectAll().where { Users.id eq userId }.first()[Users.email] })

        // Closed and reclaimed by a new signup: nothing of the old request survives.
        transaction {
            Users.update({ Users.id eq userId }) { it[isActive] = true; it[deleted] = true; it[deletedAt] = Instant.now() }
            dev.tracedown.common.onboarding.AccountService.reclaimSoftDeleted(email, "Fresh12345!", "fresh")
        }
        assertEquals(400, confirm(link).first)
        assertEquals(0L, transaction { EmailChangeRequests.selectAll().where { EmailChangeRequests.userId eq userId }.count() })
    }

    @Test
    fun `an address held by a closed, not yet purged account is taken`() {
        val closed = "closed-${UUID.randomUUID()}@tracedown.dev"
        transaction {
            val id = createUser(closed, "Closed12345!")
            Users.update({ Users.id eq id }) { it[deleted] = true; it[deletedAt] = Instant.now() }
        }
        val token = login(TAKEN_EMAIL, "Taken12345!")

        val (status, raw) = request(token, closed, "Taken12345!")
        assertEquals(400, status)
        assertEquals("email_taken", json(raw)["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the profile capabilities say the install allows it`() {
        val token = login(TAKEN_EMAIL, "Taken12345!")
        val caps = json(get("/api/v1/auth/profile/capabilities", token).second)
        assertTrue(caps["allowEmailChange"]!!.jsonPrimitive.boolean)
    }

    // ── Account closure gate ──

    @Test
    fun `closing an account is refused before the password is looked at`() {
        // `platform.allowAccountClosure` keeps its default (off) on this
        // server, and AuthRoutes gates on it before AuthController.deleteAccount
        // ever re-verifies identity. A switched-off endpoint has to answer
        // identically whatever credentials arrive: verifying first would turn a
        // disabled feature into a password oracle, and would spend a bcrypt
        // comparison on every request to a route that can never do anything.
        val token = login(TAKEN_EMAIL, "Taken12345!")

        val (wrongStatus, wrongRaw) = delete(
            "/api/v1/auth/account", """{"password":"NotThePassword1!"}""", token,
        )
        assertEquals(403, wrongStatus, "Closure response: $wrongRaw")
        assertEquals("account_closure_disabled", json(wrongRaw)["error"]!!.jsonPrimitive.content)

        val (rightStatus, rightRaw) = delete(
            "/api/v1/auth/account", """{"password":"Taken12345!"}""", token,
        )
        assertEquals(403, rightStatus, "Closure response: $rightRaw")
        assertEquals(
            "account_closure_disabled",
            json(rightRaw)["error"]!!.jsonPrimitive.content,
            "the correct password must not be distinguishable from a wrong one here",
        )

        transaction {
            assertFalse(Users.selectAll().where { Users.email eq TAKEN_EMAIL }.first()[Users.deleted])
        }
    }

    @Test
    fun `email change requires a TOTP code when the user is enrolled`() {
        val token = loginTotp(TOTP_CHANGE_USER_EMAIL, TOTP_CHANGE_USER_PASSWORD, totpChangeSecret)

        // Without a code: rejected.
        val (missingStatus, missingRaw) = post(
            "/api/v1/me/email",
            """{"newEmail":"totp-changed@tracedown.dev","currentPassword":"$TOTP_CHANGE_USER_PASSWORD"}""",
            token,
        )
        assertEquals(400, missingStatus)
        assertEquals("invalid_totp_code", json(missingRaw)["error"]!!.jsonPrimitive.content)

        // With a valid code: accepted.
        val code = freshTotpCode(TOTP_CHANGE_USER_EMAIL, totpChangeSecret)
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"totp-changed@tracedown.dev","currentPassword":"$TOTP_CHANGE_USER_PASSWORD","code":"$code"}""",
            token,
        )
        assertEquals(200, status, "Request response: $raw")
        assertEquals("totp-changed@tracedown.dev", json(raw)["newEmail"]!!.jsonPrimitive.content)
    }
}
