package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
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
import kotlinx.serialization.json.boolean
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

@Testcontainers
class TotpIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_totp_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private val aesKeyBytes = AES_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        private lateinit var totpSecret: ByteArray

        private const val TOTP_USER_EMAIL = "totp-user@tracedown.dev"
        private const val TOTP_USER_PASSWORD = "TotpTest123!"
        private const val ENFORCED_USER_EMAIL = "enforced@tracedown.dev"
        private const val ENFORCED_USER_PASSWORD = "Enforced123!"
        private const val SELF_ENROLL_USER_EMAIL = "self-enroll@tracedown.dev"
        private const val SELF_ENROLL_USER_PASSWORD = "SelfEnroll123!"

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

            createTotpUser()
            createEnforcedUser()
            createSelfEnrollUser()
        }

        /**
         * Gives [userId] its own org with an active membership, so login is
         * permitted (an account with no active org cannot sign in). The org has
         * no TOTP enforcement, so login returns a session (or a challenge when
         * the user already has TOTP) rather than a setup requirement.
         */
        private fun attachOrg(userId: UUID) {
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "TOTP Test Org ${orgId.toString().take(6)}"
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
        }

        /**
         * Creates a TOTP-less user in an org without enforcement, so login
         * returns a session directly — letting the test drive authenticated
         * self-service enrollment via /auth/totp/enroll.
         */
        private fun createSelfEnrollUser() {
            val passwordHash = BCrypt.withDefaults().hashToString(12, SELF_ENROLL_USER_PASSWORD.toCharArray())
            transaction {
                val userId = UUID.randomUUID()
                Users.insert {
                    it[id] = userId
                    it[email] = SELF_ENROLL_USER_EMAIL
                    it[Users.passwordHash] = passwordHash
                    it[displayName] = "Self Enroll User"
                    it[isActive] = true
                    it[deleted] = false
                    it[totpEnabled] = false
                    it[createdAt] = Instant.now()
                }
                attachOrg(userId)
            }
        }

        private fun createTotpUser() {
            // Generate a TOTP secret and encrypt it
            totpSecret = ByteArray(20).also { SecureRandom().nextBytes(it) }
            val (encrypted, iv) = TotpUtil.encryptSecret(totpSecret, aesKeyBytes)
            val passwordHash = BCrypt.withDefaults().hashToString(12, TOTP_USER_PASSWORD.toCharArray())

            transaction {
                val userId = UUID.randomUUID()
                Users.insert {
                    it[id] = userId
                    it[email] = TOTP_USER_EMAIL
                    it[Users.passwordHash] = passwordHash
                    it[displayName] = "TOTP User"
                    it[isActive] = true
                    it[deleted] = false
                    it[totpEnabled] = true
                    it[totpSecretEncrypted] = encrypted
                    it[totpSecretIv] = iv
                    it[totpEnrolledAt] = Instant.now()
                    it[createdAt] = Instant.now()
                }
                attachOrg(userId)
            }
        }

        /**
         * Creates a user without TOTP in an org that has totpRequired=true.
         * This user should get totpSetupRequired on login.
         */
        private fun createEnforcedUser() {
            val passwordHash = BCrypt.withDefaults().hashToString(12, ENFORCED_USER_PASSWORD.toCharArray())

            transaction {
                // Get the bootstrap org and enable TOTP enforcement on it
                val org = Organizations.selectAll().first()
                val orgId = org[Organizations.id]

                Organizations.update({ Organizations.id eq orgId }) {
                    it[totpRequired] = true
                }

                val userId = UUID.randomUUID()
                Users.insert {
                    it[id] = userId
                    it[email] = ENFORCED_USER_EMAIL
                    it[Users.passwordHash] = passwordHash
                    it[displayName] = "Enforced User"
                    it[isActive] = true
                    it[deleted] = false
                    it[totpEnabled] = false
                    it[selectedOrgId] = orgId
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
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
        }
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String): Pair<Int, JsonObject> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .post(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return response.code to json
    }

    private fun postAuth(path: String, body: String, token: String): Pair<Int, JsonObject> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .header("Authorization", "Bearer $token")
            .post(body.toRequestBody(jsonType))
            .build()
        val response = client.newCall(request).execute()
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return response.code to json
    }

    private fun get(path: String, token: String): Pair<Int, JsonObject> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .header("Authorization", "Bearer $token")
            .build()
        val response = client.newCall(request).execute()
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return response.code to json
    }

    @Test
    fun `login with TOTP user returns challenge instead of session`() {
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$TOTP_USER_EMAIL","password":"$TOTP_USER_PASSWORD"}""",
        )

        assertEquals(200, status, "Response body: $body")
        assertTrue(body["totpRequired"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(body["challenge"]?.jsonPrimitive?.content)
        // token must be null
        val tokenValue = body["token"]
        assertTrue(tokenValue == null || tokenValue is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `TOTP verification with valid code grants session`() {
        // Step 1: login to get challenge
        val (_, loginBody) = post(
            "/api/v1/auth/login",
            """{"email":"$TOTP_USER_EMAIL","password":"$TOTP_USER_PASSWORD"}""",
        )
        val challenge = loginBody["challenge"]!!.jsonPrimitive.content

        // Step 2: generate valid TOTP code and verify
        val code = TotpUtil.generateCode(totpSecret)
        val (status, verifyBody) = post(
            "/api/v1/auth/login/totp",
            """{"challenge":"$challenge","code":"$code"}""",
        )

        assertEquals(200, status)
        val token = verifyBody["token"]?.jsonPrimitive?.content
        assertNotNull(token)

        // Step 3: use the token
        val (meStatus, _) = get("/api/v1/auth/me", token!!)
        assertEquals(200, meStatus)
    }

    @Test
    fun `TOTP verification with wrong code returns 401`() {
        val (_, loginBody) = post(
            "/api/v1/auth/login",
            """{"email":"$TOTP_USER_EMAIL","password":"$TOTP_USER_PASSWORD"}""",
        )
        val challenge = loginBody["challenge"]!!.jsonPrimitive.content

        val (status, _) = post(
            "/api/v1/auth/login/totp",
            """{"challenge":"$challenge","code":"000000"}""",
        )

        assertEquals(401, status)
    }

    @Test
    fun `TOTP verification with invalid challenge returns 401`() {
        val (status, _) = post(
            "/api/v1/auth/login/totp",
            """{"challenge":"bogus","code":"123456"}""",
        )

        assertEquals(401, status)
    }

    @Test
    fun `non-TOTP user in TOTP-enforced org gets setup required`() {
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ENFORCED_USER_EMAIL","password":"$ENFORCED_USER_PASSWORD"}""",
        )

        assertEquals(200, status, "Response body: $body")
        assertTrue(body["totpSetupRequired"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(body["setupToken"]?.jsonPrimitive?.content)
        // No session token should be returned
        val tokenValue = body["token"]
        assertTrue(tokenValue == null || tokenValue is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `TOTP setup flow completes enrollment and grants session`() {
        // Step 1: login gets setupToken
        val (_, loginBody) = post(
            "/api/v1/auth/login",
            """{"email":"$ENFORCED_USER_EMAIL","password":"$ENFORCED_USER_PASSWORD"}""",
        )
        val setupToken = loginBody["setupToken"]!!.jsonPrimitive.content

        // Step 2: begin setup — get secret and confirmToken
        val (setupStatus, setupBody) = post(
            "/api/v1/auth/totp/setup",
            """{"setupToken":"$setupToken"}""",
        )
        assertEquals(200, setupStatus, "Setup response: $setupBody")
        val secret = setupBody["secret"]!!.jsonPrimitive.content
        assertNotNull(setupBody["otpauthUri"]?.jsonPrimitive?.content)
        val confirmToken = setupBody["confirmToken"]!!.jsonPrimitive.content

        // Step 3: decode secret from base32 and generate a valid code
        val secretBytes = decodeBase32(secret)
        val code = TotpUtil.generateCode(secretBytes)

        // Step 4: confirm setup with the code
        val (confirmStatus, confirmBody) = post(
            "/api/v1/auth/totp/setup/confirm",
            """{"confirmToken":"$confirmToken","code":"$code"}""",
        )
        assertEquals(200, confirmStatus, "Confirm response: $confirmBody")
        assertNotNull(confirmBody["token"]?.jsonPrimitive?.content)

        // Step 5: subsequent login should now require TOTP verification, not setup
        val (loginStatus2, loginBody2) = post(
            "/api/v1/auth/login",
            """{"email":"$ENFORCED_USER_EMAIL","password":"$ENFORCED_USER_PASSWORD"}""",
        )
        assertEquals(200, loginStatus2)
        assertTrue(loginBody2["totpRequired"]?.jsonPrimitive?.boolean ?: false)
        assertNotNull(loginBody2["challenge"]?.jsonPrimitive?.content)
        assertEquals(false, loginBody2["totpSetupRequired"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `authenticated user can self-enroll TOTP without a setup token`() {
        // Step 1: login (no org → no enforcement) returns a session directly
        val (loginStatus, loginBody) = post(
            "/api/v1/auth/login",
            """{"email":"$SELF_ENROLL_USER_EMAIL","password":"$SELF_ENROLL_USER_PASSWORD"}""",
        )
        assertEquals(200, loginStatus, "Login response: $loginBody")
        val token = loginBody["token"]!!.jsonPrimitive.content

        // Step 2: begin enrollment via the authenticated endpoint — no setupToken
        val (enrollStatus, enrollBody) = postAuth("/api/v1/auth/totp/enroll", "{}", token)
        assertEquals(200, enrollStatus, "Enroll response: $enrollBody")
        val secret = enrollBody["secret"]!!.jsonPrimitive.content
        assertNotNull(enrollBody["otpauthUri"]?.jsonPrimitive?.content)
        val confirmToken = enrollBody["confirmToken"]!!.jsonPrimitive.content

        // Step 3: confirm with a valid code (reuses the existing confirm endpoint)
        val code = TotpUtil.generateCode(decodeBase32(secret))
        val (confirmStatus, confirmBody) = post(
            "/api/v1/auth/totp/setup/confirm",
            """{"confirmToken":"$confirmToken","code":"$code"}""",
        )
        assertEquals(200, confirmStatus, "Confirm response: $confirmBody")
        assertNotNull(confirmBody["token"]?.jsonPrimitive?.content)

        // Step 4: subsequent login now requires TOTP verification
        val (_, loginBody2) = post(
            "/api/v1/auth/login",
            """{"email":"$SELF_ENROLL_USER_EMAIL","password":"$SELF_ENROLL_USER_PASSWORD"}""",
        )
        assertTrue(loginBody2["totpRequired"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    fun `self-enroll requires authentication`() {
        val (status, _) = post("/api/v1/auth/totp/enroll", "{}")
        assertEquals(401, status)
    }

    private fun decodeBase32(encoded: String): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        var buffer = 0
        var bitsLeft = 0
        val output = mutableListOf<Byte>()
        for (c in encoded.uppercase()) {
            val value = alphabet.indexOf(c)
            if (value < 0) continue
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                output.add((buffer shr bitsLeft).toByte())
            }
        }
        return output.toByteArray()
    }
}
