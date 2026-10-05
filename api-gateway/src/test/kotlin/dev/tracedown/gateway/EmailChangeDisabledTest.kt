package dev.tracedown.gateway

import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.PasswordHasher
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID

/**
 * With nothing configured — the shipped default — an account cannot change its
 * own address, the capabilities say so, and the confirmation route is closed
 * too (a link minted while the switch was on must not work after it is off).
 */
@Testcontainers
class EmailChangeDisabledTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_email_change_off_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0
        private const val EMAIL = "off-user@tracedown.dev"
        private const val PASSWORD = "OffTest12345!"

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
                "rateLimit.enabled" to "false",
            ))
            val env = applicationEnvironment { config = HoconApplicationConfig(overrides.withFallback(ConfigFactory.load())) }
            server = embeddedServer(Netty, env, configure = { connector { port = serverPort } })
            server.start(wait = false)
            Thread.sleep(2000)
            transaction {
                val userId = UUID.randomUUID()
                val orgId = UUID.randomUUID()
                Users.insert {
                    it[id] = userId; it[email] = EMAIL; it[passwordHash] = PasswordHasher.hash(PASSWORD)
                    it[displayName] = "Off"; it[isActive] = true; it[deleted] = false; it[createdAt] = Instant.now()
                }
                Organizations.insert {
                    it[id] = orgId; it[name] = "Off Org"; it[ownerId] = userId; it[deleted] = false; it[createdAt] = Instant.now()
                }
                OrgUsers.insert {
                    it[id] = UUID.randomUUID(); it[organizationId] = orgId; it[OrgUsers.userId] = userId
                    it[status] = "active"; it[isActive] = true; it[deleted] = false; it[inviteToken] = ""
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

    private fun post(path: String, body: String, token: String? = null): Pair<Int, String> {
        val builder = Request.Builder().url("http://localhost:$serverPort$path").post(body.toRequestBody(jsonType))
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { it.code to it.body!!.string() }
    }

    private fun get(path: String, token: String): Pair<Int, String> {
        val request = Request.Builder().url("http://localhost:$serverPort$path").header("Authorization", "Bearer $token").build()
        return client.newCall(request).execute().use { it.code to it.body!!.string() }
    }

    private fun login(): String {
        val (_, raw) = post("/api/v1/auth/login", """{"email":"$EMAIL","password":"$PASSWORD"}""")
        return Json.parseToJsonElement(raw).jsonObject["token"]!!.jsonPrimitive.content
    }

    @Test
    fun `by default an account cannot change its address, and the page is told so`() {
        val token = login()

        val caps = Json.parseToJsonElement(get("/api/v1/auth/profile/capabilities", token).second).jsonObject
        assertFalse(caps["allowEmailChange"]!!.jsonPrimitive.boolean)

        val (status, raw) = post("/api/v1/me/email", """{"newEmail":"elsewhere@tracedown.dev","currentPassword":"$PASSWORD"}""", token)
        assertEquals(403, status, raw)
        assertEquals("email_change_disabled", Json.parseToJsonElement(raw).jsonObject["error"]!!.jsonPrimitive.content)

        val (confirmStatus, _) = post("/api/v1/me/email/confirm", """{"token":"anything"}""")
        assertEquals(403, confirmStatus)
    }
}
