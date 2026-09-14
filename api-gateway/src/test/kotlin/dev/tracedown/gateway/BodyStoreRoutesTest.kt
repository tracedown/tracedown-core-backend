package dev.tracedown.gateway

import dev.tracedown.common.auth.AccessLevel
import dev.tracedown.common.auth.TokenHasher
import dev.tracedown.common.config.DatabaseFactory
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.interceptors.InterceptorContext
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.AgentBootstrapTokens
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
import dev.tracedown.common.storage.BodyStoreCrypto
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
import kotlinx.coroutines.runBlocking
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
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
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
 * validation, the SSRF guard on the endpoint, organization ownership, the
 * in-use refusal and its `forgetBodies` release, the location lock, assignment
 * (directly and through a bootstrap token), the host hooks, and reading a step
 * body back from the store it lives in.
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
        private const val STORE_KEY = "1111111111111111111111111111111111111111111111111111111111111111"
        private const val BUCKET = "agent-bodies"
        private const val STORES = "/api/v1/body-stores"
        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

        /** Every key a store response carries, and nothing else. */
        private val STORE_KEYS = setOf(
            "id", "name", "kind", "mode", "endpoint", "region", "bucket", "prefix", "rootPath",
            "accessKeyId", "hasSecret", "agents", "lastFailure", "createdAt", "updatedAt",
        )
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val ownerId: UUID = UUID.randomUUID()
    private val readerId: UUID = UUID.randomUUID()
    private val orgId: UUID = UUID.randomUUID()
    private val otherOwnerId: UUID = UUID.randomUUID()
    private val otherOrgId: UUID = UUID.randomUUID()
    private val serviceId: UUID = UUID.randomUUID()
    private val resultId: UUID = UUID.randomUUID()
    private val token = "tok-${UUID.randomUUID()}"
    private val readerToken = "tok-reader-${UUID.randomUUID()}"
    private val otherToken = "tok-other-${UUID.randomUUID()}"
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
        // A key of its own — never the platform one.
        BodyStoreCrypto.init(STORE_KEY)

        fsBase = Files.createTempDirectory("body-store-base").toRealPath()
        defaultRoot = Files.createTempDirectory("body-store-default").toRealPath()
        configureStores()
        BodyStoreService.configureDefault(
            DefaultBodyStore(kind = "filesystem", rootPath = defaultRoot.toString(), filesystemRoot = defaultRoot.toString()),
        )
        ProbeResultController.init(BodyStorageClient(confinement = BodyConfinement(filesystemRoot = defaultRoot)))
        TestMinio.bucket(BUCKET)

        transaction {
            for ((userId, label) in listOf(ownerId to "owner", readerId to "reader", otherOwnerId to "other")) {
                Users.insert {
                    it[id] = userId
                    it[email] = "body-store-$userId@t.dev"
                    it[passwordHash] = "x"
                    it[displayName] = label
                    it[createdAt] = NOW
                }
            }
            Organizations.insert {
                it[id] = orgId
                it[name] = "Body stores"
                it[Organizations.ownerId] = this@BodyStoreRoutesTest.ownerId
                it[createdAt] = NOW
            }
            Organizations.insert {
                it[id] = otherOrgId
                it[name] = "Another organization"
                it[Organizations.ownerId] = otherOwnerId
                it[createdAt] = NOW
            }
            member(orgId, ownerId, settings = AccessLevel.WRITE)
            // Can see the store list, may change nothing.
            member(orgId, readerId, settings = AccessLevel.READ)
            member(otherOrgId, otherOwnerId, settings = AccessLevel.WRITE)
            session(token, ownerId, orgId)
            session(readerToken, readerId, orgId)
            session(otherToken, otherOwnerId, otherOrgId)

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

    @AfterEach
    fun clearHooks() {
        // A hook left registered would refuse or stamp every later test's writes.
        Interceptors.clearAll()
        configureStores()
    }

    private fun configureStores(allowPrivateEndpoints: Boolean = true) {
        BodyStoreRegistry.configure(
            filesystemBases = fsBase.toString(),
            allowPrivateEndpoints = allowPrivateEndpoints,
        )
    }

    private fun member(org: UUID, user: UUID, settings: Short) {
        OrgUsers.insert {
            it[id] = UUID.randomUUID()
            it[organizationId] = org
            it[userId] = user
            it[status] = "active"
            it[joinedAt] = NOW
            it[inviteToken] = "t-${UUID.randomUUID()}"
            it[orgSettings] = settings
        }
    }

    private fun session(value: String, user: UUID, org: UUID) {
        Sessions.insert {
            it[id] = UUID.randomUUID()
            it[userId] = user
            it[organizationId] = org
            it[sessionTokenHash] = TokenHasher.sha256Hex(value)
            it[status] = SessionStatus.ACTIVE
            it[expiresAt] = Instant.now().plusSeconds(3600)
            it[lastActiveAt] = Instant.now()
            it[revoked] = false
            it[createdAt] = Instant.now()
        }
    }

    // ── Harness ─────────────────────────────────────────────────────────────

    private class Api(val client: HttpClient, val token: String) {
        suspend fun call(method: HttpMethod, path: String, body: String? = null, asToken: String = token): Pair<HttpStatusCode, JsonElement?> {
            val response = client.request(path) {
                this.method = method
                header(HttpHeaders.Authorization, "Bearer $asToken")
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val text = response.bodyAsText()
            // A refusal that is not an ApiException (a host hook throwing, say)
            // comes back as the engine's own HTML page — report the status and
            // leave the body alone rather than failing to parse it.
            val parsed = runCatching { if (text.isBlank()) null else Json.parseToJsonElement(text) }.getOrNull()
            return response.status to parsed
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
        prefix: String? = unique("bodies"),
        endpoint: String? = TestMinio.endpoint,
        secret: String? = TestMinio.PASSWORD,
        accessKeyId: String? = TestMinio.USER,
        region: String? = null,
    ): String = buildJsonObject {
        put("name", name)
        put("kind", "s3")
        put("mode", mode)
        endpoint?.let { put("endpoint", it) }
        bucket?.let { put("bucket", it) }
        prefix?.let { put("prefix", it) }
        region?.let { put("region", it) }
        accessKeyId?.let { put("accessKeyId", it) }
        secret?.let { put("secretAccessKey", it) }
    }.toString()

    private fun unique(name: String) = "$name-${UUID.randomUUID().toString().take(8)}"

    private fun assertError(
        result: Pair<HttpStatusCode, JsonElement?>,
        status: HttpStatusCode,
        code: String,
        field: String? = null,
        reason: String? = null,
    ) {
        val (actual, body) = result
        assertEquals(status, actual, "body: $body")
        val obj = body!!.jsonObject
        assertEquals(code, obj["error"]!!.jsonPrimitive.content, "body: $body")
        if (field != null) assertEquals(field, obj["details"]!!.jsonObject["field"]!!.jsonPrimitive.content, "body: $body")
        if (reason != null) assertEquals(reason, obj["details"]!!.jsonObject["reason"]!!.jsonPrimitive.content, "body: $body")
    }

    private suspend fun Api.createStore(body: String, asToken: String = token): String {
        val (status, created) = call(HttpMethod.Post, STORES, body, asToken)
        assertEquals(HttpStatusCode.Created, status, "body: $created")
        return created!!.jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun store(orgId: UUID, input: BodyStoreInput): UUID =
        UUID.fromString(BodyStoreService.create(orgId, input).id)

    private fun s3Input(name: String, mode: String = "in_place", prefix: String? = "bodies") = BodyStoreInput(
        name = name, kind = "s3", mode = mode, endpoint = TestMinio.endpoint, bucket = BUCKET,
        prefix = prefix, accessKeyId = TestMinio.USER, secretAccessKey = TestMinio.PASSWORD,
    )

    private fun insertAgent(slug: String, deleted: Boolean = false) = transaction {
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
            it[ProbeAgents.deleted] = deleted
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

    private fun readBody(stepId: UUID) = runBlocking {
        ProbeResultController.getStepBody(orgId, serviceId, resultId, stepId, ownerId)
    }

    private fun storeRow(id: UUID) = transaction {
        BodyStores.selectAll().where { BodyStores.id eq id }.single()
    }

    // ── Management ──────────────────────────────────────────────────────────

    @Test
    fun `create, list, update and delete a store without ever returning its secret`() = withApi {
        val name = unique("crud")
        val prefix = unique("crud-bodies")
        val (status, created) = call(HttpMethod.Post, STORES, s3Store(name, prefix = prefix))
        assertEquals(HttpStatusCode.Created, status, "body: $created")
        val store = created!!.jsonObject
        val id = store["id"]!!.jsonPrimitive.content
        assertEquals(STORE_KEYS, store.keys, "the store response carries exactly these fields")
        assertEquals(name, store["name"]!!.jsonPrimitive.content)
        assertEquals("s3", store["kind"]!!.jsonPrimitive.content)
        assertEquals("in_place", store["mode"]!!.jsonPrimitive.content)
        assertEquals(prefix, store["prefix"]!!.jsonPrimitive.content)
        assertEquals(TestMinio.USER, store["accessKeyId"]!!.jsonPrimitive.content)
        assertEquals("true", store["hasSecret"]!!.jsonPrimitive.content)
        assertEquals(0, store["agents"]!!.jsonPrimitive.int)
        assertEquals(JsonNull, store["lastFailure"], "a store that has not failed says so with null")
        assertFalse(store.containsKey("secretAccessKey"))
        assertFalse(store.containsKey("secretEnc"))
        assertFalse(store.containsKey("organizationId"), "the owning org is not part of the API")
        assertFalse(created.toString().contains(TestMinio.PASSWORD), "the secret is never returned")

        val storedSecret = storeRow(UUID.fromString(id))
            .let { it[BodyStores.secretEnc]!! to it[BodyStores.secretIv]!! }
        assertNotEquals(TestMinio.PASSWORD, storedSecret.first, "encrypted at rest")
        assertEquals(
            TestMinio.PASSWORD,
            BodyStoreCrypto.decryptBound(storedSecret.first, storedSecret.second, "body_store:$id"),
        )

        val (listStatus, list) = call(HttpMethod.Get, STORES)
        assertEquals(HttpStatusCode.OK, listStatus)
        val listed = list!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject
        assertEquals(STORE_KEYS, listed.keys)
        assertFalse(list.toString().contains(TestMinio.PASSWORD))

        // An omitted secret keeps the stored one; the response is the updated row.
        val renamed = unique("crud-renamed")
        val (updateStatus, updated) = call(HttpMethod.Put, "$STORES/$id", s3Store(renamed, mode = "import", prefix = prefix, secret = null))
        assertEquals(HttpStatusCode.OK, updateStatus, "body: $updated")
        assertEquals(renamed, updated!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("import", updated.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals("true", updated.jsonObject["hasSecret"]!!.jsonPrimitive.content)
        assertEquals(STORE_KEYS, updated.jsonObject.keys)
        val afterUpdate = storeRow(UUID.fromString(id))
            .let { BodyStoreCrypto.decryptBound(it[BodyStores.secretEnc]!!, it[BodyStores.secretIv]!!, "body_store:$id") }
        assertEquals(TestMinio.PASSWORD, afterUpdate)

        val (deleteStatus, _) = call(HttpMethod.Delete, "$STORES/$id")
        assertEquals(HttpStatusCode.OK, deleteStatus)
        val (_, after) = call(HttpMethod.Get, STORES)
        assertTrue(after!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == id })
        assertError(call(HttpMethod.Delete, "$STORES/$id"), HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND)
    }

    @Test
    fun `renaming a store to its own name is not a name clash`() = withApi {
        val name = unique("same-name")
        val id = createStore(s3Store(name))
        val (status, updated) = call(HttpMethod.Put, "$STORES/$id", s3Store(name, secret = null))
        assertEquals(HttpStatusCode.OK, status, "body: $updated")
        assertEquals(name, updated!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `moving a store to the filesystem clears the credentials it no longer has`() = withApi {
        val id = createStore(s3Store(unique("to-fs")))
        val root = fsBase.resolve("to-fs-${UUID.randomUUID().toString().take(8)}").also { Files.createDirectories(it) }
        val body = buildJsonObject {
            put("name", unique("to-fs"))
            put("kind", "filesystem")
            put("mode", "in_place")
            put("rootPath", root.toString())
        }.toString()

        val (status, updated) = call(HttpMethod.Put, "$STORES/$id", body)

        assertEquals(HttpStatusCode.OK, status, "body: $updated")
        assertEquals("false", updated!!.jsonObject["hasSecret"]!!.jsonPrimitive.content)
        assertNull(storeRow(UUID.fromString(id))[BodyStores.secretEnc])
        assertNull(storeRow(UUID.fromString(id))[BodyStores.secretIv])
    }

    @Test
    fun `the default store is described read-only`() = withApi {
        val (status, body) = call(HttpMethod.Get, "$STORES/default")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("filesystem", body!!.jsonObject["kind"]!!.jsonPrimitive.content)
        assertEquals(defaultRoot.toString(), body.jsonObject["rootPath"]!!.jsonPrimitive.content)
        // Describing the default store is not a way to read the platform's keys.
        assertFalse(body.jsonObject.containsKey("endpoint"))
        assertFalse(body.jsonObject.containsKey("filesystemRoot"))
    }

    @Test
    fun `an S3 default store is described without its endpoint`() = withApi {
        BodyStoreService.configureDefault(
            DefaultBodyStore(
                kind = "s3", bucket = "platform-bodies", prefix = "p",
                endpoint = "https://platform.example.com", filesystemRoot = defaultRoot.toString(),
            ),
        )
        try {
            val (_, body) = call(HttpMethod.Get, "$STORES/default")
            assertEquals("s3", body!!.jsonObject["kind"]!!.jsonPrimitive.content)
            assertEquals("platform-bodies", body.jsonObject["bucket"]!!.jsonPrimitive.content)
            assertEquals("p", body.jsonObject["prefix"]!!.jsonPrimitive.content)
            assertFalse(body.toString().contains("platform.example.com"), "the endpoint stays out of the API")

            // And no store may be created over the platform's own bodies, whatever
            // endpoint spelling it names them by.
            for (endpoint in listOf("https://platform.example.com", "https://platform.example.com:443", TestMinio.endpoint)) {
                assertError(
                    call(HttpMethod.Post, STORES, s3Store(unique("over"), bucket = "platform-bodies", prefix = "p/sub", endpoint = endpoint)),
                    HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "prefix", reason = "overlaps_default",
                )
            }
        } finally {
            BodyStoreService.configureDefault(
                DefaultBodyStore(kind = "filesystem", rootPath = defaultRoot.toString(), filesystemRoot = defaultRoot.toString()),
            )
        }
    }

    @Test
    fun `every field the kind needs is required by name`() = withApi {
        fun raw(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
        assertError(
            call(HttpMethod.Post, STORES, raw("kind" to "s3", "mode" to "import")),
            HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "name",
        )
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("k"), "kind" to "ftp", "mode" to "import")),
            HttpStatusCode.BadRequest, ErrorCodes.INVALID_STORE_KIND, field = "kind",
        )
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("m"), "kind" to "s3", "mode" to "sometimes")),
            HttpStatusCode.BadRequest, ErrorCodes.INVALID_STORE_MODE, field = "mode",
        )
        for ((body, field) in listOf(
            s3Store(unique("b"), bucket = null) to "bucket",
            s3Store(unique("e"), endpoint = null) to "endpoint",
            s3Store(unique("a"), accessKeyId = null) to "accessKeyId",
            s3Store(unique("s"), secret = null) to "secretAccessKey",
            // An import store owns a prefix of its own: the platform empties it.
            s3Store(unique("p"), mode = "import", prefix = null) to "prefix",
        )) {
            assertError(
                call(HttpMethod.Post, STORES, body),
                HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = field,
            )
        }
        assertError(
            call(HttpMethod.Post, STORES, raw("name" to unique("r"), "kind" to "filesystem", "mode" to "in_place")),
            HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "rootPath",
        )
        assertError(
            call(HttpMethod.Post, STORES, s3Store("x".repeat(65))),
            HttpStatusCode.BadRequest, ErrorCodes.FIELD_TOO_LONG, field = "name",
        )
    }

    @Test
    fun `a field that is present but wrong says why`() = withApi {
        for ((body, field, reason) in listOf(
            Triple(s3Store(unique("b"), bucket = "Not A Bucket"), "bucket", "invalid_bucket"),
            Triple(s3Store(unique("r"), region = "eu central 1"), "region", "invalid_region"),
            Triple(s3Store(unique("p"), prefix = "bodies/../../etc"), "prefix", "invalid_prefix"),
            Triple(s3Store(unique("p"), prefix = "bodies//x"), "prefix", "invalid_prefix"),
        )) {
            assertError(
                call(HttpMethod.Post, STORES, body),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = field, reason = reason,
            )
        }
        val relative = buildJsonObject {
            put("name", unique("fs")); put("kind", "filesystem"); put("mode", "in_place"); put("rootPath", "relative/path")
        }.toString()
        assertError(
            call(HttpMethod.Post, STORES, relative),
            HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "rootPath", reason = "root_not_absolute",
        )
        for (root in listOf("/etc", defaultRoot.resolve("inside").toString(), fsBase.toString())) {
            val (status, body) = call(
                HttpMethod.Post, STORES,
                buildJsonObject {
                    put("name", unique("fs")); put("kind", "filesystem"); put("mode", "in_place"); put("rootPath", root)
                }.toString(),
            )
            assertEquals(HttpStatusCode.BadRequest, status, "$root → $body")
            assertEquals("rootPath", body!!.jsonObject["details"]!!.jsonObject["field"]!!.jsonPrimitive.content)
        }
        val name = unique("dup")
        createStore(s3Store(name))
        assertError(
            call(HttpMethod.Post, STORES, s3Store(name)),
            HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_NAME_TAKEN, field = "name",
        )
    }

    @Test
    fun `two stores may not overlap`() = withApi {
        val prefix = "overlap-${UUID.randomUUID().toString().take(8)}"
        createStore(s3Store(unique("first"), prefix = prefix))
        for (other in listOf(prefix, "$prefix/deeper")) {
            assertError(
                call(HttpMethod.Post, STORES, s3Store(unique("second"), prefix = other)),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "prefix", reason = "overlaps_store",
            )
        }
        // A neighbouring prefix that merely shares a string boundary is fine.
        createStore(s3Store(unique("neighbour"), prefix = "$prefix-other"))

        val root = fsBase.resolve("fs-overlap-${UUID.randomUUID().toString().take(8)}")
        Files.createDirectories(root.resolve("inner"))
        fun fs(name: String, path: Path) = buildJsonObject {
            put("name", name); put("kind", "filesystem"); put("mode", "in_place"); put("rootPath", path.toString())
        }.toString()
        createStore(fs(unique("fs-a"), root))
        assertError(
            call(HttpMethod.Post, STORES, fs(unique("fs-b"), root.resolve("inner"))),
            HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "rootPath", reason = "overlaps_store",
        )
    }

    @Test
    fun `a private endpoint is refused unless the operator allowed it`() = withApi {
        configureStores(allowPrivateEndpoints = false)
        for ((endpoint, reason) in listOf(
            "https://10.0.0.5" to "private_address",
            "https://169.254.169.254" to "private_address",
            "https://192.168.0.10:9000" to "private_address",
            "https://minio.railway.internal" to "internal_host",
            "http://s3.example.com" to "scheme_not_https",
            "https://minio" to "single_label_host",
            "https://s3.example.com/bucket" to "has_path",
        )) {
            assertError(
                call(HttpMethod.Post, STORES, s3Store(unique("ssrf"), endpoint = endpoint)),
                HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "endpoint", reason = reason,
            )
        }
        // The local MinIO the rest of this suite uses is refused too.
        assertError(
            call(HttpMethod.Post, STORES, s3Store(unique("local"))),
            HttpStatusCode.BadRequest, ErrorCodes.FIELD_INVALID, field = "endpoint",
        )

        // With the setting on, a private object store is exactly what is meant.
        configureStores(allowPrivateEndpoints = true)
        createStore(s3Store(unique("private-ok"), endpoint = "http://10.0.0.5:9000"))
        createStore(s3Store(unique("private-name"), endpoint = "http://minio:9000", prefix = "other"))
    }

    @Test
    fun `a store cannot be repointed to credentials the caller did not supply`() = withApi {
        val id = createStore(s3Store(unique("repoint")))
        // Endpoint, bucket and access key id all reach somewhere new; the stored
        // secret may not follow them there.
        for (body in listOf(
            s3Store(unique("repoint"), endpoint = "https://elsewhere.example.com", secret = null),
            s3Store(unique("repoint"), bucket = "someone-elses-bucket", secret = null),
            s3Store(unique("repoint"), accessKeyId = "AKIAOTHER", secret = null),
        )) {
            assertError(
                call(HttpMethod.Put, "$STORES/$id", body),
                HttpStatusCode.BadRequest, ErrorCodes.STORE_FIELD_REQUIRED, field = "secretAccessKey",
            )
        }
        // With a secret of its own the same change is fine.
        val (status, _) = call(
            HttpMethod.Put, "$STORES/$id",
            s3Store(unique("repoint"), endpoint = "https://elsewhere.example.com", secret = "a-new-secret"),
        )
        assertEquals(HttpStatusCode.OK, status)
    }

    @Test
    fun `a store holding bodies may not move, but may be reached a new way`() = withApi {
        val id = createStore(s3Store(unique("locked"), prefix = "locked-prefix"))
        insertStep("s3://$BUCKET/locked-prefix/call_0.json", UUID.fromString(id))

        for (body in listOf(
            s3Store(unique("locked"), prefix = "somewhere-else", secret = null),
            s3Store(unique("locked"), bucket = "another-bucket", secret = "s"),
        )) {
            assertError(
                call(HttpMethod.Put, "$STORES/$id", body),
                HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_LOCATION_LOCKED,
            )
        }
        // The same objects reached by a different host, with a new credential.
        val (status, updated) = call(
            HttpMethod.Put, "$STORES/$id",
            s3Store(unique("locked-renamed"), prefix = "locked-prefix", endpoint = "https://new-host.example.com", secret = "s"),
        )
        assertEquals(HttpStatusCode.OK, status, "body: $updated")
        assertEquals("https://new-host.example.com", updated!!.jsonObject["endpoint"]!!.jsonPrimitive.content)
    }

    // ── Ownership ───────────────────────────────────────────────────────────

    @Test
    fun `another organization's store does not exist`() = withApi {
        val mine = createStore(s3Store(unique("mine")))
        val theirs = createStore(s3Store(unique("theirs"), prefix = "theirs"), asToken = otherToken)

        // Not listed.
        val (_, mineList) = call(HttpMethod.Get, STORES)
        assertTrue(mineList!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == theirs })
        val (_, theirList) = call(HttpMethod.Get, STORES, asToken = otherToken)
        assertTrue(theirList!!.jsonArray.none { it.jsonObject["id"]!!.jsonPrimitive.content == mine })

        // Not readable, not editable, not deletable, not testable.
        for (method in listOf(HttpMethod.Put, HttpMethod.Delete)) {
            val body = if (method == HttpMethod.Put) s3Store(unique("steal"), secret = null) else null
            assertError(
                call(method, "$STORES/$theirs", body),
                HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
            )
        }
        assertError(
            call(HttpMethod.Post, "$STORES/$theirs/test"),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )

        // Not assignable to an agent, and not stampable onto a token.
        val slug = "borrow-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)
        assertError(
            call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$theirs"}"""),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )
        assertNull(agentStore(slug))
        assertError(
            call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug-t","bodyStoreId":"$theirs"}"""),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )
        // Two organizations may each call a store "eu".
        val shared = unique("eu")
        createStore(s3Store(shared, prefix = "eu-mine"))
        createStore(s3Store(shared, prefix = "eu-theirs"), asToken = otherToken)
    }

    @Test
    fun `a member without settings write may look but not touch`() = withApi {
        val id = createStore(s3Store(unique("readonly")))
        val slug = "ro-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)

        val (listStatus, _) = call(HttpMethod.Get, STORES, asToken = readerToken)
        assertEquals(HttpStatusCode.OK, listStatus, "settings read is enough to see the list")

        for ((method, path, body) in listOf(
            Triple(HttpMethod.Post, STORES, s3Store(unique("nope"))),
            Triple(HttpMethod.Put, "$STORES/$id", s3Store(unique("nope"), secret = null)),
            Triple(HttpMethod.Delete, "$STORES/$id", null),
            Triple(HttpMethod.Post, "$STORES/$id/test", null),
            Triple(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}"""),
            Triple(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug-t","bodyStoreId":"$id"}"""),
        )) {
            val (status, response) = call(method, path, body, asToken = readerToken)
            assertEquals(HttpStatusCode.Forbidden, status, "$method $path → $response")
        }
        assertNull(agentStore(slug), "nothing was assigned")
    }

    // ── The host hooks ──────────────────────────────────────────────────────

    @Test
    fun `a before-hook on the write refuses every store change, atomically`() = withApi {
        val existing = createStore(s3Store(unique("hooked")))
        val seen = mutableListOf<InterceptorContext>()
        Interceptors.before(BodyStoreService.OP_WRITE) { ctx ->
            seen.add(ctx)
            throw IllegalStateException("refused by the host")
        }
        val before = transaction { BodyStores.selectAll().count() }

        val name = unique("refused")
        val (createStatus, _) = call(HttpMethod.Post, STORES, s3Store(name))
        assertEquals(HttpStatusCode.InternalServerError, createStatus)
        val (updateStatus, _) = call(HttpMethod.Put, "$STORES/$existing", s3Store(unique("refused"), secret = null))
        assertEquals(HttpStatusCode.InternalServerError, updateStatus)
        val (deleteStatus, _) = call(HttpMethod.Delete, "$STORES/$existing")
        assertEquals(HttpStatusCode.InternalServerError, deleteStatus)

        assertEquals(before, transaction { BodyStores.selectAll().count() }, "not one row was written")
        assertTrue(
            transaction { BodyStores.selectAll().where { BodyStores.name eq name }.empty() },
            "the refused store does not exist",
        )
        assertNotNull(storeRow(UUID.fromString(existing)), "the existing store is untouched")

        assertEquals(listOf("create", "update", "delete"), seen.map { it.extra["action"] })
        assertEquals(orgId, seen.first().orgId)
        assertEquals(ownerId, seen.first().userId)
        assertTrue(seen.all { it.extra["storeId"] is UUID }, "every hook is told which store")
        assertEquals(UUID.fromString(existing), seen[1].extra["storeId"])
    }

    @Test
    fun `a before-hook on the assignment refuses both an agent and a token`() = withApi {
        val id = createStore(s3Store(unique("assign-hook")))
        val slug = "hooked-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)
        val seen = mutableListOf<InterceptorContext>()
        Interceptors.before(BodyStoreService.OP_ASSIGN) { ctx ->
            seen.add(ctx)
            throw IllegalStateException("refused by the host")
        }

        val (assignStatus, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""")
        assertEquals(HttpStatusCode.InternalServerError, assignStatus)
        assertNull(agentStore(slug), "the agent was not moved")

        val tokenSlug = "hooked-token-${UUID.randomUUID().toString().take(8)}"
        val (mintStatus, _) = call(
            HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$tokenSlug","bodyStoreId":"$id"}""",
        )
        assertEquals(HttpStatusCode.InternalServerError, mintStatus)
        assertTrue(
            transaction { AgentBootstrapTokens.selectAll().where { AgentBootstrapTokens.slug eq tokenSlug }.empty() },
            "no token was minted",
        )

        assertEquals(listOf("agent", "token"), seen.map { it.extra["target"] })
        assertEquals(listOf(slug, tokenSlug), seen.map { it.extra["slug"] })
        assertTrue(seen.all { it.extra["storeId"] == UUID.fromString(id) })
        assertEquals(orgId, seen.first().orgId)
        assertEquals(ownerId, seen.first().userId)
    }

    @Test
    fun `moving an agent back to the default store is still an assignment the host sees`() = withApi {
        val id = createStore(s3Store(unique("unassign-hook")))
        val slug = "unassign-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""").first)

        val seen = mutableListOf<InterceptorContext>()
        Interceptors.before(BodyStoreService.OP_ASSIGN) { ctx ->
            seen.add(ctx)
            throw IllegalStateException("refused by the host")
        }
        val (status, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":null}""")

        assertEquals(HttpStatusCode.InternalServerError, status)
        assertEquals(UUID.fromString(id), agentStore(slug), "the agent stayed on its store")
        assertEquals(1, seen.size)
        assertEquals("agent", seen.single().extra["target"])
        assertNull(seen.single().extra["storeId"], "moving to the default store names no store")
    }

    // ── The test probe ──────────────────────────────────────────────────────

    @Test
    fun `test probes the store with its own credentials`() = withApi {
        val good = createStore(s3Store(unique("probe")))
        val (status, ok) = call(HttpMethod.Post, "$STORES/$good/test")
        assertEquals(HttpStatusCode.OK, status)
        assertEquals("true", ok!!.jsonObject["ok"]!!.jsonPrimitive.content, "body: $ok")
        assertEquals(JsonNull, ok.jsonObject["error"] ?: JsonNull)

        val missing = createStore(s3Store(unique("probe-missing"), bucket = "no-such-bucket"))
        val (_, noBucket) = call(HttpMethod.Post, "$STORES/$missing/test")
        assertEquals("false", noBucket!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("bucket_not_found", noBucket.jsonObject["error"]!!.jsonPrimitive.content)

        // A key the store does not accept is the caller's own typo, not a policy.
        val wrongKey = createStore(s3Store(unique("probe-denied"), secret = "not-the-secret"))
        val (_, denied) = call(HttpMethod.Post, "$STORES/$wrongKey/test")
        assertEquals("false", denied!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("invalid_credentials", denied.jsonObject["error"]!!.jsonPrimitive.content)

        // A filesystem store whose root has gone.
        val root = fsBase.resolve("probe-fs-${UUID.randomUUID().toString().take(8)}").also { Files.createDirectories(it) }
        val fsStore = createStore(
            buildJsonObject {
                put("name", unique("probe-fs")); put("kind", "filesystem"); put("mode", "in_place")
                put("rootPath", root.toString())
            }.toString(),
        )
        assertEquals("true", call(HttpMethod.Post, "$STORES/$fsStore/test").second!!.jsonObject["ok"]!!.jsonPrimitive.content)
        Files.delete(root)
        val (_, gone) = call(HttpMethod.Post, "$STORES/$fsStore/test")
        assertEquals("not_a_directory", gone!!.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a store whose secret no longer decrypts says so`() = withApi {
        val id = createStore(s3Store(unique("rotated")))
        // What a key rotation without --rewrap-body-stores leaves behind.
        transaction {
            BodyStores.update({ BodyStores.id eq UUID.fromString(id) }) {
                it[secretEnc] = "bm90LXRoZS1yaWdodC1jaXBoZXJ0ZXh0"
                it[updatedAt] = Instant.now().plusSeconds(2).truncatedTo(ChronoUnit.SECONDS)
            }
        }
        BodyStoreRegistry.invalidate(UUID.fromString(id))

        val (_, result) = call(HttpMethod.Post, "$STORES/$id/test")

        assertEquals("false", result!!.jsonObject["ok"]!!.jsonPrimitive.content)
        assertEquals("secret_undecryptable", result.jsonObject["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed call is remembered on the store and cleared by the next good one`() = withApi {
        val id = createStore(s3Store(unique("health"), bucket = "no-such-bucket"))
        call(HttpMethod.Post, "$STORES/$id/test")

        val (_, list) = call(HttpMethod.Get, STORES)
        val failure = list!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }
            .jsonObject["lastFailure"]!!.jsonObject
        assertEquals("bucket_not_found", failure["code"]!!.jsonPrimitive.content)
        assertNotNull(Instant.parse(failure["at"]!!.jsonPrimitive.content), "an ISO instant")

        // Point it at a bucket that is there; the next probe clears the record.
        assertEquals(
            HttpStatusCode.OK,
            call(HttpMethod.Put, "$STORES/$id", s3Store(unique("health"))).first,
        )
        call(HttpMethod.Post, "$STORES/$id/test")
        val (_, after) = call(HttpMethod.Get, STORES)
        assertEquals(
            JsonNull,
            after!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["lastFailure"],
        )
    }

    @Test
    fun `a guarded endpoint is refused by the client, not merely at save time`() = withApi {
        // The store is saved while private endpoints are allowed and dialled
        // after the operator turns the setting off — the guard is in the client
        // the registry builds, not only in validation.
        val id = createStore(s3Store(unique("guarded"), prefix = "guarded"))
        configureStores(allowPrivateEndpoints = false)
        try {
            val (_, result) = call(HttpMethod.Post, "$STORES/$id/test")
            assertEquals("false", result!!.jsonObject["ok"]!!.jsonPrimitive.content)
            assertEquals("blocked_endpoint", result.jsonObject["error"]!!.jsonPrimitive.content)
        } finally {
            configureStores(allowPrivateEndpoints = true)
        }
    }

    // ── In use, assignment, enrolment ───────────────────────────────────────

    @Test
    fun `a store in use cannot be deleted`() = withApi {
        val id = createStore(s3Store(unique("in-use"), prefix = "in-use"))
        val slug = "in-use-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)

        val (assigned, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""")
        assertEquals(HttpStatusCode.OK, assigned)
        assertEquals(UUID.fromString(id), agentStore(slug))
        val (_, list) = call(HttpMethod.Get, STORES)
        assertEquals(1, list!!.jsonArray.single { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject["agents"]!!.jsonPrimitive.int)
        // The agent list says which store each agent is on.
        val (_, agents) = call(HttpMethod.Get, "/api/v1/agents/list")
        assertEquals(
            id,
            agents!!.jsonArray.single { it.jsonObject["slug"]!!.jsonPrimitive.content == slug }
                .jsonObject["bodyStoreId"]!!.jsonPrimitive.content,
        )

        val refused = call(HttpMethod.Delete, "$STORES/$id")
        assertError(refused, HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_IN_USE)
        val details = refused.second!!.jsonObject["details"]!!.jsonObject
        assertEquals(1, details["agents"]!!.jsonPrimitive.int)
        assertEquals(0, details["tokens"]!!.jsonPrimitive.int)
        assertEquals("false", details["bodies"]!!.jsonPrimitive.content)

        val (unassigned, _) = call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":null}""")
        assertEquals(HttpStatusCode.OK, unassigned)
        assertNull(agentStore(slug))
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "$STORES/$id").first)
    }

    @Test
    fun `an outstanding token holds a store, a spent one lets it go`() = withApi {
        val id = createStore(s3Store(unique("token-held"), prefix = "token-held"))
        val slug = "token-held-${UUID.randomUUID().toString().take(8)}"
        assertEquals(
            HttpStatusCode.OK,
            call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug","bodyStoreId":"$id"}""").first,
        )

        val refused = call(HttpMethod.Delete, "$STORES/$id")
        assertError(refused, HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_IN_USE)
        assertEquals(1, refused.second!!.jsonObject["details"]!!.jsonObject["tokens"]!!.jsonPrimitive.int)

        // A used token is history, and history holds no store.
        transaction {
            AgentBootstrapTokens.update({ AgentBootstrapTokens.slug eq slug }) { it[used] = true }
        }
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "$STORES/$id").first)
        assertNull(
            transaction { AgentBootstrapTokens.selectAll().where { AgentBootstrapTokens.slug eq slug }.single()[AgentBootstrapTokens.bodyStoreId] },
        )
    }

    @Test
    fun `a decommissioned agent holds no store`() = withApi {
        val id = createStore(s3Store(unique("decom"), prefix = "decom"))
        val slug = "decom-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""").first)
        transaction { ProbeAgents.update({ ProbeAgents.slug eq slug }) { it[deleted] = true } }

        assertEquals(HttpStatusCode.OK, call(HttpMethod.Delete, "$STORES/$id").first)
        assertNull(agentStore(slug))
    }

    @Test
    fun `a store holding bodies is refused, and released with forgetBodies`() = withApi {
        val id = createStore(s3Store(unique("held"), prefix = "held"))
        val slug = "held-${UUID.randomUUID().toString().take(8)}"
        insertAgent(slug)
        assertEquals(HttpStatusCode.OK, call(HttpMethod.Put, "/api/v1/agents/$slug/body-store", """{"storeId":"$id"}""").first)
        val tokenSlug = "held-token-${UUID.randomUUID().toString().take(8)}"
        call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$tokenSlug","bodyStoreId":"$id"}""")
        val stepId = insertStep("s3://$BUCKET/held/call_0.json", UUID.fromString(id))

        val refused = call(HttpMethod.Delete, "$STORES/$id")
        assertError(refused, HttpStatusCode.Conflict, ErrorCodes.BODY_STORE_IN_USE)
        val details = refused.second!!.jsonObject["details"]!!.jsonObject
        assertEquals(1, details["agents"]!!.jsonPrimitive.int)
        assertEquals(1, details["tokens"]!!.jsonPrimitive.int)
        assertEquals("true", details["bodies"]!!.jsonPrimitive.content)
        // A plain delete changed nothing.
        assertEquals(UUID.fromString(id), agentStore(slug))

        val (status, _) = call(HttpMethod.Delete, "$STORES/$id?forgetBodies=true")

        assertEquals(HttpStatusCode.OK, status)
        assertNull(agentStore(slug), "its agents are back on the default store")
        assertNull(
            transaction { AgentBootstrapTokens.selectAll().where { AgentBootstrapTokens.slug eq tokenSlug }.single()[AgentBootstrapTokens.bodyStoreId] },
            "its outstanding tokens lost the stamp",
        )
        val step = transaction { ProbeSteps.selectAll().where { ProbeSteps.id eq stepId }.single() }
        assertNull(step[ProbeSteps.responseBodyStorageUrl], "the body is forgotten")
        assertNull(step[ProbeSteps.bodyStoreId])
        assertEquals("storeRemoved", step[ProbeSteps.bodyNotStoredReason])
        assertTrue(transaction { BodyStores.selectAll().where { BodyStores.id eq UUID.fromString(id) }.empty() })
    }

    @Test
    fun `assignment answers for an agent that is not there`() = withApi {
        val id = createStore(s3Store(unique("assign"), prefix = "assign"))
        // No such slug at all.
        assertError(
            call(HttpMethod.Put, "/api/v1/agents/no-such-agent/body-store", """{"storeId":"$id"}"""),
            HttpStatusCode.NotFound, ErrorCodes.AGENT_NOT_FOUND,
        )
        // Decommissioned: the row is there, the agent is not.
        val goneSlug = "gone-${UUID.randomUUID().toString().take(8)}"
        insertAgent(goneSlug, deleted = true)
        assertError(
            call(HttpMethod.Put, "/api/v1/agents/$goneSlug/body-store", """{"storeId":"$id"}"""),
            HttpStatusCode.NotFound, ErrorCodes.AGENT_NOT_FOUND,
        )
        // A store that does not exist.
        val liveSlug = "live-${UUID.randomUUID().toString().take(8)}"
        insertAgent(liveSlug)
        assertError(
            call(HttpMethod.Put, "/api/v1/agents/$liveSlug/body-store", """{"storeId":"${UUID.randomUUID()}"}"""),
            HttpStatusCode.NotFound, ErrorCodes.BODY_STORE_NOT_FOUND,
        )
        // And a malformed one is a bad request, not a 500.
        val (status, _) = call(HttpMethod.Put, "/api/v1/agents/$liveSlug/body-store", """{"storeId":"not-a-uuid"}""")
        assertEquals(HttpStatusCode.BadRequest, status)
    }

    @Test
    fun `a bootstrap token carries its store, narrowed to the agent, and registration assigns it`() = withApi {
        val id = createStore(s3Store(unique("enrol"), prefix = "enrol"))
        val slug = "enrol-${UUID.randomUUID().toString().take(8)}"
        val (status, minted) = call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug","bodyStoreId":"$id"}""")
        assertEquals(HttpStatusCode.OK, status, "body: $minted")
        val bodyStore = minted!!.jsonObject["bodyStore"]!!.jsonObject
        assertEquals(id, bodyStore["id"]!!.jsonPrimitive.content)
        assertEquals(BUCKET, bodyStore["bucket"]!!.jsonPrimitive.content)
        assertEquals("in_place", bodyStore["mode"]!!.jsonPrimitive.content)
        // The agent writes under its own corner of the store, and that is what
        // the dashboard prints as PROBE_AGENT_S3_PREFIX.
        assertEquals("enrol/$slug", bodyStore["prefix"]!!.jsonPrimitive.content)
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

    @Test
    fun `a filesystem store prints the agent's own directory`() = withApi {
        val root = fsBase.resolve("enrol-fs-${UUID.randomUUID().toString().take(8)}").also { Files.createDirectories(it) }
        val id = createStore(
            buildJsonObject {
                put("name", unique("enrol-fs")); put("kind", "filesystem"); put("mode", "in_place")
                put("rootPath", root.toString())
            }.toString(),
        )
        val slug = "enrol-fs-${UUID.randomUUID().toString().take(8)}"

        val (_, minted) = call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug","bodyStoreId":"$id"}""")

        val bodyStore = minted!!.jsonObject["bodyStore"]!!.jsonObject
        assertEquals("$root/$slug", bodyStore["rootPath"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, bodyStore["prefix"] ?: JsonNull)
    }

    @Test
    fun `the token response reflects a store a host hook stamped`() = withApi {
        val stamped = createStore(s3Store(unique("stamped"), prefix = "stamped"))
        val slug = "stamped-${UUID.randomUUID().toString().take(8)}"
        // A host that decides where this organization's agents write, whatever
        // the request asked for. The response must describe the store the agent
        // will actually enrol on, not the one the request named.
        Interceptors.after("agent.bootstrap.create") { _, result ->
            AgentBootstrapTokens.update({ AgentBootstrapTokens.slug eq slug }) {
                it[bodyStoreId] = UUID.fromString(stamped)
            }
            result
        }

        val (status, minted) = call(HttpMethod.Post, "/api/v1/agents/bootstrap-token", """{"slug":"$slug"}""")

        assertEquals(HttpStatusCode.OK, status, "body: $minted")
        assertEquals(stamped, minted!!.jsonObject["bodyStore"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("stamped/$slug", minted.jsonObject["bodyStore"]!!.jsonObject["prefix"]!!.jsonPrimitive.content)
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
        val store = store(orgId, s3Input(unique("read"), prefix = "read-bodies"))
        TestMinio.put(BUCKET, "read-bodies/run-1/call_0.json", """{"in":"place"}""".toByteArray(), "application/json")

        val body = readBody(insertStep("s3://$BUCKET/read-bodies/run-1/call_0.json", store))

        assertEquals(BodyStorageClient.BodyContent.Inline("""{"in":"place"}""", "application/json"), body)
    }

    @Test
    fun `a binary in_place body comes back base64, never mangled`() {
        val store = store(orgId, s3Input(unique("binary"), prefix = "binary-bodies"))
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0xFF.toByte(), 0xFE.toByte())
        TestMinio.put(BUCKET, "binary-bodies/call_0.png", png, "image/png")

        val body = readBody(insertStep("s3://$BUCKET/binary-bodies/call_0.png", store))

        val inline = body as BodyStorageClient.BodyContent.Inline
        assertEquals("base64", inline.encoding)
        assertEquals("image/png", inline.contentType)
        assertArrayEquals(png, java.util.Base64.getDecoder().decode(inline.content))
    }

    @Test
    fun `a body that is gone is 410, a store that is not answering is 503`() {
        val store = store(orgId, s3Input(unique("gone"), prefix = "gone-bodies"))
        // Written, but outside the store: the confinement refusal is a real one,
        // not "there was nothing there anyway".
        TestMinio.put(BUCKET, "outside-the-store/call_0.json", "not yours".toByteArray())

        for (uri in listOf(
            "s3://$BUCKET/gone-bodies/never-written.json",
            "s3://$BUCKET/outside-the-store/call_0.json",
            "file:///etc/passwd",
        )) {
            val e = assertThrows(ApiException::class.java) { readBody(insertStep(uri, store)) }
            assertEquals(HttpStatusCode.Gone, e.status, uri)
            assertEquals(ErrorCodes.BODY_GONE, e.code, uri)
        }
        assertEquals("not yours", String(TestMinio.get(BUCKET, "outside-the-store/call_0.json")), "untouched")

        // A store nobody is listening on, and one whose key the store refuses:
        // the body is probably still there, so this is not `body_gone`.
        val unreachable = store(
            orgId,
            BodyStoreInput(
                name = unique("unreachable"), kind = "s3", mode = "in_place",
                endpoint = "http://127.0.0.1:1", bucket = BUCKET, prefix = "unreachable",
                accessKeyId = TestMinio.USER, secretAccessKey = TestMinio.PASSWORD,
            ),
        )
        val wrongSecret = store(
            orgId,
            BodyStoreInput(
                name = unique("wrong-secret"), kind = "s3", mode = "in_place",
                endpoint = TestMinio.endpoint, bucket = BUCKET, prefix = "wrong-secret",
                accessKeyId = TestMinio.USER, secretAccessKey = "not-the-secret",
            ),
        )
        TestMinio.put(BUCKET, "wrong-secret/call_0.json", "there all along".toByteArray())
        for ((id, uri) in listOf(
            unreachable to "s3://$BUCKET/unreachable/call_0.json",
            wrongSecret to "s3://$BUCKET/wrong-secret/call_0.json",
        )) {
            val e = assertThrows(ApiException::class.java) { readBody(insertStep(uri, id)) }
            assertEquals(HttpStatusCode.ServiceUnavailable, e.status, uri)
            assertEquals(ErrorCodes.BODY_STORE_UNAVAILABLE, e.code, uri)
            // And the store says so in the list, so nobody has to click through
            // bodies to find out.
            assertNotNull(storeRow(id)[BodyStores.lastFailureCode], uri)
        }
    }

    @Test
    fun `an over-size in_place body is refused`() {
        val root = fsBase.resolve("fs-store-${UUID.randomUUID().toString().take(8)}").also { Files.createDirectories(it) }
        val store = store(
            orgId,
            BodyStoreInput(name = unique("big"), kind = "filesystem", mode = "in_place", rootPath = root.toString()),
        )
        val big = root.resolve("big.bin")
        RandomAccessFile(big.toFile(), "rw").use { it.setLength(BodyStoreRegistry.MAX_BODY_BYTES + 1) }

        val e = assertThrows(ApiException::class.java) { readBody(insertStep("file://$big", store)) }

        assertEquals(HttpStatusCode.PayloadTooLarge, e.status)
        assertEquals(ErrorCodes.BODY_TOO_LARGE, e.code)
    }

    @Test
    fun `a client cached for a store is rebuilt when the store changes`() {
        val id = store(orgId, s3Input(unique("cache"), prefix = "cache-bodies"))
        TestMinio.put(BUCKET, "cache-bodies/call_0.json", "first".toByteArray())
        assertEquals(
            BodyStorageClient.BodyContent.Inline("first", "application/octet-stream"),
            readBody(insertStep("s3://$BUCKET/cache-bodies/call_0.json", id)),
        )

        // Repoint the credentials at something the store will not accept. The
        // ingestor and the worker never see an invalidate() call — they notice
        // because `updated_at` moved.
        BodyStoreService.update(
            orgId, id,
            BodyStoreInput(
                name = unique("cache"), kind = "s3", mode = "in_place", endpoint = TestMinio.endpoint,
                bucket = BUCKET, prefix = "cache-bodies", accessKeyId = TestMinio.USER,
                secretAccessKey = "not-the-secret",
            ),
        )

        val e = assertThrows(ApiException::class.java) {
            readBody(insertStep("s3://$BUCKET/cache-bodies/call_0.json", id))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, e.status)
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
