package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.CaRoot
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.gateway.controllers.agents.CaService
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.netty.Netty
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.net.ServerSocket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AgentRegistrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_test")
            .withUsername("test")
            .withPassword("test")

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
    }

    private var serverPort: Int = 0
    private var bootstrapToken: String = ""
    private val httpClient = HttpClient.newHttpClient()

    @BeforeAll
    fun setup() {
        // PLATFORM_AES_KEY is set via build.gradle.kts test environment

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()

        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)

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

        val serverEnv = applicationEnvironment {
            config = HoconApplicationConfig(mergedConfig)
        }

        val server = embeddedServer(Netty, serverEnv, configure = {
            connector { port = serverPort }
        })
        server.start(wait = false)
        Thread.sleep(2000)
    }

    @Test
    @Order(1)
    fun `CA root is generated on first bootstrap token creation`() {
        transaction {
            val before = CaRoot.selectAll().count()
            assertEquals(0, before)
        }

        // Simulate what --agent-bootstrap does
        val token = generateToken()
        bootstrapToken = token
        val tokenHash = BCrypt.withDefaults().hashToString(12, token.toCharArray())

        transaction {
            CaService.ensureCaRoot()

            AgentBootstrapTokens.insert {
                it[id] = UUID.randomUUID()
                it[slug] = "test-agent-1"
                it[label] = "Test Agent 1"
                it[AgentBootstrapTokens.tokenHash] = tokenHash
                it[AgentBootstrapTokens.tokenLookup] = dev.tracedown.common.auth.TokenHasher.sha256Hex(token)
                it[expiresAt] = Instant.now().plus(1, ChronoUnit.HOURS)
                it[createdAt] = Instant.now()
            }
        }

        transaction {
            val caCount = CaRoot.selectAll().count()
            assertEquals(1, caCount)

            val caRow = CaRoot.selectAll().first()
            assertTrue(caRow[CaRoot.certificatePem].contains("BEGIN CERTIFICATE"))
            assertTrue(caRow[CaRoot.privateKeyEncrypted].isNotEmpty())
            assertTrue(caRow[CaRoot.privateKeyIv].isNotEmpty())
        }
    }

    @Test
    @Order(2)
    fun `agent registers with valid bootstrap token`() {
        // Generate a CSR like the Python agent does (RSA >= 3072 key floor).
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(3072, SecureRandom())
        }.generateKeyPair()

        val csrPem = generateCsr(keyPair, "test-agent-1")

        val body = """{"bootstrapToken":"$bootstrapToken","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://test-agent-1:8443"}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort/internal/agents/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())

        assertEquals(200, response.statusCode(), "Registration should succeed. Body: ${response.body()}")

        val json = Json.parseToJsonElement(response.body()).jsonObject
        assertTrue(json["certificatePem"]!!.jsonPrimitive.content.contains("BEGIN CERTIFICATE"))
        assertTrue(json["caRootPem"]!!.jsonPrimitive.content.contains("BEGIN CERTIFICATE"))

        // RFC 5280 key identifiers and CA key usage: OpenSSL's X509_STRICT
        // (Python 3.13's default) refuses the chain without them.
        val leaf = parseCert(json["certificatePem"]!!.jsonPrimitive.content)
        val root = parseCert(json["caRootPem"]!!.jsonPrimitive.content)
        assertNotNull(leaf.getExtensionValue("2.5.29.14"), "leaf carries a Subject Key Identifier")
        assertNotNull(leaf.getExtensionValue("2.5.29.35"), "leaf carries an Authority Key Identifier")
        assertNotNull(root.getExtensionValue("2.5.29.14"), "root carries a Subject Key Identifier")
        assertNotNull(root.getExtensionValue("2.5.29.35"), "root carries an Authority Key Identifier")
        assertTrue(root.keyUsage[5], "root keyUsage has keyCertSign")
        // The leaf's AKID names the root's SKID: that is what links the chain.
        assertTrue(
            leaf.getExtensionValue("2.5.29.35").toList().containsAll(root.getExtensionValue("2.5.29.14").drop(4).toList()),
            "leaf AKID key id matches root SKID",
        )

        // Verify DB state
        transaction {
            val agent = ProbeAgents.selectAll()
                .where { ProbeAgents.slug eq "test-agent-1" }
                .firstOrNull()
            assertNotNull(agent)
            assertEquals("Test Agent 1", agent!![ProbeAgents.label])
            assertTrue(agent[ProbeAgents.isActive])

            val cert = AgentCertificates.selectAll()
                .where { AgentCertificates.probeAgentId eq agent[ProbeAgents.id] }
                .firstOrNull()
            assertNotNull(cert)
            assertTrue(cert!![AgentCertificates.certificatePem].contains("BEGIN CERTIFICATE"))
            assertFalse(cert[AgentCertificates.revoked])

            val token = AgentBootstrapTokens.selectAll()
                .where { AgentBootstrapTokens.slug eq "test-agent-1" }
                .first()
            assertTrue(token[AgentBootstrapTokens.used])
            assertNotNull(token[AgentBootstrapTokens.usedAt])
        }
    }

    @Test
    @Order(3)
    fun `reusing bootstrap token is rejected`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val csrPem = generateCsr(keyPair, "test-agent-2")

        val body = """{"bootstrapToken":"$bootstrapToken","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://test-agent-2:8443"}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort/internal/agents/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, response.statusCode())
    }

    @Test
    @Order(4)
    fun `invalid bootstrap token is rejected`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom())
        }.generateKeyPair()
        val csrPem = generateCsr(keyPair, "test-agent-3")

        val body = """{"bootstrapToken":"invalid-token-value","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://test-agent-3:8443"}"""

        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort/internal/agents/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(403, response.statusCode())
    }

    @Test
    @Order(5)
    fun `CSR subject is ignored — cert identity is bound to the token slug`() {
        val slug = "forge-agent"
        val token = createToken(slug)

        // A CSR that lies about its identity: subject CN=tracedown-scheduler.
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(3072, SecureRandom())
        }.generateKeyPair()
        val csrPem = generateCsr(keyPair, "tracedown-scheduler")

        val body = """{"bootstrapToken":"$token","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://forge-agent:8443"}"""
        val response = post("/internal/agents/register", body)
        assertEquals(200, response.statusCode(), "Registration should succeed. Body: ${response.body()}")

        val certPem = Json.parseToJsonElement(response.body()).jsonObject["certificatePem"]!!.jsonPrimitive.content
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(certPem.byteInputStream()) as X509Certificate

        // The CSR's forged CN is discarded — identity is the token's slug.
        assertTrue(
            cert.subjectX500Principal.name.contains("CN=$slug"),
            "cert CN should be the slug, was ${cert.subjectX500Principal.name}",
        )
        val dnsSans = cert.subjectAlternativeNames.orEmpty()
            .filter { it[0] == 2 }
            .map { it[1] as String }
        assertEquals(listOf(slug), dnsSans, "DNS SAN should be exactly the slug")
        assertFalse(
            cert.subjectX500Principal.name.contains("tracedown-scheduler"),
            "forged scheduler identity must not appear",
        )

        // serverAuth EKU only — an agent cert can never act as a TLS client.
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), cert.extendedKeyUsage)
    }

    @Test
    @Order(6)
    fun `weak-key CSR is rejected`() {
        val token = createToken("weak-agent")
        val keyPair = KeyPairGenerator.getInstance("RSA").apply {
            initialize(2048, SecureRandom()) // below the 3072 floor
        }.generateKeyPair()
        val csrPem = generateCsr(keyPair, "weak-agent")

        val body = """{"bootstrapToken":"$token","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://weak-agent:8443"}"""
        val response = post("/internal/agents/register", body)
        assertEquals(400, response.statusCode(), "Weak key must be rejected. Body: ${response.body()}")
    }

    @Test
    @Order(7)
    fun `CSR with an invalid self-signature is rejected`() {
        val token = createToken("forged-sig-agent")

        // Build a CSR that carries key A's public key but is signed by key B —
        // proof-of-possession fails.
        val keyA = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, SecureRandom()) }.generateKeyPair()
        val keyB = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, SecureRandom()) }.generateKeyPair()
        val csr = org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=forged-sig-agent"),
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyA.public.encoded),
        ).build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyB.private),
        )
        val writer = java.io.StringWriter()
        org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use { it.writeObject(csr) }
        val csrPem = writer.toString()

        val body = """{"bootstrapToken":"$token","csrPem":${Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)},"agentUri":"https://forged-sig-agent:8443"}"""
        val response = post("/internal/agents/register", body)
        assertEquals(400, response.statusCode(), "Invalid self-signature must be rejected. Body: ${response.body()}")
    }

    @Test
    @Order(8)
    fun `http and private agentUri are rejected`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, SecureRandom()) }.generateKeyPair()
        val csrPem = generateCsr(keyPair, "uri-agent")
        val csrJson = Json.encodeToString(kotlinx.serialization.serializer<String>(), csrPem)

        // http:// scheme — would send scripts + secrets in the clear.
        val httpToken = createToken("uri-http-agent")
        val httpBody = """{"bootstrapToken":"$httpToken","csrPem":$csrJson,"agentUri":"http://uri-agent:8443"}"""
        assertEquals(400, post("/internal/agents/register", httpBody).statusCode())

        // https but a loopback host — a classic SSRF pivot.
        val loopToken = createToken("uri-loop-agent")
        val loopBody = """{"bootstrapToken":"$loopToken","csrPem":$csrJson,"agentUri":"https://127.0.0.1:8443"}"""
        assertEquals(400, post("/internal/agents/register", loopBody).statusCode())
    }

    /**
     * The dashboard issued a token for a slug that was already an agent, and
     * registration then refused it — a token that could never be redeemed. The
     * refusal belongs where the name is chosen. `test-agent-1` is the agent
     * registered in order 2.
     */
    @Test
    @Order(9)
    fun `a bootstrap token for a registered slug is refused, a fresh slug is issued one`() {
        val session = newOwnerSession()

        val taken = post("/api/v1/agents/bootstrap-token", """{"slug":"test-agent-1"}""", bearer = session)
        assertEquals(409, taken.statusCode(), "A registered slug must be refused. Body: ${taken.body()}")
        assertTrue(taken.body().contains("agent_slug_taken"), taken.body())

        val fresh = post("/api/v1/agents/bootstrap-token", """{"slug":"brand-new-agent"}""", bearer = session)
        assertEquals(200, fresh.statusCode(), "A new slug must be issued a token. Body: ${fresh.body()}")
        val json = Json.parseToJsonElement(fresh.body()).jsonObject
        assertEquals("brand-new-agent", json["slug"]!!.jsonPrimitive.content)
        // Nothing configured in the test environment: an honest null, not a guess.
        assertTrue(json["schedulerUrl"] == null || json["schedulerUrl"] is kotlinx.serialization.json.JsonNull, fresh.body())
    }

    // ── Helpers ──

    private fun parseCert(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(pem.byteInputStream()) as X509Certificate

    private fun post(path: String, body: String, bearer: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
            .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** An org owner with an active session scoped to that org; returns the bearer token. */
    private fun newOwnerSession(): String {
        val userId = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val token = "tok-${UUID.randomUUID()}"
        transaction {
            Users.insert {
                it[id] = userId
                it[email] = "owner-$userId@t.dev"
                it[passwordHash] = "x"
                it[displayName] = "owner"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Org ${orgId.toString().take(6)}"
                it[ownerId] = userId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "active"
                it[isActive] = true
                it[deleted] = false
                it[inviteToken] = ""
            }
            Sessions.insert {
                it[id] = UUID.randomUUID()
                it[Sessions.userId] = userId
                it[organizationId] = orgId
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[status] = SessionStatus.ACTIVE
                it[expiresAt] = Instant.now().plusSeconds(3600)
                it[lastActiveAt] = Instant.now()
                it[revoked] = false
                it[createdAt] = Instant.now()
            }
        }
        return token
    }

    /** Inserts a fresh single-use bootstrap token for [slug] and returns the raw value. */
    private fun createToken(slug: String): String {
        val token = generateToken()
        val tokenHash = BCrypt.withDefaults().hashToString(12, token.toCharArray())
        transaction {
            CaService.ensureCaRoot()
            AgentBootstrapTokens.insert {
                it[id] = UUID.randomUUID()
                it[AgentBootstrapTokens.slug] = slug
                it[label] = slug
                it[AgentBootstrapTokens.tokenHash] = tokenHash
                it[AgentBootstrapTokens.tokenLookup] = dev.tracedown.common.auth.TokenHasher.sha256Hex(token)
                it[expiresAt] = Instant.now().plus(1, ChronoUnit.HOURS)
                it[createdAt] = Instant.now()
            }
        }
        return token
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateCsr(keyPair: java.security.KeyPair, cn: String): String {
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=$cn,O=tracedown-agent")
        val csr = org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder(
            subject,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        )
        val writer = java.io.StringWriter()
        org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use { it.writeObject(csr) }
        return writer.toString()
    }
}
