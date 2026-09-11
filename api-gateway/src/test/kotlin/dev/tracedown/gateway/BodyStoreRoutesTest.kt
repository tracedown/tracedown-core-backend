package dev.tracedown.gateway

import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.storage.BodyConfinement
import dev.tracedown.common.storage.BodyStorageClient
import dev.tracedown.common.storage.BodyStoreInput
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.common.storage.DefaultBodyStore
import dev.tracedown.common.util.VariableCrypto
import dev.tracedown.gateway.controllers.agents.AgentRegistrationController
import dev.tracedown.gateway.controllers.agents.CaService
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.data.agents.AgentRegisterRequest
import dev.tracedown.gateway.routes.v1.agents.agentAdminRoutes
import dev.tracedown.gateway.routes.v1.agents.bodyStoreRoutes
import dev.tracedown.gateway.util.ApiException
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Body stores through the API: management with the secret never returned,
 * validation, the SSRF guard on the endpoint, the in-use refusal, assignment
 * (directly and through a bootstrap token), and reading a step body back from
 * the store it lives in.
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BodyStoreRoutesTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_body_store_routes_test")
            .withUsername("test")
            .withPassword("test")

        private const val AES_KEY = "0000000000000000000000000000000000000000000000000000000000000000"
        private const val BUCKET = "agent-bodies"
        private const val STORES = "/api/v1/body-stores"
        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ownerId: UUID = UUID.randomUUID()
    private val orgId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()
    private val resultId: UUID = UUID.randomUUID()
    private val token = "tok-${UUID.randomUUID()}"
    private lateinit var fsBase: Path
    private lateinit var defaultRoot: Path

    @BeforeAll
    fun setup() {
        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations")
            .load()
            .migrate()
        DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password)
        AuthController.init(AES_KEY)
        VariableCrypto.init(AES_KEY)
        CaService.init(AES_KEY)

        fsBase = Files.createTempDirectory("body-store-base").toRealPath()
        defaultRoot = fsBase.resolve("default-bodies").also { Files.createDirectories(it) }
        BodyStoreRegistry.configure(deploymentEnvironment = "dev", filesystemBases = fsBase.toString())
        BodyStoreService.configureDefault(
            DefaultBodyStore(kind = "filesystem", rootPath = defaultRoot.toString(), filesystemRoot = defaultRoot.toString()),
        )
        ProbeResultController.init(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = defaultRoot)))
        TestMinio.bucket(BUCKET)

        transaction {
            Users.insert {
                it[id] = ownerId
                it[email] = "body-store-$ownerId@t.dev"
                it[passwordHash] = "x"
                it[displayName] = "owner"
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Body stores"
                it[Organizations.ownerId] = this@BodyStoreRoutesTest.ownerId
                it[createdAt] = NOW
            }
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = orgId
                it[userId] = ownerId
                it[status] = "active"
                it[joinedAt] = NOW
                it[inviteToken] = "t-${UUID.randomUUID()}"
            }
            Sessions.insert {
                it[id] = UUID.randomUUID()
                it[userId] = ownerId
                it[organizationId] = orgId
                it[sessionTokenHash] = TokenHasher.sha256Hex(token)
                it[status] = SessionStatus.ACTIVE
                it[expiresAt] = Instant.now().plusSeconds(3600)
                it[lastActiveAt] = Instant.now()
                it[revoked] = false
                it[createdAt] = Instant.now()
            }
            val workspaceId = UUID.randomUUID()
            Workspaces.insert {
                it[id] = workspaceId
                it[organizationId] = orgId
                it[name] = "workspace"
                it[createdAt] = NOW
            }
            val projectId = UUID.randomUUID()
            Projects.insert {
                it[id] = projectId
                it[Projects.workspaceId] = workspaceId
                it[name] = "project"
                it[createdAt] = NOW
            }
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = projectId
                it[name] = "service"
                it[createdAt] = NOW
            }
            ProbeResults.insert {
                it[id] = resultId
                it[ProbeResults.serviceId] = this@BodyStoreRoutesTest.serviceId
                it[startedAt] = NOW
                it[status] = "success"
                it[runDurationMs] = 10
                it[rawResult] = JsonObject(emptyMap())
                it[ProbeResults.projectId] = projectId
                it[ProbeResults.workspaceId] = workspaceId
                it[organizationId] = orgId
            }
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private class Api(val client: HttpClient, val token: String) {
        suspend fun call(method: HttpMethod, path: String, body: String? = null): Pair<HttpStatusCode, JsonElement?> {
            val response = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $token")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val text = response.bodyAsText()
            return response.status to (if (text.isBlank()) null else Json.parseToJsonElement(text))
        }
    }

    /** The routes under test, wired as `Application.module()` wires them, with production's error shape. */
    private fun withApi(block: suspend Api.() -> Unit) = testApplication {
        application {
            install(Resources)
            install(ContentNegotiation) { json(json) }
            install(StatusPages) {
                exception<ApiException> { call, cause ->
                    val details = cause.details
                    if (details == null) {
                        call.respond(cause.status, mapOf("error" to cause.code))
                    } else {
                        call.respond(cause.status, buildJsonObject {
                            put("error", cause.code)
                            put("details", details)
                        })
                    }
                }
            }
            routing {
                bodyStoreRoutes()
                agentAdminRoutes()
            }
        }
        Api(client, token).block()
    }

    private fun s3Store(
        name: String,
        mode: String = "in_place",
        bucket: String? = BUCKET,
        prefix: String? = "bodies",
        endpoint: String? = TestMinio.endpoint,
        secret: String? = TestMinio.PASSWORD,
    ): String = buildJsonObject {
        put("name", name)
        put("kind", "s3")
        put("mode", mode)
        endpoint?.let { put("endpoint", it) }
        bucket?.let { put("bucket", it) }
        prefix?.let { put("prefix", it) }
        put("accessKeyId", TestMinio.USER)
        secret?.let { put("secretAccessKey", it) }
    }.toString()

    private fun unique(name: String) = "$name-${UUID.randomUUID().toString().take(8)}"

    private fun assertError(result: Pair<HttpStatusCode, JsonElement?>, status: HttpStatusCode, code: String, field: String? = null) {
        val (actual, body) = result
        assertEquals(status, actual, "body: $body")
        val obj = body!!.jsonObject
        assertEquals(code, obj["error"]!!.jsonPrimitive.content)
        if (field != null) assertEquals(field, obj["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
    }

    private suspend fun Api.createStore(body: String): String {
        val (status, created) = call(HttpMethod.Post, STORES, body)
        assertEquals(HttpStatusCode.Created, status, "body: $created")
        return created!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun insertAgent(slug: String) = transaction {
        ProbeAgents.insert {
            it[ProbeAgents.slug] = slug
            it[label] = slug
            it[agentUri] = "https://$slug.example.test:8443"
            it[publicKey] = "x"
            it[lastPing] = NOW
            it[lastStatus] = "success"
            it[lastPingDelayMs] = 0
            it[lastPongDeltaMs] = 0
            it[createdAt] = NOW
        }[ProbeAgents.id]
    }

    private fun agentStore(slug: String): UUID? = transaction {
        ProbeAgents.selectAll().where { ProbeAgents.slug eq slug }.single()[ProbeAgents.bodyStoreId]
    }

    private fun insertStep(url: String?, store: UUID?): UUID = transaction {
        val id = UUID.randomUUID()
        ProbeSteps.insert {
            it[ProbeSteps.id] = id
            it[probeResultId] = resultId
            it[stepNum] = 1
            it[requestUrl] = "https://api.example.test/"
            it[responseBodyStorageUrl] = url
            it[bodyStoreId] = store
            it[createdAt] = NOW
        }
        id
    }

    private fun readBody(stepId: UUID) = ProbeResultController.getStepBody(orgId, serviceId, resultId, stepId, ownerId)

    // ── Management ──────────────────────────────────────────────────────────

    @Test
    fun `create, list, update and delete a store without ever returning its secret`() = withApi {
        val name = unique("crud")
        val (status, created) = call(HttpMethod.Post, STORES, s3Store(name))
        assertEquals(HttpStatusCode.Created, status, "body: $created")
        val store = created!!.jsonObject
        val id = store["id"]!!.jsonPrimitive.content
        assertEquals(name, store["name"]!!.jsonPrimitive.content)
        assertEquals("s3", store["kind"]!!.jsonPrimitive.content)
        assertEquals("in_place", store["mode"]!!.jsonPrimitive.content)
        assertEquals("bodies", store["prefix"]!!.jsonPrimitive.content)
        assertEquals(TestMinio.USER, store["accessKeyId"]!!.jsonPrimitive.content)
        assertEquals("true", store["hasSecret"]!!.jsonPrimitive.content)
        assertEquals(0, store["agents"]!!.jsonPrimitive.int)
        assertFalse(store.containsKey("secretAccessKey"))
        assertFalse(created.toString().contains(TestMinio.PASSWORD), "the secret is never returned")

        val storedSecret = transaction {
            BodyStores.selectAll().where { BodyStores.id eq UUID.fromString(id) }.single()
                .let { it[BodyStores.secretEnc]!! to it[BodyStores.secretIv]!! }
        }
        assertNotEquals(TestMinio.PASSWORD, storedSecret.first, "encrypted at rest")
        assertEquals(TestMinio.PASSWORD, VariableCrypto.decryptBound(storedSecret.first, storedSecret.second, "body_store:$id"))

        val (listStatus, list) = call(HttpMethod.Get, STORES)
        assertEquals(HttpStatusCode.OK, listStatus)
        assertTrue(list!!.jsonArray.any { it.jsonObject["id"]!!.jsonPrimitive.content == id })
        assertFalse(list.toString().contains(TestMinio.PASSWORD))

        // An omitted secret keeps the stored one; the response is the updated row.
        val renamed = unique("crud-renamed")
        val (updateStatus, updated) = call(HttpMethod.Put, "$STORES/$id", s3Store(renamed, mode = "import", secret = null))
        assertEquals(HttpStatusCode.OK, updateStatus, "body: $updated")
        assertEquals(renamed, updated!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("import", updated.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("true", updated.jsonObject["hasSecret"]!!.jsonPrimitive.content)
        val afterUpdate = transaction {
            BodyStores.selectAll().where { BodyStores.id eq UUID.fromString(id) }.single()
                .let { VariableCrypto.decryptBound(it[BodyStores.secretEnc]!!, it[BodyStores.secretIv]!!, "body_store:$id") }
        }
        assertEquals(TestMinio.PASSWORD, afterUpdate)

        val (deleteStatus, _) = call(HttpMethod.Delete, "$STORES/$id")
        assertEquals(HttpStatusCode.OK, deleteStatus)
        val (_, after) = call(HttpMethod.Get, STORES)
        assertTrue(after!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
        assertError(call(HttpMethod.Delete, "$STORES/$id"), HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND)
    }

    @Test
    fun `the default store is described read-only`() = withApi {
        val (status, body) = call(HttpMethod.Get, "$STORES/default")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("filesystem", body!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(defaultRoot.toString(), body.jsonObject["rootPath"]!!.jsonPrimitive.content)
    }

    @Test
    fun `invalid input is refused with the field at fault`() = withApi {
        fun raw(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("k"), "kind" to "ftp", "mode" to "import")),
            HttpStatusCode.BadRequest, ErrorCodes.INVALID_STORE_KIND,
        )
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("m"), "kind" to "s3", "mode" to "sometimes")),
            HttpStatusCode.BadRequest, ErrorCodes.INVALID_STORE_MODE,
        )
        assertError(
            call(HttpMethod.Post, STORES, s3Store(unique("b"), bucket = null)),
            HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "bucket",
        )
        assertError(
            call(HttpMethod.Post, STORES, s3Store(unique("s"), secret = null)),
            HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "secretAccessKey",
        )
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("r"), "kind" to "filesystem", "mode" to "in_place")),
            HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "rootPath",
        )
        // A root the operator did not set aside, and one over the default store.
        for (root in listOf("/etc", defaultRoot.resolve("inside").toString(), fsBase.toString())) {
            assertError(
                call(HttpMethod.Post, STORES, raw("name" to unique("fs"), "kind" to "filesystem", "mode" to "in_place", "rootPath" to root)),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "rootPath",
            )
        }
        val name = unique("dup")
        createStore(s3Store(name))
        assertError(call(HttpMethod.Post, STORES, s3Store(name)), HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_NAME_TAKEN)
    }

    @Test
    fun `an endpoint inside the platform's network is refused`() = withApi {
        for (endpoint in listOf(
            "https://10.0.0.5", "https://169.254.169.254", "https://192.168.0.10:9000",
            "https://minio.railway.internal", "http://s3.example.com",
        )) {
            assertError(
                call(HttpMethod.Post, STORES, s3Store(unique("ssrf"), endpoint = endpoint)),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "endpoint",
            )
        }
        // In production even a local endpoint is refused.
        BodyStoreRegistry.configure(deploymentEnvironment = "production", filesystemBases = fsBase.toString())
        try {
            assertError(
                call(HttpMethod.Post, STORES, s3Store(unique("local"))),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "endpoint",
            )
        } finally {
            BodyStoreRegistry.configure(deploymentEnvironment = "dev", filesystemBases = fsBase.toString())
        }
    }

    @Test
    fun `test probes the store with its own credentials`() = withApi {
        val good = createStore(s3Store(unique("probe")))
        val (status, ok) = call(HttpMethod.Post, "$STORES/$good/test")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("true", ok!!.jsonObject["ok"]!!.jsonPrimitive.content, "body: $ok")

        val missing = createStore(s3Store(unique("probe-missing"), bucket = "no-such-bucket"))
        val (_, noBucket) = call(HttpMethod.Post, "$STORES/$missing/test")
        assertEquals("false", noBucket!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("bucket_not_found", noBucket.jsonObject["error"]!!.jsonPrimitive.content)

        val wrongKey = createStore(s3Store(unique("probe-denied"), secret = "not-the-secret"))
        val (_, denied) = call(HttpMethod.Post, "$STORES/$wrongKey/test")
        assertEquals("false", denied!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("access_denied", denied.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a store in use cannot be deleted`() = withApi {
        val id = createStore(s3Store(unique("in-use")))
        val slug = "in-use-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)

        val (assigned, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""")
        assertEquals(HttpStatusCode.OK, assigned)
        assertEquals(UUID.fromString(id), agentStore(slug))
        val (_, list) = call(HttpMethod.Get, STORES)
        assertEquals(1, list!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["agents"]!!.jsonPrimitive.int)

        val refused = call(HttpMethod.Delete, "$STORES/$id")
        assertError(refused, HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_IN_USE)
        assertEquals(1, refused.second!!.jsonObject["details"]!!.jsonObject["agents"]!!.jsonPrimitive.int)

        val (unassigned, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":null}""")
        assertEquals(HttpStatusCode.OK, unassigned)
        assertNull(agentStore(slug))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "$STORES/$id").first)

        // A stored body holds its store too.
        val held = createStore(s3Store(unique("held")))
        insertStep("s3://$BUCKET/bodies/held.json", UUID.fromString(held))
        assertError(call(HttpMethod.Delete, "$STORES/$held"), HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_IN_USE)

        assertError(
            call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"${UUID.randomUUID()}"}"""),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )
    }

    @Test
    fun `a bootstrap token carries its store and registration assigns it`() = withApi {
        val id = createStore(s3Store(unique("enrol")))
        val slug = "enrol-${UUID.randomUUID().toString().take(8)}"
        val (status, minted) = call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug","bodyStoreId":"$id"}""")
        assertEquals(HttpStatusCode.OK, status, "body: $minted")
        val bodyStore = minted!!.jsonObject["bodyStore"]!!.jsonObject
        assertEquals(id, bodyStore["id"]!!.jsonPrimitive.content)
        assertEquals(BUCKET, bodyStore["bucket"]!!.jsonPrimitive.content)
        assertEquals("in_place", bodyStore["mode"]!!.jsonPrimitive.content)
        assertFalse(minted.toString().contains(TestMinio.PASSWORD), "a token response carries no credential")

        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(3072, SecureRandom()) }.generateKeyPair()
        val uri = "https://$slug.example.test:8443"
        AgentRegistrationController.register(
            AgentRegisterRequest(minted.jsonObject["token"]!!.jsonPrimitive.content, generateCsr(keyPair, slug), uri),
            uri,
        )
        assertEquals(UUID.fromString(id), agentStore(slug), "the agent starts on the token's store")

        // A token for the default store says so explicitly.
        val (_, plain) = call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"plain-${UUID.randomUUID().toString().take(8)}"}""")
        assertEquals(JsonNull, plain!!.jsonObject["bodyStore"])

        assertError(
            call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"nostore-agent","bodyStoreId":"${UUID.randomUUID()}"}"""),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )
    }

    // ── Reading a step body back ────────────────────────────────────────────

    @Test
    fun `a body in the default store is read as before`() {
        val file = defaultRoot.resolve("run-default/call_0.json")
        Files.createDirectories(file.parent)
        Files.writeString(file, "default body")

        val body = readBody(insertStep("file://$file", null))

        assertEquals(BodyStorageClient.BodyContent.Inline("default body", null), body)
    }

    @Test
    fun `an in_place body is served as content from its store`() {
        val store = UUID.fromString(BodyStoreService.create(BodyStoreInput(
            name = unique("read"), kind = "s3", mode = "in_place", endpoint = TestMinio.endpoint, bucket = BUCKET,
            prefix = "bodies", accessKeyId = TestMinio.USER, secretAccessKey = TestMinio.PASSWORD,
        )).id)
        TestMinio.put(BUCKET, "bodies/run-1/call_0.json", """{"in":"place"}""".toByteArray(), "application/json")

        val body = readBody(insertStep("s3://$BUCKET/bodies/run-1/call_0.json", store))

        assertEquals(BodyStorageClient.BodyContent.Inline("""{"in":"place"}""", "application/json"), body)
    }

    @Test
    fun `a missing or refused in_place body is gone`() {
        val store = UUID.fromString(BodyStoreService.create(BodyStoreInput(
            name = unique("gone"), kind = "s3", mode = "in_place", endpoint = TestMinio.endpoint, bucket = BUCKET,
            prefix = "bodies", accessKeyId = TestMinio.USER, secretAccessKey = TestMinio.PASSWORD,
        )).id)
        for (uri in listOf("s3://$BUCKET/bodies/never-written.json", "s3://$BUCKET/outside/call_0.json", "file:///etc/passwd")) {
            val e = assertThrows(ApiException::class.java) { readBody(insertStep(uri, store)) }
            assertEquals(HttpStatusCode.Gone, e.status, uri)
            assertEquals(ErrorCodes.BODY_GONE, e.code, uri)
        }
    }

    @Test
    fun `an over-size in_place body is refused`() {
        val root = fsBase.resolve("fs-store-${UUID.randomUUID().toString().take(8)}").also { Files.createDirectories(it) }
        val store = UUID.fromString(BodyStoreService.create(BodyStoreInput(
            name = unique("big"), kind = "filesystem", mode = "in_place", rootPath = root.toString(),
        )).id)
        val big = root.resolve("big.bin")
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(BodyStoreRegistry.MAX_BODY_BYTES + 1) }

        val e = assertThrows(ApiException::class.java) { readBody(insertStep("file://$big", store)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, e.status)
        assertEquals(ErrorCodes.BODY_TOO_LARGE, e.code)
    }

    private fun generateCsr(keyPair: java.security.KeyPair, cn: String): String {
        val subject = org.bouncycastle.asn1.x500.X500Name("CN=$cn,O=tracedown-agent")
        val csr = org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder(
            subject,
            org.bouncycastle.asn1.x509.SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
        ).build(org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        val writer = java.io.StringWriter()
        org.bouncycastle.openssl.jcajce.JcaPEMWriter(writer).use { it.writeObject(csr) }
        return writer.toString()
    }
}
