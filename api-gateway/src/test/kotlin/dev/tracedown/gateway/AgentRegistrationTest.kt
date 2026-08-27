package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.models.AgentBootstrapTokens
import dev.tracedown.common.models.AgentCertificates
import dev.tracedown.common.models.CaRoot
import dev.tracedown.common.models.ProbeAgents
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
        val postgres = PostgreSQLContainer("postgres:16-alpine")
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

    // ── Helpers ──

    private fun post(path: String, body: String): HttpResponse<String> {
        val request = HttpRequest.newBuilder()
            .uri(URI.create("http://localhost:$serverPort$path"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString())
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
