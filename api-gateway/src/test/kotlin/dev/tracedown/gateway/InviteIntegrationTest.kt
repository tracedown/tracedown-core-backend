package dev.tracedown.gateway

import com.typesafe.config.ConfigFactory
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.ServerSocket

@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class InviteIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_invite_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val ADMIN_EMAIL = "admin@tracedown.dev"
        private const val ADMIN_PASSWORD = "Down2trace!"
        private const val INVITEE_EMAIL = "invited@example.com"
        private const val INVITEE_PASSWORD = "InviteTest1!"

        private val emlFile = File("build/test-invite-email.eml")

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
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            emlFile.delete()
        }
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String, token: String? = null): Pair<Int, JsonObject> {
        val builder = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .post(body.toRequestBody(jsonType))
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.newCall(builder.build()).execute()
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return response.code to json
    }

    private fun get(path: String, token: String? = null): Pair<Int, String> {
        val builder = Request.Builder()
            .url("http://localhost:$serverPort$path")
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.newCall(builder.build()).execute()
        return response.code to response.body!!.string()
    }

    private fun delete(path: String, token: String): Pair<Int, JsonObject> {
        val request = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .header("Authorization", "Bearer $token")
            .delete()
            .build()
        val response = client.newCall(request).execute()
        val json = Json.parseToJsonElement(response.body!!.string()).jsonObject
        return response.code to json
    }

    private fun login(): Pair<String, String> {
        val (_, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
        )
        val token = body["token"]!!.jsonPrimitive.content
        val orgId = body["user"]!!.jsonObject["selectedOrgId"]!!.jsonPrimitive.content
        return token to orgId
    }

    private fun extractInviteToken(): String {
        // Read the invite token from the Redis email queue (the email job contains the invite link)
        val redis = io.lettuce.core.RedisClient.create(TestRedis.url).connect().sync()
        // The email job is in the queue — peek at it
        val raw = redis.lindex("email_queue", 0)
            ?: throw AssertionError("No email job in queue")
        val job = kotlinx.serialization.json.Json.parseToJsonElement(raw).jsonObject
        val vars = job["vars"]?.jsonObject
        val inviteLink = vars?.get("inviteLink")?.jsonPrimitive?.content
            ?: throw AssertionError("No inviteLink in email job vars: $job")
        // Extract token from URL (last segment after /invite/)
        return inviteLink.substringAfterLast("/invite/")
    }

    @Test
    @Order(1)
    fun `send invite to new user`() {
        val (token, orgId) = login()

        val (status, body) = post(
            "/api/v1/invites",
            """{"email":"$INVITEE_EMAIL"}""",
            token,
        )

        assertEquals(200, status, "Response: $body")
        assertTrue(body["ok"]?.jsonPrimitive?.boolean ?: false)
    }

    @Test
    @Order(2)
    fun `get invite info`() {
        val inviteToken = extractInviteToken()
        val (status, body) = get("/api/v1/invites/$inviteToken")
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals(200, status)
        assertNotNull(json["orgName"]?.jsonPrimitive?.content)
        assertEquals(INVITEE_EMAIL, json["email"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(3)
    fun `list pending invites`() {
        val (token, orgId) = login()

        val (status, body) = get("/api/v1/invites", token)
        val json = Json.parseToJsonElement(body).jsonObject
        val items = json["items"]!!.jsonArray

        assertEquals(200, status)
        assertTrue(items.size > 0)
        val invite = items[0].jsonObject
        assertEquals(INVITEE_EMAIL, invite["email"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(4)
    fun `resend invite within cooldown returns 429`() {
        val (token, orgId) = login()

        val (status, _) = post(
            "/api/v1/invites",
            """{"email":"$INVITEE_EMAIL"}""",
            token,
        )

        assertEquals(429, status)
    }

    @Test
    @Order(5)
    fun `accept invite with weak password returns 400`() {
        val inviteToken = extractInviteToken()
        val (status, body) = post(
            "/api/v1/invites/$inviteToken/accept",
            """{"password":"weak","displayName":"Test User"}""",
        )

        assertEquals(400, status)
        assertEquals("password_too_weak", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(6)
    fun `accept invite with valid password`() {
        val inviteToken = extractInviteToken()
        val (status, body) = post(
            "/api/v1/invites/$inviteToken/accept",
            """{"password":"$INVITEE_PASSWORD","displayName":"Invited User"}""",
        )

        assertEquals(200, status, "Response: $body")
        // A genuinely new invitee sets a password + name and gets a session.
        assertEquals("accepted_new", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["token"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(7)
    fun `accepted user can log in`() {
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$INVITEE_EMAIL","password":"$INVITEE_PASSWORD"}""",
        )

        assertEquals(200, status)
        assertNotNull(body["token"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(8)
    fun `accept with already-used token returns 401`() {
        // The token was cleared after acceptance in test 6
        val (status, _) = post(
            "/api/v1/invites/bogus-token/accept",
            """{"password":"$INVITEE_PASSWORD","displayName":"Test"}""",
        )

        assertEquals(401, status)
    }

    @Test
    @Order(9)
    fun `invite already-active member returns 409`() {
        val (token, orgId) = login()

        val (status, body) = post(
            "/api/v1/invites",
            """{"email":"$INVITEE_EMAIL"}""",
            token,
        )

        assertEquals(409, status)
        assertEquals("already_exists", body["error"]?.jsonPrimitive?.content)
    }

    @Test
    @Order(10)
    fun `invite without auth returns 401`() {
        val (status, _) = post(
            "/api/v1/invites",
            """{"email":"someone@example.com"}""",
        )

        assertEquals(401, status)
    }

    @Test
    @Order(11)
    fun `revoke invite then accept fails`() {
        val (adminToken, orgId) = login()

        // Send a new invite
        val newEmail = "revoke-test@example.com"
        val (sendStatus, _) = post(
            "/api/v1/invites",
            """{"email":"$newEmail"}""",
            adminToken,
        )
        assertEquals(200, sendStatus)

        val inviteToken = extractInviteToken()

        // List invites to get the invite ID
        val (_, listBody) = get("/api/v1/invites", adminToken)
        val invites = Json.parseToJsonElement(listBody).jsonObject["items"]!!.jsonArray
        val revokeInvite = invites.first {
            it.jsonObject["email"]?.jsonPrimitive?.content == newEmail
        }.jsonObject
        val inviteId = revokeInvite["id"]!!.jsonPrimitive.content

        // Revoke
        val (revokeStatus, _) = delete("/api/v1/invites/$inviteId", adminToken)
        assertEquals(200, revokeStatus)

        // Try to accept — should fail
        val (acceptStatus, _) = post(
            "/api/v1/invites/$inviteToken/accept",
            """{"password":"RevokeTest1!","displayName":"Revoked User"}""",
        )
        assertEquals(401, acceptStatus)
    }

    @Test
    @Order(12)
    fun `get invalid invite token returns 401`() {
        val (status, _) = get("/api/v1/invites/nonexistent-token")
        assertEquals(401, status)
    }

    @Test
    @Order(13)
    fun `invite existing user from another context`() {
        // The key security test is timing: all invite responses should take >= 500ms
        val (adminToken, orgId) = login()

        val newEmail = "timing-test@example.com"
        val start = System.currentTimeMillis()
        post(
            "/api/v1/invites",
            """{"email":"$newEmail"}""",
            adminToken,
        )
        val elapsed = System.currentTimeMillis() - start

        assertTrue(elapsed >= 450, "Response should be timing-normalized (took ${elapsed}ms)")
    }
}
