package dev.tracedown.gateway

import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.domain.HttpDnsDomainVerifier
import dev.tracedown.gateway.controllers.domains.DomainController
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
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap

/**
 * Integration test for domain verification via HTTP.
 * Spins up a second HTTP server to serve challenge tokens at well-known URLs.
 */
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DomainVerificationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tracedown_domain_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private lateinit var verifyServer: HttpServer
        private var verifyServerPort: Int = 0
        private val verifyFiles = ConcurrentHashMap<String, String>()

        private const val ADMIN_EMAIL = "admin@tracedown.dev"
        private const val ADMIN_PASSWORD = "Down2trace!"

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            // Start verification HTTP server
            verifyServerPort = ServerSocket(0).use { it.localPort }
            verifyServer = HttpServer.create(InetSocketAddress(verifyServerPort), 0)
            verifyServer.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                val content = verifyFiles[path]
                if (content != null) {
                    val bytes = content.toByteArray(Charsets.UTF_8)
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            }
            verifyServer.start()

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

            // Reinitialize DomainController with HTTP verifier pointing at test server (http, not https)
            val aesKey = "0000000000000000000000000000000000000000000000000000000000000000"
            DomainController.init(aesKey, HttpDnsDomainVerifier(httpScheme = "http", httpPort = verifyServerPort, allowInternalTargets = true))
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            verifyServer.stop(0)
        }
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    private fun post(path: String, body: String? = null, token: String? = null): Pair<Int, JsonObject> {
        val reqBody = body?.toRequestBody(jsonType)
            ?: "".toRequestBody(jsonType)
        val builder = Request.Builder()
            .url("http://localhost:$serverPort$path")
            .post(reqBody)
        token?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.newCall(builder.build()).execute()
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

    private fun login(): String {
        val (code, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
        )
        assertEquals(200, code)
        return body["token"]!!.jsonPrimitive.content
    }

    // ── State shared across ordered tests ──
    private var token: String = ""
    private var domainId: String = ""
    private var challenge: String = ""

    @Test
    @Order(1)
    fun `create domain with http-01 verification`() {
        token = login()
        // Use "localhost" as domain — the verifier is configured with httpPort pointing to our test server
        val (code, body) = post(
            "/api/v1/domains",
            """{"domain":"localhost","verificationType":"http-01"}""",
            token,
        )
        assertEquals(200, code)
        assertEquals("localhost", body["domain"]!!.jsonPrimitive.content)
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
        assertEquals("http-01", body["verificationType"]!!.jsonPrimitive.content)

        domainId = body["id"]!!.jsonPrimitive.content
        challenge = body["challenge"]!!.jsonPrimitive.content
        assertNotNull(challenge)
        assertTrue(challenge.isNotBlank())
    }

    @Test
    @Order(2)
    fun `verification fails when challenge file is missing`() {
        val (code, body) = post(
            "/api/v1/domains/$domainId/verify",
            null,
            token,
        )
        assertEquals(200, code)
        assertFalse(body["verified"]!!.jsonPrimitive.boolean)
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
        assertNotNull(body["error"])
    }

    @Test
    @Order(3)
    fun `verification fails when challenge token is wrong`() {
        // Serve wrong content
        verifyFiles["/.well-known/tracedown-verify.txt"] = "wrong-token"

        val (code, body) = post(
            "/api/v1/domains/$domainId/verify",
            null,
            token,
        )
        assertEquals(200, code)
        assertFalse(body["verified"]!!.jsonPrimitive.boolean)
        assertEquals("pending", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    @Order(4)
    fun `verification succeeds when challenge token matches`() {
        // Serve the correct challenge token
        verifyFiles["/.well-known/tracedown-verify.txt"] = challenge

        val (code, body) = post(
            "/api/v1/domains/$domainId/verify",
            null,
            token,
        )
        assertEquals(200, code)
        assertTrue(body["verified"]!!.jsonPrimitive.boolean)
        assertEquals("verified", body["status"]!!.jsonPrimitive.content)
    }

    @Test
    @Order(5)
    fun `domain shows as verified after successful check`() {
        val (code, body) = get("/api/v1/domains/$domainId", token)
        assertEquals(200, code)
        assertEquals("verified", body["status"]!!.jsonPrimitive.content)
        assertNotNull(body["verifiedAt"])
        assertFalse(body["lapsed"]!!.jsonPrimitive.boolean)
    }
}
