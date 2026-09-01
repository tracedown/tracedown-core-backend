package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.ApiKeys
import dev.tracedown.common.models.OrgAuditLog
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
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
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
        val postgres = PostgreSQLContainer("postgres:16-alpine")
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

    @Test
    fun `email change updates the profile and revokes other sessions`() {
        val otherToken = login(CHANGE_USER_EMAIL, CHANGE_USER_PASSWORD)
        val currentToken = login(CHANGE_USER_EMAIL, CHANGE_USER_PASSWORD)

        val newEmail = "changed-user@tracedown.dev"
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"$newEmail","currentPassword":"$CHANGE_USER_PASSWORD"}""",
            currentToken,
        )
        assertEquals(200, status, "Change response: $raw")
        assertEquals(newEmail, json(raw)["email"]!!.jsonPrimitive.content)

        // The calling session survives; every other session is revoked.
        assertEquals(200, get("/api/v1/auth/me", currentToken).first)
        assertEquals(401, get("/api/v1/auth/me", otherToken).first)

        // The new email is the login identity now.
        val freshToken = login(newEmail, CHANGE_USER_PASSWORD)
        assertNotNull(freshToken)
    }

    @Test
    fun `email change rejects a wrong password`() {
        val token = login(TAKEN_EMAIL, "Taken12345!")
        val (status, raw) = post(
            "/api/v1/me/email",
            """{"newEmail":"nope@tracedown.dev","currentPassword":"WrongPass123!"}""",
            token,
        )
        assertEquals(400, status)
        assertEquals("incorrect_password", json(raw)["error"]!!.jsonPrimitive.content)
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
        assertEquals(200, status, "Change response: $raw")
        assertEquals("totp-changed@tracedown.dev", json(raw)["email"]!!.jsonPrimitive.content)
    }
}
