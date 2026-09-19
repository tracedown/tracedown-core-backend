package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Sessions
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
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.lowerCase
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.ServerSocket
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * The address a session is stamped with is the caller's, not the machine that
 * opened the TCP connection.
 *
 * Behind a reverse proxy those are different, and the difference is the whole
 * point: the peer is the proxy, identical for every person signing in, which
 * makes a session list say nothing. Two deployments are covered here — one with
 * proxies in front (the forwarded chain decides, and a caller cannot talk its
 * way into a different answer), one exposed directly (the peer is the caller,
 * and a forwarded header is ignored entirely).
 */
@Testcontainers
class ClientAddressIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_clientaddr_test")
            .withUsername("test")
            .withPassword("test")

        /** Proxies between the internet and the gateway in the proxied deployment. */
        private const val TRUSTED_PROXIES = 3

        /** The person signing in. */
        private const val CLIENT = "203.0.113.9"

        /** What the two outer proxies appended on the way in. */
        private const val PROXY_ONE = "198.51.100.7"
        private const val PROXY_TWO = "198.51.100.8"

        /**
         * The chain as it reaches the gateway: the client's address, then each
         * proxy that forwarded it. The innermost proxy is the TCP peer and so
         * appears in no header at all.
         */
        private const val FORWARDED = "$CLIENT, $PROXY_ONE, $PROXY_TWO"

        /** What the gateway sees as its peer when the test talks to it. */
        private const val LOOPBACK = "127.0.0.1"

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private val aesKeyBytes = AES_KEY.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        private const val ADMIN_EMAIL = "admin@tracedown.dev"
        private const val ADMIN_PASSWORD = "Down2trace!"

        private const val ENROLL_EMAIL = "addr-enroll@tracedown.dev"
        private const val ENROLL_PASSWORD = "AddrEnroll1!"

        private const val TOTP_EMAIL = "addr-totp@tracedown.dev"
        private const val TOTP_PASSWORD = "AddrTotp1!"

        private const val INVITEE_EMAIL = "addr-invitee@example.com"
        private const val INVITEE_PASSWORD = "AddrInvite1!"

        private lateinit var totpSecret: ByteArray

        /** Gateway behind [TRUSTED_PROXIES] proxies, rate limiting on. */
        private lateinit var proxied: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var proxiedPort: Int = 0

        /**
         * Gateway exposed with nothing in front, and with rate limiting switched
         * off — the address still has to resolve when that plugin does nothing.
         */
        private lateinit var direct: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var directPort: Int = 0

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            proxiedPort = ServerSocket(0).use { it.localPort }
            directPort = ServerSocket(0).use { it.localPort }

            proxied = start(proxiedPort, mapOf(
                "rateLimit.enabled" to "true",
                "rateLimit.trustedProxies" to TRUSTED_PROXIES.toString(),
            ))
            // Sequential: the first boot seeds the single-org bootstrap, and the
            // second must find it already there rather than race it.
            direct = start(directPort, mapOf(
                "rateLimit.enabled" to "false",
                "rateLimit.trustedProxies" to "0",
            ))

            createUser(ENROLL_EMAIL, ENROLL_PASSWORD, withTotp = false)
            createUser(TOTP_EMAIL, TOTP_PASSWORD, withTotp = true)
        }

        private fun start(
            port: Int,
            extra: Map<String, String>,
        ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
            ) + extra)
            val env = applicationEnvironment {
                config = HoconApplicationConfig(overrides.withFallback(ConfigFactory.load()))
            }
            val server = embeddedServer(Netty, env, configure = { connector { this.port = port } })
            server.start(wait = false)
            Thread.sleep(2000)
            return server
        }

        /** A user with its own org, so signing in is permitted. */
        private fun createUser(email: String, password: String, withTotp: Boolean) {
            val hash = BCrypt.withDefaults().hashToString(12, password.toCharArray())
            val encrypted = if (withTotp) {
                totpSecret = ByteArray(20).also { SecureRandom().nextBytes(it) }
                TotpUtil.encryptSecret(totpSecret, aesKeyBytes)
            } else {
                null
            }
            transaction {
                val userId = UUID.randomUUID()
                Users.insert {
                    it[id] = userId
                    it[Users.email] = email
                    it[passwordHash] = hash
                    it[displayName] = email.substringBefore("@")
                    it[isActive] = true
                    it[deleted] = false
                    it[totpEnabled] = withTotp
                    it[totpSecretEncrypted] = encrypted?.first
                    it[totpSecretIv] = encrypted?.second
                    it[totpEnrolledAt] = if (withTotp) Instant.now() else null
                    it[createdAt] = Instant.now()
                }
                val orgId = UUID.randomUUID()
                Organizations.insert {
                    it[id] = orgId
                    it[name] = "Addr Org ${orgId.toString().take(6)}"
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
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            proxied.stop(1000, 5000)
            direct.stop(1000, 5000)
        }
    }

    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    /**
     * Posts to the proxied gateway with [forwarded] as the chain the proxies
     * built, or to [port] when a different deployment is under test.
     */
    private fun post(
        path: String,
        body: String,
        forwarded: String? = FORWARDED,
        bearer: String? = null,
        port: Int = proxiedPort,
    ): Pair<Int, JsonObject> {
        val builder = Request.Builder()
            .url("http://localhost:$port$path")
            .post(body.toRequestBody(jsonType))
        forwarded?.let { builder.header("X-Forwarded-For", it) }
        bearer?.let { builder.header("Authorization", "Bearer $it") }
        val response = client.newCall(builder.build()).execute()
        return response.code to Json.parseToJsonElement(response.body.string()).jsonObject
    }

    /** The address recorded on the session [token] identifies. */
    private fun addressOn(token: String): String? = transaction {
        Sessions.selectAll()
            .where { Sessions.sessionTokenHash eq TokenHasher.sha256Hex(token) }
            .single()[Sessions.ipAddress]
    }

    private fun tokenFrom(body: JsonObject): String =
        body["token"]?.jsonPrimitive?.content ?: throw AssertionError("No session token in $body")

    @Test
    fun `signing in records the address the proxies forwarded`() {
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
        )
        assertEquals(200, status, "Login response: $body")
        assertEquals(CLIENT, addressOn(tokenFrom(body)))
    }

    @Test
    fun `an address the caller injected is not the one recorded`() {
        // Whatever a caller puts in the header arrives ahead of everything the
        // proxies append, so it is further out than the hop that counts.
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
            forwarded = "192.0.2.66, $FORWARDED",
        )
        assertEquals(200, status, "Login response: $body")
        assertEquals(CLIENT, addressOn(tokenFrom(body)))
    }

    @Test
    fun `confirming enrollment in two-factor records the forwarded address`() {
        val (_, login) = post(
            "/api/v1/auth/login",
            """{"email":"$ENROLL_EMAIL","password":"$ENROLL_PASSWORD"}""",
        )
        val (enrollStatus, enroll) = post("/api/v1/auth/totp/enroll", "{}", bearer = tokenFrom(login))
        assertEquals(200, enrollStatus, "Enroll response: $enroll")

        val secret = enroll["secret"]!!.jsonPrimitive.content
        val (confirmStatus, confirm) = post(
            "/api/v1/auth/totp/setup/confirm",
            """{"confirmToken":"${enroll["confirmToken"]!!.jsonPrimitive.content}",""" +
                """"code":"${TotpUtil.generateCode(decodeBase32(secret))}"}""",
        )
        assertEquals(200, confirmStatus, "Confirm response: $confirm")
        assertEquals(CLIENT, addressOn(tokenFrom(confirm)))
    }

    @Test
    fun `passing the second factor records the forwarded address`() {
        val (_, login) = post(
            "/api/v1/auth/login",
            """{"email":"$TOTP_EMAIL","password":"$TOTP_PASSWORD"}""",
        )
        val challenge = login["challenge"]?.jsonPrimitive?.content
        assertNotNull(challenge, "Expected a second-factor challenge, got: $login")

        val (status, body) = post(
            "/api/v1/auth/login/totp",
            """{"challenge":"$challenge","code":"${TotpUtil.generateCode(totpSecret)}"}""",
        )
        assertEquals(200, status, "Verify response: $body")
        assertEquals(CLIENT, addressOn(tokenFrom(body)))
    }

    @Test
    fun `accepting an invitation records the forwarded address`() {
        val (_, login) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
        )
        val (inviteStatus, invite) = post(
            "/api/v1/invites",
            """{"email":"$INVITEE_EMAIL"}""",
            bearer = tokenFrom(login),
        )
        assertEquals(200, inviteStatus, "Invite response: $invite")

        val inviteToken = transaction {
            val invitedUserId = Users.selectAll()
                .where { Users.email.lowerCase() eq INVITEE_EMAIL }
                .single()[Users.id]
            OrgUsers.selectAll()
                .where { (OrgUsers.userId eq invitedUserId) and (OrgUsers.status eq "invited") }
                .single()[OrgUsers.inviteToken]
        }

        val (acceptStatus, accepted) = post(
            "/api/v1/invites/$inviteToken/accept",
            """{"password":"$INVITEE_PASSWORD","displayName":"Invited"}""",
        )
        assertEquals(200, acceptStatus, "Accept response: $accepted")
        assertEquals(CLIENT, addressOn(tokenFrom(accepted)))
    }

    @Test
    fun `a hop too long to be an address is not the one recorded`() {
        // Only a caller can put this in the chain, and only where the hop count
        // claims more proxies than there are. It must not reach the column.
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
            forwarded = "${"9".repeat(60)}, $PROXY_ONE, $PROXY_TWO",
        )
        assertEquals(200, status, "Login response: $body")
        assertEquals(LOOPBACK, addressOn(tokenFrom(body)))
    }

    @Test
    fun `with no proxies in front the peer is recorded and the header ignored`() {
        val (status, body) = post(
            "/api/v1/auth/login",
            """{"email":"$ADMIN_EMAIL","password":"$ADMIN_PASSWORD"}""",
            forwarded = FORWARDED,
            port = directPort,
        )
        assertEquals(200, status, "Login response: $body")
        assertEquals(LOOPBACK, addressOn(tokenFrom(body)))
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
