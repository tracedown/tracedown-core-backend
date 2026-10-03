package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.assertThrows
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.common.storage.BodyStoreRegistry
import dev.tracedown.common.storage.BodyStorageClient
import org.jetbrains.exposed.v1.core.and
import dev.tracedown.gateway.util.ForbiddenException
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.ProbeAgents
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.BodyStores
import dev.tracedown.common.models.OrgAuditLog
import dev.tracedown.common.storage.BodyStoreInput
import dev.tracedown.common.storage.BodyStoreService
import dev.tracedown.gateway.controllers.results.ProbeResultController
import dev.tracedown.gateway.routes.publicapi.PublicApiOperations
import dev.tracedown.gateway.util.VariableCrypto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProbeResults
import dev.tracedown.common.models.ProbeSteps
import dev.tracedown.common.models.Users
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.gateway.controllers.orgs.GroupController
import dev.tracedown.gateway.controllers.orgs.OrgVariableController
import dev.tracedown.gateway.controllers.orgs.ResourceAccessController
import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.silences.SilenceController
import dev.tracedown.gateway.controllers.webhooks.WebhookController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.data.CreateVariableRequest
import dev.tracedown.gateway.data.orgs.UpsertAccessRequest
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.silences.CreateSilenceRequest
import dev.tracedown.gateway.data.webhooks.CreateWebhookRequest
import dev.tracedown.gateway.data.webhooks.WebhookBindingRequest
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import dev.tracedown.gateway.routes.publicapi.PublicApi
import dev.tracedown.gateway.util.ApiRateLimit
import io.ktor.http.HttpMethod
import io.ktor.server.application.pluginOrNull
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.RoutingRoot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * The key-authenticated API's resources, route by route.
 *
 * Every route under [PublicApi.V1] is a [Case], and every case is put through
 * the same four questions, against fixtures of its own:
 *
 *  - a read-only key is refused anything but a read (`api_key_read_only`);
 *  - a key whose user may not do it gets exactly the answer that user's
 *    session gets from the dashboard's twin of the route — the same status,
 *    the same code, the same body for a read;
 *  - a key whose user may do it gets, for a read, exactly what that user's
 *    session gets from the twin, field for field;
 *  - and the call succeeds, with the status and shape the route promises.
 *
 * So a public handler that authorized differently from its twin — more, or
 * less — fails here, and so does one that answered differently. A route with
 * no twin (`/key`) only has the first and last.
 *
 * [cases] is held to the mounted tree: a route added without a case fails
 * `every public route has a case`.
 */
@Testcontainers
class ApiKeyResourcesTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_api_key_resources_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val PASSWORD = "ApiKeyResources123!"

        /** The default store's filesystem root, and its bucket. */
        private lateinit var storageRoot: Path
        private const val BUCKET = "public-api-bodies"

        /** Where body stores of the filesystem kind may be made. */
        private lateinit var storeBase: Path

        /** One window for the whole run, so no budget resets mid-test. */
        private const val ONE_WINDOW = "315360000"

        /** Hands each test its own client address, from two documentation ranges. */
        private val addresses = AtomicInteger(0)

        private fun nextAddress(): String {
            val n = addresses.incrementAndGet()
            return if (n < 250) "198.51.100.$n" else "203.0.113.${n - 249}"
        }

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            // Nothing a previous suite registered into the tree.
            PublicApi.clearAll()

            storageRoot = Files.createTempDirectory("public-api-bodies").toRealPath()
            storeBase = Files.createTempDirectory("public-api-stores").toRealPath()
            TestS3.bucket(BUCKET)

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
                // Scripts in the fixtures name example.com, which no one here
                // has verified.
                "platform.trustedDomainMode" to "true",
                // The default store holds bodies both ways: as files, and as
                // objects — the case where the dashboard is handed a link.
                "storage.filesystemRoot" to storageRoot.toString(),
                "storage.s3.endpoint" to TestS3.endpoint,
                "storage.s3.accessKey" to TestS3.USER,
                "storage.s3.secretKey" to TestS3.PASSWORD,
                "storage.s3.bucket" to BUCKET,
                // Body stores too, for the reads that go through one.
                "storage.stores.filesystemBases" to storeBase.toString(),
                "storage.stores.privateEndpoints" to "true",
                "storage.stores.aesKey" to "1111111111111111111111111111111111111111111111111111111111111111",
                // On, as in production, with budgets this suite cannot reach.
                "rateLimit.enabled" to "true",
                "rateLimit.general.maxRequests" to "100000",
                "rateLimit.auth.maxRequests" to "100000",
                "rateLimit.api.maxRequests" to "100000",
                "rateLimit.api.windowSeconds" to ONE_WINDOW,
                "rateLimit.apiFailure.maxRequests" to "100000",
                "rateLimit.apiFailure.windowSeconds" to ONE_WINDOW,
            ))
            val env = applicationEnvironment {
                config = HoconApplicationConfig(overrides.withFallback(ConfigFactory.load()))
            }
            // Port 0: the system picks a free one when the connector binds,
            // so nothing else can take it between choosing and binding.
            server = embeddedServer(Netty, env, configure = {
                connector { port = 0 }
            })
            server.start(wait = false)
            serverPort = runBlocking { server.engine.resolvedConnectors().first().port }
            awaitReady()
        }

        /** Waits until the gateway answers, rather than for a fixed time. */
        private fun awaitReady() {
            val probe = OkHttpClient.Builder().callTimeout(Duration.ofSeconds(2)).build()
            val deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos()
            while (System.nanoTime() < deadline) {
                val up = runCatching {
                    probe.newCall(Request.Builder().url("http://localhost:$serverPort/ping").build()).execute().use { it.code == 200 }
                }.getOrDefault(false)
                if (up) return
                Thread.sleep(50)
            }
            error("The gateway did not answer /ping within a minute")
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            PublicApi.clearAll()
            Interceptors.clearAll()
            ApiRateLimit.init(null)
            storageRoot.toFile().deleteRecursively()
            storeBase.toFile().deleteRecursively()
        }
    }

    private val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build()

    /** A script that validates and names nothing anyone has to verify. */
    private val SCRIPT = "get(\"https://example.com\").expect(status: 200)"
    private val jsonType = "application/json".toMediaType()

    // ── Fixtures ──

    private class Account(val userId: UUID, val orgId: UUID, val email: String)

    /**
     * One organization with one of everything the public API reaches, the
     * credentials that reach it, and a member with no permission at all.
     */
    private class Fx(
        val owner: Account,
        /** A plain member: no section, no grant. */
        val member: Account,
        /** A member who holds a grant on [service], for the grant to be withdrawn. */
        val grantee: Account,
        val ownerSession: String,
        val memberSession: String,
        val writeKey: String,
        val readKey: String,
        val memberKey: String,
        val workspace: UUID,
        val project: UUID,
        val service: UUID,
        val serviceVersion: Int,
        val orgVar: UUID,
        val wsVar: UUID,
        val projVar: UUID,
        val svcVar: UUID,
        val result: UUID,
        /** A step whose body is a file in the default store. */
        val step: UUID,
        val stepBody: String,
        val silence: UUID,
        /** Bound to [service] as [binding]. */
        val webhook: UUID,
        /** Bound to nothing. */
        val spareWebhook: UUID,
        val binding: UUID,
    )

    private fun newOwner(): Account = transaction {
        val userId = UUID.randomUUID()
        val email = "owner-${userId.toString().take(8)}@tracedown.dev"
        insertUser(userId, email)
        val orgId = UUID.randomUUID()
        Organizations.insert {
            it[id] = orgId
            it[name] = "Resources Org ${orgId.toString().take(6)}"
            it[ownerId] = userId
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
        insertMembership(orgId, userId)
        Users.update({ Users.id eq userId }) { it[selectedOrgId] = orgId }
        Account(userId, orgId, email)
    }

    private fun newMember(orgId: UUID): Account = transaction {
        val userId = UUID.randomUUID()
        val email = "member-${userId.toString().take(8)}@tracedown.dev"
        insertUser(userId, email)
        insertMembership(orgId, userId)
        Users.update({ Users.id eq userId }) { it[selectedOrgId] = orgId }
        Account(userId, orgId, email)
    }

    private fun insertUser(userId: UUID, email: String) {
        Users.insert {
            it[id] = userId
            it[Users.email] = email
            it[passwordHash] = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray())
            it[displayName] = email.substringBefore("@")
            it[isActive] = true
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
    }

    private fun insertMembership(orgId: UUID, userId: UUID) {
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

    private fun login(account: Account, address: String): String {
        val (status, raw) = send(address, "POST", "/api/v1/auth/login", null, """{"email":"${account.email}","password":"$PASSWORD"}""")
        assertEquals(200, status, "Login response: $raw")
        return obj(raw).str("token")
    }

    private fun mintKey(session: String, access: String, address: String): String {
        val (status, raw) = send(
            address, "POST", "/api/v1/me/api-keys", session,
            """{"name":"$access key","access":"$access","password":"$PASSWORD"}""",
        )
        assertEquals(201, status, "Mint response: $raw")
        return obj(raw).str("key")
    }

    /**
     * Builds the fixtures through the controllers the routes call — the same
     * code, without the HTTP — so each case starts from a known organization.
     */
    private fun fixtures(address: String): Fx {
        val owner = newOwner()
        val member = newMember(owner.orgId)
        val grantee = newMember(owner.orgId)
        val o = owner.orgId
        val u = owner.userId

        val ws = UUID.fromString(WorkspaceController.create(o, CreateWorkspaceRequest("Fixture WS"), u).id)
        val proj = UUID.fromString(ProjectController.create(o, ws, CreateProjectRequest(ws.toString(), "Fixture Project"), u).id)
        // Created with its script, which switches it on: a service that can run.
        val svc = ServiceController.create(
            o, proj, CreateServiceRequest(projectId = proj.toString(), name = "Fixture Service", script = SCRIPT), u,
        )
        assertTrue(svc.isActive, "A create with a script switches the service on")
        assertEquals(2, svc.version, "A create with a script saves it as the first script save does")
        val svcId = UUID.fromString(svc.id)

        val orgVar = OrgVariableController.create(o, CreateVariableRequest("ORG_VAR", "org-value"), u).id
        val wsVar = WorkspaceController.createVariable(o, ws, CreateVariableRequest("WS_VAR", "ws-value"), u).id
        val projVar = ProjectController.createVariable(o, proj, CreateVariableRequest("PROJ_VAR", "proj-value"), u).id
        val svcVar = ServiceController.createVariable(o, svcId, CreateVariableRequest("SVC_VAR", "svc-value", "secret"), u).id

        val silence = SilenceController.create(o, u, CreateSilenceRequest(channel = "email")).id
        val webhook = WebhookController.create(o, CreateWebhookRequest(name = "Fixture hook", url = "https://example.com/hook"), u).id
        val spare = WebhookController.create(o, CreateWebhookRequest(name = "Spare hook", url = "https://example.com/spare"), u).id
        val binding = WebhookController.createBinding(o, "service", svcId, WebhookBindingRequest(webhook), u).id
        GroupController.createGroup(o, "Fixture group", u)
        ResourceAccessController.upsert(o, "service", svcId, UpsertAccessRequest("user", grantee.userId.toString(), 2), u)

        val resultId = UUID.randomUUID()
        val stepId = UUID.randomUUID()
        val bodyText = "{\"fixture\":\"$stepId\"}"
        val bodyFile = Files.createDirectories(storageRoot.resolve(o.toString())).resolve("$stepId.json")
        Files.writeString(bodyFile, bodyText)
        transaction {
            ProbeResults.insert {
                it[id] = resultId
                it[serviceId] = svcId
                it[projectId] = proj
                it[workspaceId] = ws
                it[organizationId] = o
                it[startedAt] = Instant.now().minusSeconds(60)
                it[status] = "success"
                it[runDurationMs] = 12
                it[totalResponseMs] = 10
                // A ProbeResult as an agent sends it, storage location included.
                it[rawResult] = Json.parseToJsonElement(
                    """{"outcome":"success","calls":[{"request":{"url":"https://example.com/health","method":"GET"},""" +
                        """"response":{"status":200,"bodyPath":"/agent/bodies/$stepId.json"}}]}""",
                ).jsonObject
            }
            ProbeSteps.insert {
                it[id] = stepId
                it[probeResultId] = resultId
                it[stepNum] = 1
                it[requestUrl] = "https://example.com/health"
                it[statusCode] = 200
                it[responseTimeMs] = 10
                it[responseBodyStorageUrl] = "file://$bodyFile"
                it[createdAt] = Instant.now()
            }
        }

        val ownerSession = login(owner, address)
        val memberSession = login(member, address)
        return Fx(
            owner = owner, member = member, grantee = grantee,
            ownerSession = ownerSession, memberSession = memberSession,
            writeKey = mintKey(ownerSession, "write", address),
            readKey = mintKey(ownerSession, "read", address),
            memberKey = mintKey(memberSession, "write", address),
            workspace = ws, project = proj, service = svcId, serviceVersion = svc.version,
            orgVar = UUID.fromString(orgVar), wsVar = UUID.fromString(wsVar),
            projVar = UUID.fromString(projVar), svcVar = UUID.fromString(svcVar),
            result = resultId, step = stepId, stepBody = bodyText,
            silence = UUID.fromString(silence),
            webhook = UUID.fromString(webhook), spareWebhook = UUID.fromString(spare),
            binding = UUID.fromString(binding),
        )
    }

    /** A step of [fx]'s result whose body is the object [key] in the default store's bucket. */
    private fun objectStep(fx: Fx, key: String, content: ByteArray, contentType: String): UUID {
        TestS3.put(BUCKET, key, content, contentType)
        val stepId = UUID.randomUUID()
        transaction {
            ProbeSteps.insert {
                it[id] = stepId
                it[probeResultId] = fx.result
                it[stepNum] = 2
                it[requestUrl] = "https://example.com/object"
                it[statusCode] = 200
                it[responseBodyStorageUrl] = "s3://$BUCKET/$key"
                it[createdAt] = Instant.now()
            }
        }
        return stepId
    }

    // ── HTTP ──

    private fun send(address: String, method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val builder = Request.Builder().url("http://localhost:$serverPort$path")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> if (body == null) builder.delete() else builder.delete(body.toRequestBody(jsonType))
            else -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
        }
        builder.header("X-Forwarded-For", address)
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
    }

    private fun parse(raw: String): JsonElement? = if (raw.isBlank()) null else Json.parseToJsonElement(raw)

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.str(field: String): String = this[field]!!.jsonPrimitive.content

    private fun errorOf(raw: String): String? =
        (parse(raw) as? JsonObject)?.get("error")?.jsonPrimitive?.content

    // ── Cases ──

    /**
     * One public route: how to call it against a set of fixtures, which
     * dashboard route is its twin (null when it has none), and what a
     * successful call looks like.
     */
    private class Case(
        val method: String,
        /** The route as mounted, relative to [PublicApi.V1]. */
        val route: String,
        val path: (Fx) -> String,
        val twin: ((Fx) -> String)?,
        val body: ((Fx) -> String)? = null,
        val status: Int = 200,
        val shape: (JsonElement?) -> Unit,
        /**
         * The twin's answer as the public one gives it, where the public API
         * deliberately shows less (a directory entry, a webhook without its
         * address, a result without storage locations). Identity otherwise.
         */
        val twinView: (JsonElement?) -> JsonElement? = { it },
    )

    /** A page whose items keep only [keys], in the public route's order of fields. */
    private fun pageOf(vararg keys: String): (JsonElement?) -> JsonElement? = { element ->
        val o = element as JsonObject
        JsonObject(o + ("items" to JsonArray(o["items"]!!.jsonArray.map { item ->
            val i = item.jsonObject
            JsonObject(keys.associateWith { i[it] ?: JsonNull })
        })))
    }

    /** A page whose items have exactly [keys] — the public shape, nothing more. */
    private fun pageExactly(vararg keys: String): (JsonElement?) -> Unit = { element ->
        page(*keys)(element)
        element!!.jsonObject["items"]!!.jsonArray.forEach { assertEquals(keys.toSet(), it.jsonObject.keys, "Unexpected fields in $it") }
    }

    /** A result detail with every storage locator in `rawResult` set to null. */
    private val withoutLocators: (JsonElement?) -> JsonElement? = { element ->
        val o = element as JsonObject
        val raw = o["rawResult"]!!.jsonObject
        val calls = raw["calls"]?.jsonArray?.map { call ->
            val c = call.jsonObject
            val response = c["response"]?.jsonObject ?: return@map call
            JsonObject(c + ("response" to JsonObject(response.mapValues { (k, v) -> if (k.endsWith("Path") || k.endsWith("Uri")) JsonNull else v })))
        }
        if (calls == null) o else JsonObject(o + ("rawResult" to JsonObject(raw + ("calls" to JsonArray(calls)))))
    }

    private fun hasFields(vararg names: String): (JsonElement?) -> Unit = { element ->
        val o = element as JsonObject
        names.forEach { assertTrue(it in o, "Expected field '$it' in $o") }
    }

    private fun page(vararg itemFields: String): (JsonElement?) -> Unit = { element ->
        val o = element as JsonObject
        listOf("items", "total", "page", "pageSize").forEach { assertTrue(it in o, "Expected '$it' in $o") }
        val items = o["items"]!!.jsonArray
        assertTrue(items.isNotEmpty(), "Expected at least one item in $o")
        itemFields.forEach { f -> assertTrue(f in items.first().jsonObject, "Expected item field '$f' in $o") }
    }

    private fun array(vararg itemFields: String): (JsonElement?) -> Unit = { element ->
        val items = element as JsonArray
        if (itemFields.isNotEmpty()) {
            assertTrue(items.isNotEmpty(), "Expected at least one item")
            itemFields.forEach { f -> assertTrue(f in items.first().jsonObject, "Expected item field '$f' in $items") }
        }
    }

    private val ok: (JsonElement?) -> Unit = { element ->
        assertEquals(Json.parseToJsonElement("""{"ok":true}"""), element)
    }

    private val anything: (JsonElement?) -> Unit = { }

    private val v1 = "/api/v1"

    private val cases: List<Case> = listOf(
        Case("GET", "/key", { "/key" }, null, shape = hasFields("id", "name", "access", "organization", "user")),

        // Workspaces
        Case("GET", "/workspaces", { "/workspaces" }, { "$v1/workspaces" }, shape = page("id", "name")),
        Case("POST", "/workspaces", { "/workspaces" }, { "$v1/workspaces" }, { """{"name":"Made by key"}""" },
            shape = hasFields("id", "name", "createdAt")),
        Case("GET", "/workspaces/{id}", { "/workspaces/${it.workspace}" }, { "$v1/workspaces/${it.workspace}" },
            shape = hasFields("id", "name")),
        Case("PATCH", "/workspaces/{id}", { "/workspaces/${it.workspace}" }, { "$v1/workspaces/${it.workspace}" },
            { """{"name":"Renamed"}""" }, shape = hasFields("id", "name")),
        Case("DELETE", "/workspaces/{id}", { "/workspaces/${it.workspace}" }, { "$v1/workspaces/${it.workspace}" }, shape = ok),
        Case("PATCH", "/workspaces/{id}/services-toggle", { "/workspaces/${it.workspace}/services-toggle" },
            { "$v1/workspaces/${it.workspace}/services/toggle" }, { """{"isActive":false}""" }, shape = hasFields("matched", "changed")),

        // Projects
        Case("GET", "/projects", { "/projects?workspaceId=${it.workspace}" }, { "$v1/projects?workspaceId=${it.workspace}" },
            shape = page("id", "workspaceId", "name")),
        Case("POST", "/projects", { "/projects" }, { "$v1/projects" }, { """{"workspaceId":"${it.workspace}","name":"Made by key"}""" },
            shape = hasFields("id", "workspaceId", "name")),
        Case("GET", "/projects/{id}", { "/projects/${it.project}" }, { "$v1/projects/${it.project}" }, shape = hasFields("id", "name")),
        Case("PATCH", "/projects/{id}", { "/projects/${it.project}" }, { "$v1/projects/${it.project}" },
            { """{"name":"Renamed"}""" }, shape = hasFields("id", "name")),
        Case("DELETE", "/projects/{id}", { "/projects/${it.project}" }, { "$v1/projects/${it.project}" }, shape = ok),
        Case("PATCH", "/projects/{id}/services-toggle", { "/projects/${it.project}/services-toggle" },
            { "$v1/projects/${it.project}/services/toggle" }, { """{"isActive":false}""" }, shape = hasFields("matched", "changed")),

        // Services
        Case("GET", "/services", { "/services?projectId=${it.project}" }, { "$v1/services?projectId=${it.project}" },
            shape = page("id", "name", "script", "version")),
        Case("POST", "/services", { "/services" }, { "$v1/services" }, { """{"projectId":"${it.project}","name":"Made by key"}""" },
            shape = hasFields("id", "name", "version", "isActive")),
        Case("GET", "/services/{id}", { "/services/${it.service}" }, { "$v1/services/${it.service}" },
            shape = hasFields("id", "name", "script", "version")),
        Case("PATCH", "/services/{id}", { "/services/${it.service}" }, { "$v1/services/${it.service}" },
            { """{"name":"Renamed"}""" }, shape = hasFields("id", "name")),
        Case("DELETE", "/services/{id}", { "/services/${it.service}" }, { "$v1/services/${it.service}" }, shape = ok),
        Case("PATCH", "/services/{id}/script", { "/services/${it.service}/script" }, { "$v1/services/${it.service}/script" },
            { """{"script":"get(\"https://example.com/other\").expect(status: 200)","version":${it.serviceVersion}}""" },
            shape = { e ->
                hasFields("id", "script", "version")(e)
                // The fixture's create saved version 2; this save makes 3.
                assertEquals(3, e!!.jsonObject["version"]!!.jsonPrimitive.content.toInt())
            }),
        Case("GET", "/services/{id}/snapshot", { "/services/${it.service}/snapshot" }, { "$v1/services/${it.service}/snapshot" },
            shape = hasFields("service", "recentProbes")),
        Case("PATCH", "/services/{id}/toggle", { "/services/${it.service}/toggle" }, { "$v1/services/${it.service}/toggle" },
            { """{"isActive":false}""" }, shape = hasFields("id", "isActive")),
        Case("POST", "/services/{id}/run", { "/services/${it.service}/run" }, { "$v1/services/${it.service}/run" },
            status = 202, shape = { e -> assertEquals("true", e!!.jsonObject.str("ok")); Instant.parse(e.jsonObject.str("requestedAt")) }),
        Case("GET", "/services/{id}/agents", { "/services/${it.service}/agents" }, { "$v1/services/${it.service}/agents" },
            shape = array()),
        Case("PUT", "/services/{id}/agents", { "/services/${it.service}/agents" }, { "$v1/services/${it.service}/agents" },
            { """{"slugs":[]}""" }, shape = array()),

        // Agents
        Case("GET", "/agents", { "/agents" }, null, shape = array()),

        // Variables — organization
        Case("GET", "/variables", { "/variables" }, { "$v1/org/variables" }, shape = page("id", "key", "value", "type")),
        Case("POST", "/variables", { "/variables" }, { "$v1/org/variables" }, { """{"key":"MADE_BY_KEY","value":"v"}""" },
            shape = hasFields("id", "key", "value")),
        Case("PATCH", "/variables/{varId}", { "/variables/${it.orgVar}" }, { "$v1/org/variables/${it.orgVar}" },
            { """{"value":"changed"}""" }, shape = hasFields("id", "key", "value")),
        Case("DELETE", "/variables/{varId}", { "/variables/${it.orgVar}" }, { "$v1/org/variables/${it.orgVar}" }, shape = ok),

        // Variables — workspace
        Case("GET", "/workspaces/{id}/variables", { "/workspaces/${it.workspace}/variables" },
            { "$v1/workspaces/${it.workspace}/variables" }, shape = page("id", "key", "value")),
        Case("GET", "/workspaces/{id}/variables/hierarchy", { "/workspaces/${it.workspace}/variables/hierarchy" },
            { "$v1/workspaces/${it.workspace}/variables/hierarchy" }, shape = hasFields("scopes")),
        Case("POST", "/workspaces/{id}/variables", { "/workspaces/${it.workspace}/variables" },
            { "$v1/workspaces/${it.workspace}/variables" }, { """{"key":"MADE_BY_KEY","value":"v"}""" }, shape = hasFields("id", "key")),
        Case("PATCH", "/workspaces/{id}/variables/{varId}", { "/workspaces/${it.workspace}/variables/${it.wsVar}" },
            { "$v1/workspaces/${it.workspace}/variables/${it.wsVar}" }, { """{"value":"changed"}""" }, shape = hasFields("id", "key")),
        Case("DELETE", "/workspaces/{id}/variables/{varId}", { "/workspaces/${it.workspace}/variables/${it.wsVar}" },
            { "$v1/workspaces/${it.workspace}/variables/${it.wsVar}" }, shape = ok),

        // Variables — project
        Case("GET", "/projects/{id}/variables", { "/projects/${it.project}/variables" },
            { "$v1/projects/${it.project}/variables" }, shape = page("id", "key", "value")),
        Case("GET", "/projects/{id}/variables/hierarchy", { "/projects/${it.project}/variables/hierarchy" },
            { "$v1/projects/${it.project}/variables/hierarchy" }, shape = hasFields("scopes")),
        Case("POST", "/projects/{id}/variables", { "/projects/${it.project}/variables" },
            { "$v1/projects/${it.project}/variables" }, { """{"key":"MADE_BY_KEY","value":"v"}""" }, shape = hasFields("id", "key")),
        Case("PATCH", "/projects/{id}/variables/{varId}", { "/projects/${it.project}/variables/${it.projVar}" },
            { "$v1/projects/${it.project}/variables/${it.projVar}" }, { """{"value":"changed"}""" }, shape = hasFields("id", "key")),
        Case("DELETE", "/projects/{id}/variables/{varId}", { "/projects/${it.project}/variables/${it.projVar}" },
            { "$v1/projects/${it.project}/variables/${it.projVar}" }, shape = ok),

        // Variables — service
        Case("GET", "/services/{id}/variables", { "/services/${it.service}/variables" },
            { "$v1/services/${it.service}/variables" },
            shape = { e ->
                page("id", "key", "value")(e)
                // The fixture's service variable is a secret: listed, never shown.
                val secret = e!!.jsonObject["items"]!!.jsonArray.map { it.jsonObject }.single { it.str("key") == "SVC_VAR" }
                assertFalse(secret.str("value").contains("svc-value"), "A secret's value was listed: $secret")
            }),
        Case("GET", "/services/{id}/variables/hierarchy", { "/services/${it.service}/variables/hierarchy" },
            { "$v1/services/${it.service}/variables/hierarchy" }, shape = hasFields("scopes")),
        Case("POST", "/services/{id}/variables", { "/services/${it.service}/variables" },
            { "$v1/services/${it.service}/variables" }, { """{"key":"MADE_BY_KEY","value":"v"}""" }, shape = hasFields("id", "key")),
        Case("PATCH", "/services/{id}/variables/{varId}", { "/services/${it.service}/variables/${it.svcVar}" },
            { "$v1/services/${it.service}/variables/${it.svcVar}" }, { """{"value":"changed"}""" }, shape = hasFields("id", "key")),
        Case("DELETE", "/services/{id}/variables/{varId}", { "/services/${it.service}/variables/${it.svcVar}" },
            { "$v1/services/${it.service}/variables/${it.svcVar}" }, shape = ok),

        // Results
        Case("GET", "/services/{id}/results", { "/services/${it.service}/results" }, { "$v1/services/${it.service}/results" },
            shape = page("id", "status", "startedAt")),
        Case("GET", "/services/{id}/results/{resultId}", { "/services/${it.service}/results/${it.result}" },
            { "$v1/services/${it.service}/results/${it.result}" },
            shape = { e ->
                hasFields("id", "status", "steps", "agentSlug")(e)
                assertEquals(1, e!!.jsonObject["steps"]!!.jsonArray.size)
                val response = e.jsonObject["rawResult"]!!.jsonObject["calls"]!!.jsonArray.single().jsonObject["response"]!!.jsonObject
                assertTrue("bodyPath" in response && response["bodyPath"] is JsonNull, "A storage location reached the public detail: $e")
            },
            twinView = withoutLocators),
        Case("GET", "/services/{id}/results/{resultId}/steps/{stepId}/body",
            { "/services/${it.service}/results/${it.result}/steps/${it.step}/body" },
            { "$v1/services/${it.service}/results/${it.result}/steps/${it.step}/body" },
            shape = { e ->
                val o = e as JsonObject
                assertFalse("url" in o, "A public step body must never carry a url: $o")
                assertEquals(setOf("content", "contentType", "encoding"), o.keys)
            }),

        // Metrics — service
        Case("GET", "/services/{id}/metrics", { "/services/${it.service}/metrics" }, { "$v1/services/${it.service}/metrics" },
            shape = hasFields("counters", "state")),
        Case("GET", "/services/{id}/metrics/history", { "/services/${it.service}/metrics/history?hours=48" },
            { "$v1/services/${it.service}/metrics/history?hours=48" }, shape = array()),
        Case("GET", "/services/{id}/metrics/statistics", { "/services/${it.service}/metrics/statistics?window=7d" },
            { "$v1/services/${it.service}/metrics/statistics?window=7d" }, shape = hasFields("window", "bucketType", "overall", "regions")),
        Case("GET", "/services/{id}/metrics/statistics/endpoint-series",
            { "/services/${it.service}/metrics/statistics/endpoint-series" },
            { "$v1/services/${it.service}/metrics/statistics/endpoint-series" }, shape = hasFields("window", "bucketType", "buckets", "all", "endpoints")),
        Case("GET", "/services/{id}/metrics/statistics/assertions",
            { "/services/${it.service}/metrics/statistics/assertions?window=30d" },
            { "$v1/services/${it.service}/metrics/statistics/assertions?window=30d" },
            shape = hasFields("window", "since", "until", "truncated", "assertions")),
        Case("GET", "/services/{id}/metrics/statistics/failure-heatmap",
            { "/services/${it.service}/metrics/statistics/failure-heatmap?days=30" },
            { "$v1/services/${it.service}/metrics/statistics/failure-heatmap?days=30" },
            shape = hasFields("days", "timezone", "since", "until", "totalRuns", "totalFailedRuns", "cells")),

        // Metrics — project and workspace
        Case("GET", "/projects/{id}/metrics", { "/projects/${it.project}/metrics" }, { "$v1/projects/${it.project}/metrics" },
            shape = hasFields("counters", "state")),
        Case("GET", "/projects/{id}/metrics/history", { "/projects/${it.project}/metrics/history" },
            { "$v1/projects/${it.project}/metrics/history" }, shape = array()),
        Case("GET", "/workspaces/{id}/metrics", { "/workspaces/${it.workspace}/metrics" },
            { "$v1/workspaces/${it.workspace}/metrics" }, shape = hasFields("counters", "state")),
        Case("GET", "/workspaces/{id}/metrics/history", { "/workspaces/${it.workspace}/metrics/history?hours=12" },
            { "$v1/workspaces/${it.workspace}/metrics/history?hours=12" }, shape = array()),

        // Silences
        Case("GET", "/silences", { "/silences" }, { "$v1/silences" }, shape = page("id", "channel")),
        Case("POST", "/silences", { "/silences" }, { "$v1/silences" }, { """{"channel":"all"}""" }, shape = hasFields("id", "channel")),
        Case("GET", "/silences/{id}", { "/silences/${it.silence}" }, { "$v1/silences/${it.silence}" }, shape = hasFields("id", "channel")),
        Case("PATCH", "/silences/{id}", { "/silences/${it.silence}" }, { "$v1/silences/${it.silence}" },
            { """{"channel":"all"}""" }, shape = hasFields("id", "channel")),
        Case("DELETE", "/silences/{id}", { "/silences/${it.silence}" }, { "$v1/silences/${it.silence}" }, shape = ok),

        // Access
        Case("GET", "/access/{resourceType}/{resourceId}", { "/access/service/${it.service}" }, { "$v1/access/service/${it.service}" },
            shape = array("principalType", "principalId", "permissions")),
        Case("PUT", "/access/{resourceType}/{resourceId}", { "/access/service/${it.service}" }, { "$v1/access/service/${it.service}" },
            { """{"principalType":"user","principalId":"${it.member.userId}","permissions":1}""" }, shape = ok),
        Case("DELETE", "/access/{resourceType}/{resourceId}/{principalType}/{principalId}",
            { "/access/service/${it.service}/user/${it.grantee.userId}" },
            { "$v1/access/service/${it.service}/user/${it.grantee.userId}" }, shape = ok),

        // Directory
        Case("GET", "/members", { "/members" }, { "$v1/users" },
            shape = pageExactly("userId", "displayName", "email", "isActive"), twinView = pageOf("userId", "displayName", "email", "isActive")),
        Case("GET", "/groups", { "/groups" }, { "$v1/groups" },
            shape = pageExactly("id", "name", "memberCount"), twinView = pageOf("id", "name", "memberCount")),

        // Webhooks
        Case("GET", "/webhooks", { "/webhooks" }, { "$v1/webhooks" },
            shape = pageExactly("id", "name", "label", "method", "createdAt"), twinView = pageOf("id", "name", "label", "method", "createdAt")),
        Case("GET", "/webhooks/bindings", { "/webhooks/bindings?resourceType=service&resourceId=${it.service}" },
            { "$v1/webhooks/bindings/service/${it.service}" }, shape = page("id", "webhookId", "enabled")),
        Case("POST", "/webhooks/bindings", { "/webhooks/bindings?resourceType=service&resourceId=${it.service}" },
            { "$v1/webhooks/bindings/service/${it.service}" }, { """{"webhookId":"${it.spareWebhook}"}""" },
            shape = hasFields("id", "webhookId", "enabled")),
        Case("PATCH", "/webhooks/bindings/{id}", { "/webhooks/bindings/${it.binding}" }, { "$v1/webhooks/bindings/${it.binding}" },
            { """{"enabled":false}""" }, shape = { e -> hasFields("id", "enabled")(e); assertEquals("false", e!!.jsonObject.str("enabled")) }),
        Case("DELETE", "/webhooks/bindings/{id}", { "/webhooks/bindings/${it.binding}" }, { "$v1/webhooks/bindings/${it.binding}" },
            shape = ok),
    )

    /**
     * Puts one route through the four questions. Each runs from an address of
     * its own, against an organization of its own.
     */
    private fun exercise(case: Case) {
        val address = nextAddress()
        val fx = fixtures(address)
        val publicPath = PublicApi.V1 + case.path(fx)
        val body = case.body?.invoke(fx)
        val isRead = case.method == "GET"

        // Every error status any of the calls below answers must be one the
        // description declares for the operation.
        val operation = PublicApiOperations.find(HttpMethod.parse(case.method), case.route)!!
        val declared = operation.errorStatuses.map { it.value }.toSet()
        fun declaredOrSuccess(status: Int, raw: String) {
            if (status >= 400) assertTrue(status in declared, "${case.method} ${case.route} answered $status, not declared ($declared): $raw")
        }

        // A read-only key: refused anything but a read, before the handler.
        val (readStatus, readRaw) = send(address, case.method, publicPath, fx.readKey, body)
        declaredOrSuccess(readStatus, readRaw)
        if (isRead) {
            assertEquals(case.status, readStatus, "Read-only key on ${case.method} ${case.route}: $readRaw")
        } else {
            assertEquals(403, readStatus, "Read-only key on ${case.method} ${case.route}: $readRaw")
            assertEquals("api_key_read_only", errorOf(readRaw), readRaw)
        }

        val twin = case.twin
        if (twin != null) {
            // A user who may not: the key gets what their session gets.
            val (keyStatus, keyRaw) = send(address, case.method, publicPath, fx.memberKey, body)
            declaredOrSuccess(keyStatus, keyRaw)
            val (sessionStatus, sessionRaw) = send(address, case.method, twin(fx), fx.memberSession, body)
            assertEquals(sessionStatus, keyStatus, "Member on ${case.method} ${case.route}: key $keyRaw, session $sessionRaw")
            if (keyStatus >= 400) {
                assertEquals(errorOf(sessionRaw), errorOf(keyRaw), "Member on ${case.method} ${case.route}")
            } else if (isRead) {
                assertEquals(
                    comparable(case.twinView(parse(sessionRaw))), comparable(parse(keyRaw)),
                    "Member on ${case.method} ${case.route}",
                )
            }
        }

        // A user who may: the call succeeds, and a read answers as the twin does.
        val (status, raw) = send(address, case.method, publicPath, fx.writeKey, body)
        assertEquals(case.status, status, "Write key on ${case.method} ${case.route}: $raw")
        assertEquals(operation.status.value, case.status, "The status ${case.route} declares")
        if (isRead && twin != null) {
            // Asked twice at most: a statistics read is aligned to the hour,
            // and two calls a millisecond apart can still fall either side of
            // one. Twice in a row they cannot.
            var publicAnswer = raw
            var twinAnswer = send(address, "GET", twin(fx), fx.ownerSession)
            for (attempt in 1..2) {
                assertEquals(twinAnswer.first, status, "Owner session on the twin of ${case.route}: ${twinAnswer.second}")
                if (comparable(case.twinView(parse(twinAnswer.second))) == comparable(parse(publicAnswer)) || attempt == 2) break
                publicAnswer = send(address, "GET", publicPath, fx.writeKey).second
                twinAnswer = send(address, "GET", twin(fx), fx.ownerSession)
            }
            assertEquals(
                comparable(case.twinView(parse(twinAnswer.second))), comparable(parse(publicAnswer)),
                "Public and dashboard answers differ for ${case.route}",
            )
        }
        case.shape(parse(raw))
    }

    /**
     * An answer as it can be compared with another. What is not a difference:
     * the dashboard's step body has a `url` field the public one does not —
     * always null for a body the dashboard is handed inline — and a statistics
     * read says when it was made (`until`, the moment of the call) and where
     * its window starts (`since`, aligned to the call's hour), which two calls
     * need not share.
     */
    private fun comparable(element: JsonElement?): JsonElement? {
        val o = element as? JsonObject ?: return element
        if (o.keys == setOf("content", "url", "contentType", "encoding")) {
            assertEquals(JsonNull, o["url"], "The dashboard was handed a link where a comparison expects content")
            return JsonObject(o - "url")
        }
        return JsonObject(o - "until" - "since")
    }

    @TestFactory
    fun `every public route answers as its user would be answered`(): List<DynamicTest> =
        cases.map { case -> DynamicTest.dynamicTest("${case.method} ${case.route}") { exercise(case) } }

    @Test
    fun `every public route has a case`() {
        val root = server.application.pluginOrNull(RoutingRoot)!!
        val mounted = PublicApiContractTest.publicRoutes(root)
        val covered = cases.map { "${it.method} ${PublicApi.V1}${it.route}" }.sorted()
        assertEquals(mounted, covered)
    }

    // ── Step bodies are text in the response ──

    @Test
    fun `a body kept as an object is returned as content, where the dashboard gets a link`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val text = "{\"object\":true}"
        val stepId = objectStep(fx, "${fx.owner.orgId}/${UUID.randomUUID()}.json", text.toByteArray(), "application/json")
        val tail = "/services/${fx.service}/results/${fx.result}/steps/$stepId/body"

        val (dashStatus, dashRaw) = send(address, "GET", "/api/v1$tail", fx.ownerSession)
        assertEquals(200, dashStatus, dashRaw)
        assertTrue(obj(dashRaw)["url"] is kotlinx.serialization.json.JsonPrimitive, "The dashboard's answer: $dashRaw")

        val (status, raw) = send(address, "GET", "${PublicApi.V1}$tail", fx.readKey)
        assertEquals(200, status, raw)
        val body = obj(raw)
        assertEquals(text, body.str("content"))
        assertEquals("application/json", body.str("contentType"))
        assertTrue(body["encoding"] is JsonNull, raw)
        assertFalse("url" in body, raw)
        assertFalse(TestS3.endpoint in raw, "The response must not name the store: $raw")
    }

    @Test
    fun `a body that is not text is returned as base64`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff.toByte(), 0x00)
        val stepId = objectStep(fx, "${fx.owner.orgId}/${UUID.randomUUID()}.png", bytes, "image/png")

        val (status, raw) = send(
            address, "GET", "${PublicApi.V1}/services/${fx.service}/results/${fx.result}/steps/$stepId/body", fx.readKey,
        )
        assertEquals(200, status, raw)
        val body = obj(raw)
        assertEquals("base64", body.str("encoding"))
        assertEquals("image/png", body.str("contentType"))
        assertTrue(bytes.contentEquals(java.util.Base64.getDecoder().decode(body.str("content"))))
    }

    @Test
    fun `a file body is returned as its text, and a step with no body as 204`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val (status, raw) = send(
            address, "GET", "${PublicApi.V1}/services/${fx.service}/results/${fx.result}/steps/${fx.step}/body", fx.readKey,
        )
        assertEquals(200, status, raw)
        assertEquals(fx.stepBody, obj(raw).str("content"))

        val bare = UUID.randomUUID()
        transaction {
            ProbeSteps.insert {
                it[id] = bare
                it[probeResultId] = fx.result
                it[stepNum] = 3
                it[requestUrl] = "https://example.com/none"
                it[createdAt] = Instant.now()
            }
        }
        val (noneStatus, _) = send(
            address, "GET", "${PublicApi.V1}/services/${fx.service}/results/${fx.result}/steps/$bare/body", fx.readKey,
        )
        assertEquals(204, noneStatus)
    }

    /** A step of [fx]'s result whose recorded body location is [uri], in [store] when given. */
    private fun stepAt(fx: Fx, uri: String?, store: UUID? = null): UUID {
        val id = UUID.randomUUID()
        transaction {
            ProbeSteps.insert {
                it[ProbeSteps.id] = id
                it[probeResultId] = fx.result
                it[stepNum] = 9
                it[requestUrl] = "https://example.com/step"
                it[responseBodyStorageUrl] = uri
                it[bodyStoreId] = store
                it[createdAt] = Instant.now()
            }
        }
        return id
    }

    private fun bodyPath(fx: Fx, step: UUID) = "${PublicApi.V1}/services/${fx.service}/results/${fx.result}/steps/$step/body"

    @Test
    fun `a recorded body that is gone is 410 body_gone, and a location that cannot be read is not read`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val secretFile = Files.createTempFile("outside-store", ".txt")
        try {
            Files.writeString(secretFile, "not yours")
            // Outside the default store's root: refused, never opened.
            val (outsideStatus, outsideRaw) = send(address, "GET", bodyPath(fx, stepAt(fx, "file://$secretFile")), fx.readKey)
            assertEquals(410, outsideStatus, outsideRaw)
            assertEquals("body_gone", errorOf(outsideRaw))
            assertFalse("not yours" in outsideRaw)

            // Recorded, in the default store, and not there — as an object and as a file.
            for (uri in listOf(
                "s3://$BUCKET/${fx.owner.orgId}/never-written.json",
                "file://${storageRoot.resolve("${fx.owner.orgId}/never-written.json")}",
            )) {
                val (status, raw) = send(address, "GET", bodyPath(fx, stepAt(fx, uri)), fx.readKey)
                assertEquals(410, status, "$uri: $raw")
                assertEquals("body_gone", errorOf(raw), uri)
            }

            // A location no store can read: a scheme there is none for, a malformed
            // object URI. Permanent, so not worth retrying — not a 503.
            for (uri in listOf("ftp://somewhere/else.json", "s3://no-key-here")) {
                val (status, raw) = send(address, "GET", bodyPath(fx, stepAt(fx, uri)), fx.readKey)
                assertEquals(410, status, "$uri: $raw")
                assertEquals("body_gone", errorOf(raw), uri)
            }

            // No body recorded at all: nothing to say, 204.
            assertEquals(204, send(address, "GET", bodyPath(fx, stepAt(fx, null)), fx.readKey).first)
        } finally {
            Files.deleteIfExists(secretFile)
        }
    }

    @Test
    fun `a body over the public cap is 413 with the limit, and control bytes come back base64`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val dir = Files.createDirectories(storageRoot.resolve(fx.owner.orgId.toString()))

        val big = dir.resolve("big-${UUID.randomUUID()}.bin")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(ProbeResultController.PUBLIC_BODY_INLINE_MAX + 1) }
        val (bigStatus, bigRaw) = send(address, "GET", bodyPath(fx, stepAt(fx, "file://$big")), fx.readKey)
        assertEquals(413, bigStatus, bigRaw)
        assertEquals("body_too_large", errorOf(bigRaw))
        assertEquals(ProbeResultController.PUBLIC_BODY_INLINE_MAX, obj(bigRaw)["details"]!!.jsonObject.str("maxBytes").toLong())

        // Exactly at the cap is served.
        val atCap = dir.resolve("cap-${UUID.randomUUID()}.txt")
        Files.write(atCap, ByteArray(ProbeResultController.PUBLIC_BODY_INLINE_MAX.toInt()) { 'a'.code.toByte() })
        assertEquals(200, send(address, "GET", bodyPath(fx, stepAt(fx, "file://$atCap")), fx.readKey).first)

        // Valid UTF-8, but every byte would be a six-byte escape in JSON.
        val control = dir.resolve("control-${UUID.randomUUID()}.bin")
        val controlBytes = ByteArray(1024) { 0x01 }
        Files.write(control, controlBytes)
        val (status, raw) = send(address, "GET", bodyPath(fx, stepAt(fx, "file://$control")), fx.readKey)
        assertEquals(200, status, raw.take(200))
        assertEquals("base64", obj(raw).str("encoding"))
        assertTrue(controlBytes.contentEquals(java.util.Base64.getDecoder().decode(obj(raw).str("content"))))

        // Tabs and newlines are text.
        val text = dir.resolve("text-${UUID.randomUUID()}.txt")
        Files.writeString(text, "a\tb\r\nc\n")
        val textBody = obj(send(address, "GET", bodyPath(fx, stepAt(fx, "file://$text")), fx.readKey).second)
        assertTrue(textBody["encoding"] is JsonNull, textBody.toString())
        assertEquals("a\tb\r\nc\n", textBody.str("content"))
    }

    /** A body store of [fx]'s organization. */
    private fun bodyStore(fx: Fx, input: BodyStoreInput): UUID = UUID.fromString(BodyStoreService.create(fx.owner.orgId, input).id)

    @Test
    fun `a body store that does not answer is 503, and one over the cap is 413`() {
        val address = nextAddress()
        val fx = fixtures(address)

        val unreachable = bodyStore(fx, BodyStoreInput(
            name = "unreachable-${UUID.randomUUID().toString().take(6)}", kind = "s3", mode = "in_place",
            // A bucket of its own: one over the default store's would be refused.
            endpoint = "http://127.0.0.1:1", bucket = "elsewhere", prefix = "unreachable-${UUID.randomUUID().toString().take(6)}",
            accessKeyId = TestS3.USER, secretAccessKey = TestS3.PASSWORD,
        ))
        val unreachablePrefix = transaction { BodyStores.selectAll().where { BodyStores.id eq unreachable }.single()[BodyStores.prefix] }
        val (status, raw) = send(address, "GET", bodyPath(fx, stepAt(fx, "s3://elsewhere/$unreachablePrefix/call_0.json", unreachable)), fx.readKey)
        assertEquals(503, status, raw)
        assertEquals("body_store_unavailable", errorOf(raw))
        assertNotNull(transaction {
            BodyStores.selectAll().where { BodyStores.id eq unreachable }.single()[BodyStores.lastFailureCode]
        }, "The store says it stopped answering")

        val root = Files.createDirectories(storeBase.resolve("fs-${UUID.randomUUID().toString().take(8)}"))
        val fsStore = bodyStore(fx, BodyStoreInput(
            name = "fs-${UUID.randomUUID().toString().take(6)}", kind = "filesystem", mode = "in_place", rootPath = root.toString(),
        ))
        val big = root.resolve("big.bin")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(ProbeResultController.PUBLIC_BODY_INLINE_MAX + 1) }
        val (bigStatus, bigRaw) = send(address, "GET", bodyPath(fx, stepAt(fx, "file://$big", fsStore)), fx.readKey)
        assertEquals(413, bigStatus, bigRaw)
        assertEquals("body_too_large", errorOf(bigRaw))

        val small = root.resolve("small.json")
        Files.writeString(small, """{"in":"place"}""")
        val (okStatus, okRaw) = send(address, "GET", bodyPath(fx, stepAt(fx, "file://$small", fsStore)), fx.readKey)
        assertEquals(200, okStatus, okRaw)
        assertEquals("""{"in":"place"}""", obj(okRaw).str("content"))
    }

    @Test
    fun `a read that is cancelled while it waits records nothing against the store`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val root = Files.createDirectories(storeBase.resolve("held-${UUID.randomUUID().toString().take(8)}"))
        val store = bodyStore(fx, BodyStoreInput(
            name = "held-${UUID.randomUUID().toString().take(6)}", kind = "filesystem", mode = "in_place", rootPath = root.toString(),
        ))
        Files.writeString(root.resolve("body.json"), "{}")
        val step = stepAt(fx, "file://${root.resolve("body.json")}", store)
        val o = fx.owner

        runBlocking {
            // Every place the store has in the gate, held by reads that have not
            // finished answering.
            val release = CompletableDeferred<Unit>()
            val holders = (1..2).map {
                val inside = CompletableDeferred<Unit>()
                launch(Dispatchers.IO) {
                    ProbeResultController.readStepBody(o.orgId, fx.service, fx.result, step, o.userId) {
                        inside.complete(Unit)
                        release.await()
                    }
                } to inside
            }
            holders.forEach { it.second.await() }

            // A third waits for a place, and its caller goes away.
            val waiter = launch(Dispatchers.IO) {
                ProbeResultController.readStepBody(o.orgId, fx.service, fx.result, step, o.userId) { }
            }
            delay(300)
            assertTrue(waiter.isActive, "The third read should be waiting for the store's place in the gate")
            waiter.cancelAndJoin()
            release.complete(Unit)
            holders.forEach { it.first.join() }
        }

        assertNull(
            transaction { BodyStores.selectAll().where { BodyStores.id eq store }.single()[BodyStores.lastFailureCode] },
            "A cancelled wait is not the store failing",
        )
    }

    // ── Paging ──

    @Test
    fun `public lists page with page and pageSize only, up to 100, and say which parameter is wrong`() {
        val address = nextAddress()
        val fx = fixtures(address)

        val (okStatus, okRaw) = send(address, "GET", "${PublicApi.V1}/workspaces?page=1&pageSize=100", fx.readKey)
        assertEquals(200, okStatus, okRaw)
        assertEquals(100, obj(okRaw).str("pageSize").toInt())

        for ((query, field) in listOf(
            "pageSize=101" to "pageSize", "pageSize=0" to "pageSize", "page=0" to "page", "page=x" to "page",
            "pageSize=1000" to "pageSize", "page=${Int.MAX_VALUE}&pageSize=100" to "page",
        )) {
            val (status, raw) = send(address, "GET", "${PublicApi.V1}/workspaces?$query", fx.readKey)
            assertEquals(400, status, "$query: $raw")
            assertEquals("field_invalid", errorOf(raw), query)
            assertEquals(field, obj(raw)["details"]!!.jsonObject.str("field"), query)
        }
        assertEquals("100", obj(send(address, "GET", "${PublicApi.V1}/workspaces?pageSize=101", fx.readKey).second)["details"]!!.jsonObject.str("max"))
        // The furthest page that fits is answered, empty.
        val far = send(address, "GET", "${PublicApi.V1}/workspaces?page=${Int.MAX_VALUE / 100}&pageSize=100", fx.readKey)
        assertEquals(200, far.first, far.second)
        assertTrue(obj(far.second)["items"]!!.jsonArray.isEmpty())
        // The dashboard's parser takes what the public one refuses.
        assertEquals(200, send(address, "GET", "$v1/workspaces?pageSize=1000", fx.ownerSession).first)
    }

    @Test
    fun `a missing or malformed query parameter is named`() {
        val address = nextAddress()
        val fx = fixtures(address)
        for ((path, field, code) in listOf(
            Triple("/projects", "workspaceId", "field_required"),
            Triple("/projects?workspaceId=nope", "workspaceId", "invalid_uuid"),
            Triple("/services", "projectId", "field_required"),
            Triple("/webhooks/bindings?resourceId=${fx.service}", "resourceType", "field_required"),
            Triple("/webhooks/bindings?resourceType=service", "resourceId", "field_required"),
            Triple("/services/${fx.service}/metrics/history?hours=x", "hours", "field_invalid"),
            Triple("/services/${fx.service}/metrics/history?hours=500", "hours", "field_invalid"),
            Triple("/services/${fx.service}/metrics/statistics?window=1y", "window", "field_invalid"),
            Triple("/services/${fx.service}/results?since=yesterday", "since", "field_invalid"),
        )) {
            val (status, raw) = send(address, "GET", "${PublicApi.V1}$path", fx.readKey)
            assertEquals(400, status, "$path: $raw")
            assertEquals(code, errorOf(raw), path)
            assertEquals(field, obj(raw)["details"]!!.jsonObject.str("field"), path)
        }
    }

    @Test
    fun `a list without an order comes back in the same order every time`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val made = (1..4).map { n ->
            obj(send(address, "POST", "${PublicApi.V1}/workspaces", fx.writeKey, """{"name":"Order $n"}""").second)
        }
        fun pageThrough() = (1..5).map { page ->
            obj(send(address, "GET", "${PublicApi.V1}/workspaces?page=$page&pageSize=1", fx.readKey).second)["items"]!!
                .jsonArray.single().jsonObject.str("id")
        }
        val first = pageThrough()
        // Each workspace once, oldest first, and ties (one creation instant)
        // by id — compared as text, which is how the database orders a UUID.
        val all = made + obj(send(address, "GET", "${PublicApi.V1}/workspaces/${fx.workspace}", fx.readKey).second)
        val expected = all.sortedWith(compareBy({ Instant.parse(it.str("createdAt")) }, { it.str("id") })).map { it.str("id") }
        assertEquals(expected, first)
        assertEquals(first, pageThrough(), "Asked again, the same order")
    }

    @Test
    fun `filters and sorters are refused rather than ignored`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val filters = java.net.URLEncoder.encode(
            """[{"table":"workspaces","column":"name","operator":"eq","value":"nothing"}]""", Charsets.UTF_8,
        )
        val sorters = java.net.URLEncoder.encode("""[{"table":"workspaces","column":"name","order":"asc"}]""", Charsets.UTF_8)
        for ((query, field) in listOf("filters=$filters" to "filters", "sorters=$sorters" to "sorters", "filters=" to "filters", "sorters=%5B%5D" to "sorters")) {
            for (path in listOf("/workspaces", "/services/${fx.service}/results", "/members")) {
                val (status, raw) = send(address, "GET", "${PublicApi.V1}$path?$query", fx.readKey)
                assertEquals(400, status, "$path?$query: $raw")
                assertEquals("field_invalid", errorOf(raw), "$path?$query")
                assertEquals(field, obj(raw)["details"]!!.jsonObject.str("field"), "$path?$query")
            }
        }
    }

    // ── Behaviour of its own ──

    @Test
    fun `a run is refused for a service that cannot run, and its result can be waited for`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val o = fx.owner
        val bare = ServiceController.create(o.orgId, fx.project, CreateServiceRequest(projectId = fx.project.toString(), name = "Bare"), o.userId)
        val (missing, missingRaw) = send(address, "POST", "${PublicApi.V1}/services/${bare.id}/run", fx.writeKey)
        assertEquals(409, missing, missingRaw)
        assertEquals("script_missing", errorOf(missingRaw))

        send(address, "PATCH", "${PublicApi.V1}/services/${fx.service}/toggle", fx.writeKey, """{"isActive":false}""")
        val (inactive, inactiveRaw) = send(address, "POST", "${PublicApi.V1}/services/${fx.service}/run", fx.writeKey)
        assertEquals(409, inactive, inactiveRaw)
        assertEquals("service_inactive", errorOf(inactiveRaw))

        // A user who may not run it learns nothing about whether it could run.
        val (memberStatus, memberRaw) = send(address, "POST", "${PublicApi.V1}/services/${bare.id}/run", fx.memberKey)
        assertTrue(memberStatus == 403 || memberStatus == 404, memberRaw)

        // `since` keeps the runs started at or after it.
        val after = Instant.now().plusSeconds(3600)
        val (status, raw) = send(address, "GET", "${PublicApi.V1}/services/${fx.service}/results?since=$after", fx.readKey)
        assertEquals(200, status, raw)
        assertEquals(0, obj(raw).str("total").toInt())
        val before = Instant.now().minusSeconds(3600)
        assertEquals(1, obj(send(address, "GET", "${PublicApi.V1}/services/${fx.service}/results?since=$before", fx.readKey).second).str("total").toInt())
    }

    @Test
    fun `a create keeps its script and state, and a refused script leaves no service behind`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val (status, raw) = send(
            address, "POST", "${PublicApi.V1}/services", fx.writeKey,
            """{"projectId":"${fx.project}","name":"Off","script":"get(\"https://example.com\").expect(status: 200)","isActive":false}""",
        )
        assertEquals(200, status, raw)
        assertEquals("false", obj(raw).str("isActive"))
        assertTrue("example.com" in obj(raw).str("script"))

        val (badStatus, badRaw) = send(
            address, "POST", "${PublicApi.V1}/services", fx.writeKey,
            """{"projectId":"${fx.project}","name":"Broken","script":"this is not lace"}""",
        )
        assertEquals(400, badStatus, badRaw)
        val (onWithout, onRaw) = send(address, "POST", "${PublicApi.V1}/services", fx.writeKey, """{"projectId":"${fx.project}","name":"On","isActive":true}""")
        assertEquals(400, onWithout, onRaw)
        assertEquals("field_required", errorOf(onRaw))
        val names = obj(send(address, "GET", "${PublicApi.V1}/services?projectId=${fx.project}", fx.readKey).second)["items"]!!
            .jsonArray.map { it.jsonObject.str("name") }
        assertFalse("Broken" in names || "On" in names, "A refused create left a service: $names")
    }

    @Test
    fun `masked values say so, at every scope`() {
        val address = nextAddress()
        val fx = fixtures(address)
        send(address, "POST", "${PublicApi.V1}/variables", fx.writeKey, """{"key":"ORG_SECRET","value":"s","type":"secret"}""")
        for (path in listOf("/variables", "/workspaces/${fx.workspace}/variables", "/projects/${fx.project}/variables", "/services/${fx.service}/variables")) {
            val items = obj(send(address, "GET", "${PublicApi.V1}$path", fx.readKey).second)["items"]!!.jsonArray.map { it.jsonObject }
            for (item in items) {
                val masked = item.str("masked").toBoolean()
                // The flag and the mask agree, and a secret is always masked.
                assertEquals(item.str("value") == VariableCrypto.MASK, masked, "$path: $item")
                if (item.str("type") == "secret") assertTrue(masked, "$path: $item")
            }
        }
    }

    @Test
    fun `the namespace answers unknown paths, wrong methods, HEAD and odd spellings in its own shape`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val (missing, missingRaw) = send(address, "GET", "${PublicApi.V1}/no-such-thing", fx.readKey)
        assertEquals(404, missing, missingRaw)
        assertEquals("not_found", errorOf(missingRaw))
        val (method, methodRaw) = send(address, "PUT", "${PublicApi.V1}/workspaces", fx.writeKey, "{}")
        assertEquals(405, method, methodRaw)
        assertEquals("method_not_allowed", errorOf(methodRaw))

        val head = Request.Builder().url("http://localhost:$serverPort${PublicApi.V1}/workspaces").head()
            .header("X-Forwarded-For", address).header("Authorization", "Bearer ${fx.readKey}").build()
        client.newCall(head).execute().use {
            assertEquals(200, it.code)
            assertEquals(0, it.body.bytes().size)
        }

        // Paths that do not reduce to one canonical form, sent as written.
        for (path in listOf("${PublicApi.V1}/%2e/workspaces", "/api/public/../public/v1/workspaces", "/api/x/%2e%2e/public/v1/key")) {
            val (status, raw) = rawGet(path, fx.readKey, address)
            assertEquals(400, status, "$path: $raw")
            assertTrue("invalid_path" in raw, "$path: $raw")
        }
    }

    /** Sends a GET with [path] exactly as given — no client normalizing it first. */
    private fun rawGet(path: String, token: String, address: String): Pair<Int, String> {
        java.net.Socket("localhost", serverPort).use { socket ->
            socket.soTimeout = 10_000
            val out = socket.getOutputStream()
            out.write(
                ("GET $path HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\n" +
                    "X-Forwarded-For: $address\r\nConnection: close\r\n\r\n").toByteArray(),
            )
            out.flush()
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            return text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
        }
    }

    @Test
    fun `agents are listed by slug, and an unknown slug is named`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val (status, raw) = send(address, "GET", "${PublicApi.V1}/agents", fx.readKey)
        assertEquals(200, status, raw)
        Json.parseToJsonElement(raw).jsonArray.forEach { assertEquals(setOf("slug", "label"), it.jsonObject.keys) }

        val (refused, refusedRaw) = send(address, "PUT", "${PublicApi.V1}/services/${fx.service}/agents", fx.writeKey, """{"slugs":["no-such-agent"]}""")
        assertEquals(400, refused, refusedRaw)
        val details = obj(refusedRaw)["details"]!!.jsonObject
        assertEquals("slugs", details.str("field"))
        assertEquals(listOf("no-such-agent"), details["unknown"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `project and workspace metrics are 204 while there is nothing, where the dashboard shows zeros`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val o = fx.owner
        val ws = WorkspaceController.create(o.orgId, CreateWorkspaceRequest("Empty"), o.userId).id
        val proj = ProjectController.create(o.orgId, UUID.fromString(ws), CreateProjectRequest(ws, "Empty"), o.userId).id
        for ((public, twin) in listOf("/projects/$proj/metrics" to "$v1/projects/$proj/metrics", "/workspaces/$ws/metrics" to "$v1/workspaces/$ws/metrics")) {
            assertEquals(204, send(address, "GET", "${PublicApi.V1}$public", fx.readKey).first, public)
            val (status, raw) = send(address, "GET", twin, fx.ownerSession)
            assertEquals(200, status, raw)
            assertEquals("0", obj(raw)["counters"]!!.jsonObject.str("probesTotal"))
        }
    }

    @Test
    fun `variable writes and binding changes are audited, through the key`() {
        val address = nextAddress()
        val fx = fixtures(address)
        send(address, "POST", "${PublicApi.V1}/projects/${fx.project}/variables", fx.writeKey, """{"key":"AUDITED","value":"v"}""")
        send(address, "PATCH", "${PublicApi.V1}/variables/${fx.orgVar}", fx.writeKey, """{"value":"w"}""")
        send(address, "DELETE", "${PublicApi.V1}/workspaces/${fx.workspace}/variables/${fx.wsVar}", fx.writeKey)
        send(address, "PATCH", "${PublicApi.V1}/webhooks/bindings/${fx.binding}", fx.writeKey, """{"enabled":false}""")
        val rows = transaction {
            OrgAuditLog.selectAll().where { OrgAuditLog.organizationId eq fx.owner.orgId }.map { it[OrgAuditLog.action] to it[OrgAuditLog.apiKeyId] }
        }
        for (action in listOf("create.variable", "update.variable", "delete.variable", "update.webhook-binding")) {
            // The fixtures audit their own creates, without a key; these are the key's.
            assertTrue(rows.any { it.first == action && it.second != null }, "No $action entry attributed to the key in $rows")
        }
    }

    @Test
    fun `a webhook is bound to a resource once`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val (status, raw) = send(
            address, "POST", "${PublicApi.V1}/webhooks/bindings?resourceType=service&resourceId=${fx.service}", fx.writeKey,
            """{"webhookId":"${fx.webhook}"}""",
        )
        assertEquals(409, status, raw)
        assertEquals("binding_exists", errorOf(raw))
        // And the database holds the line on its own, for creates that race the check.
        val duplicate = runCatching {
            transaction {
                exec(
                    "INSERT INTO resource_webhook_access (id, org_id, resource_type, resource_id, webhook_delivery_id, enabled, created_at) " +
                        "VALUES ('${UUID.randomUUID()}', '${fx.owner.orgId}', 'service', '${fx.service}', '${fx.webhook}', true, now())",
                )
            }
        }
        assertTrue(duplicate.isFailure, "A second binding of the same webhook to the same resource was stored")
    }

    @Test
    fun `a key cannot reach another organization's resources`() {
        val address = nextAddress()
        val mine = fixtures(address)
        val theirs = fixtures(address)
        for (path in listOf(
            "/workspaces/${theirs.workspace}", "/projects/${theirs.project}", "/services/${theirs.service}",
            "/services/${theirs.service}/results/${theirs.result}", "/services/${theirs.service}/metrics",
            "/services/${theirs.service}/results/${theirs.result}/steps/${theirs.step}/body",
            "/workspaces/${theirs.workspace}/variables",
        )) {
            val (keyStatus, keyRaw) = send(address, "GET", "${PublicApi.V1}$path", mine.writeKey)
            val (twinStatus, twinRaw) = send(address, "GET", "$v1$path", mine.ownerSession)
            assertEquals(twinStatus, keyStatus, "$path: key $keyRaw, session $twinRaw")
            assertEquals(404, keyStatus, "$path: $keyRaw")
            assertEquals(errorOf(twinRaw), errorOf(keyRaw), path)
        }
        val (writeStatus, writeRaw) = send(address, "DELETE", "${PublicApi.V1}/services/${theirs.service}", mine.writeKey)
        assertEquals(404, writeStatus, writeRaw)
    }

    // ── Round-two behaviour ──

    @Test
    fun `a body store that does not answer says when to ask again`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val unreachable = bodyStore(fx, BodyStoreInput(
            name = "retry-${UUID.randomUUID().toString().take(6)}", kind = "s3", mode = "in_place",
            endpoint = "http://127.0.0.1:1", bucket = "elsewhere", prefix = "retry-${UUID.randomUUID().toString().take(6)}",
            accessKeyId = TestS3.USER, secretAccessKey = TestS3.PASSWORD,
        ))
        val prefix = transaction { BodyStores.selectAll().where { BodyStores.id eq unreachable }.single()[BodyStores.prefix] }
        val request = Request.Builder()
            .url("http://localhost:$serverPort${bodyPath(fx, stepAt(fx, "s3://elsewhere/$prefix/call_0.json", unreachable))}")
            .header("X-Forwarded-For", address).header("Authorization", "Bearer ${fx.readKey}").build()
        client.newCall(request).execute().use {
            assertEquals(503, it.code)
            assertEquals("10", it.header("Retry-After"))
        }
    }

    @Test
    fun `since is compared to the second run times are kept to`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val second = Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        transaction {
            ProbeResults.insert {
                it[id] = UUID.randomUUID()
                it[serviceId] = fx.service
                it[projectId] = fx.project
                it[workspaceId] = fx.workspace
                it[organizationId] = fx.owner.orgId
                it[startedAt] = second
                it[status] = "success"
                it[runDurationMs] = 1
                it[rawResult] = JsonObject(emptyMap())
            }
        }
        // A `since` 400 ms into the run's second still finds the run.
        val since = second.plusMillis(400)
        val (status, raw) = send(address, "GET", "${PublicApi.V1}/services/${fx.service}/results?since=$since", fx.readKey)
        assertEquals(200, status, raw)
        assertEquals(1, obj(raw).str("total").toInt(), raw)
        // And a run asked for now reports its moment to the second.
        val requestedAt = obj(send(address, "POST", "${PublicApi.V1}/services/${fx.service}/run", fx.writeKey).second).str("requestedAt")
        assertEquals(0, Instant.parse(requestedAt).nano, requestedAt)
    }

    @Test
    fun `a create refused after its row was written leaves nothing behind, and a host's enable gate sees a first save`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val gated = "Gated ${UUID.randomUUID().toString().take(6)}"
        Interceptors.before("service.toggle") { ctx ->
            val name = Services.selectAll().where { Services.id eq ctx.serviceId!! }.single()[Services.name]
            if (name.startsWith("Gated") && ctx.extra["isActive"] == true) throw ForbiddenException("enable_refused")
        }
        fun serviceEvents() = transaction {
            Outbox.selectAll().where { Outbox.aggregateType eq "service" }
                .count { it[Outbox.payload]["parentId"]?.jsonPrimitive?.content == fx.project.toString() }
        }
        try {
            val eventsBefore = serviceEvents()
            // Asked to start switched on, which only the gate can refuse —
            // after the row, the script save and their audit entries exist.
            val (status, raw) = send(
                address, "POST", "${PublicApi.V1}/services", fx.writeKey,
                """{"projectId":"${fx.project}","name":"$gated","script":"get(\"https://example.com\").expect(status: 200)","isActive":true}""",
            )
            assertEquals(403, status, raw)
            assertEquals("enable_refused", errorOf(raw))
            transaction {
                assertEquals(0, Services.selectAll().where { Services.name eq gated }.count(), "No service row")
                assertEquals(0, OrgAuditLog.selectAll().where { OrgAuditLog.entityDisplayName eq gated }.count(), "No audit entry")
            }
            assertEquals(eventsBefore, serviceEvents(), "No outbox event")

            // A dashboard first save goes through the same gate: refused, the
            // save stands and the service stays off.
            val name = "Gated first save ${UUID.randomUUID().toString().take(6)}"
            val created = ServiceController.create(fx.owner.orgId, fx.project, CreateServiceRequest(projectId = fx.project.toString(), name = name), fx.owner.userId)
            val (saveStatus, saveRaw) = send(
                address, "PATCH", "$v1/services/${created.id}/script", fx.ownerSession,
                """{"script":"get(\"https://example.com\").expect(status: 200)","version":1}""",
            )
            assertEquals(200, saveStatus, saveRaw)
            assertEquals("false", obj(saveRaw).str("isActive"))
            assertEquals(2, obj(saveRaw).str("version").toInt())
        } finally {
            Interceptors.clearAll()
        }
    }

    @Test
    fun `an agent the caller cannot see, or one switched off, is unknown to them`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val visible = "visible-${UUID.randomUUID().toString().take(6)}"
        val hidden = "hidden-${UUID.randomUUID().toString().take(6)}"
        val off = "off-${UUID.randomUUID().toString().take(6)}"
        transaction {
            for ((slug, active) in listOf(visible to true, hidden to true, off to false)) {
                ProbeAgents.insert {
                    it[ProbeAgents.slug] = slug
                    it[label] = slug
                    it[agentUri] = "https://$slug.example.com:8443"
                    it[publicKey] = "unused"
                    it[isActive] = active
                    it[lastPing] = Instant.now()
                    it[lastStatus] = "success"
                    it[lastPingDelayMs] = 0
                    it[lastPongDeltaMs] = 0
                    it[createdAt] = Instant.now()
                }
            }
        }
        dev.tracedown.common.agents.AgentVisibility.install { _, _, slugs -> slugs.filter { it != hidden }.toSet() }
        try {
            val agents = Json.parseToJsonElement(send(address, "GET", "${PublicApi.V1}/agents", fx.readKey).second).jsonArray
                .map { it.jsonObject.str("slug") }
            assertTrue(visible in agents && hidden !in agents && off !in agents, "$agents")

            val (status, raw) = send(
                address, "PUT", "${PublicApi.V1}/services/${fx.service}/agents", fx.writeKey,
                """{"slugs":["$visible","$hidden","$off"]}""",
            )
            assertEquals(400, status, raw)
            assertEquals(setOf(hidden, off), obj(raw)["details"]!!.jsonObject["unknown"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
            assertEquals(200, send(address, "PUT", "${PublicApi.V1}/services/${fx.service}/agents", fx.writeKey, """{"slugs":["$visible"]}""").first)
        } finally {
            dev.tracedown.common.agents.AgentVisibility.install(null)
        }
    }

    @Test
    fun `webhook bindings are read and written only by those who may see and change the resource`() {
        val address = nextAddress()
        val fx = fixtures(address)
        // A member who may manage webhooks, but has no grant on the service.
        transaction {
            OrgUsers.update({ (OrgUsers.organizationId eq fx.owner.orgId) and (OrgUsers.userId eq fx.member.userId) }) {
                it[orgWebhooks] = 2
                it[permissionCache] = null
            }
        }
        val list = "/webhooks/bindings?resourceType=service&resourceId=${fx.service}"
        val (keyStatus, keyRaw) = send(address, "GET", "${PublicApi.V1}$list", fx.memberKey)
        val (twinStatus, twinRaw) = send(address, "GET", "$v1/webhooks/bindings/service/${fx.service}", fx.memberSession)
        assertEquals(404, keyStatus, keyRaw)
        assertEquals(twinStatus, keyStatus, twinRaw)
        val (createStatus, createRaw) = send(address, "POST", "${PublicApi.V1}$list", fx.memberKey, """{"webhookId":"${fx.spareWebhook}"}""")
        assertEquals(404, createStatus, createRaw)
    }

    @Test
    fun `refusals name the field at fault`() {
        val address = nextAddress()
        val fx = fixtures(address)
        // A body that parsed but failed its own validation.
        val (tooLong, tooLongRaw) = send(address, "POST", "${PublicApi.V1}/services", fx.writeKey,
            """{"projectId":"${fx.project}","name":"${"x".repeat(200)}"}""")
        assertEquals(400, tooLong, tooLongRaw)
        assertEquals("invalid_request_body", errorOf(tooLongRaw))
        assertEquals("name", obj(tooLongRaw)["details"]!!.jsonObject.str("field"))
        // A body that is not JSON at all names nothing.
        val (garbage, garbageRaw) = send(address, "POST", "${PublicApi.V1}/services", fx.writeKey, "not json")
        assertEquals(400, garbage, garbageRaw)
        assertEquals("invalid_request_body", errorOf(garbageRaw))
        assertTrue(obj(garbageRaw)["details"] == null, garbageRaw)

        // A script that does not validate: every error, and the first one's reason.
        for ((method, path, body) in listOf(
            Triple("POST", "/services", """{"projectId":"${fx.project}","name":"Broken script","script":"this is not lace"}"""),
            Triple("PATCH", "/services/${fx.service}/script", """{"script":"this is not lace","version":${fx.serviceVersion}}"""),
        )) {
            val (status, raw) = send(address, method, "${PublicApi.V1}$path", fx.writeKey, body)
            assertEquals(400, status, "$path: $raw")
            val details = obj(raw)["details"]!!.jsonObject
            assertEquals("script", details.str("field"), raw)
            assertTrue(details["errors"]!!.jsonArray.isNotEmpty(), raw)
            assertTrue(details.str("reason").isNotBlank(), raw)
        }

        // Path ids, and the resource and principal kinds, name themselves.
        for ((path, field) in listOf(
            "/workspaces/nope" to "id",
            "/services/${fx.service}/results/${fx.result}/steps/nope/body" to "stepId",
            "/services/${fx.service}/results/nope" to "resultId",
            "/access/planet/${fx.service}" to "resourceType",
        )) {
            val (status, raw) = send(address, "GET", "${PublicApi.V1}$path", fx.readKey)
            assertEquals(400, status, "$path: $raw")
            assertEquals(field, obj(raw)["details"]!!.jsonObject.str("field"), path)
        }
        val (principal, principalRaw) = send(address, "DELETE", "${PublicApi.V1}/access/service/${fx.service}/robot/${fx.member.userId}", fx.writeKey)
        assertEquals(400, principal, principalRaw)
        assertEquals("principalType", obj(principalRaw)["details"]!!.jsonObject.str("field"))
    }

    @Test
    fun `real answers fit the schemas the description declares for them`() {
        val address = nextAddress()
        val fx = fixtures(address)
        val doc = obj(send(address, "GET", PublicApi.DESCRIPTION_PATH, null).second)
        val components = doc["components"]!!.jsonObject["schemas"]!!.jsonObject
        components.keys.forEach { assertTrue(Regex("^[a-zA-Z0-9._-]+$").matches(it), "Component key '$it'") }

        fun schemaOf(path: String, method: String, status: String): JsonElement =
            doc["paths"]!!.jsonObject[path]!!.jsonObject[method]!!.jsonObject["responses"]!!.jsonObject[status]!!
                .jsonObject["content"]!!.jsonObject["application/json"]!!.jsonObject["schema"]!!

        // A run with assertions and headers, as an agent reports them.
        transaction {
            ProbeSteps.update({ ProbeSteps.id eq fx.step }) {
                it[assertionResults] = Json.parseToJsonElement("""[{"kind":"status","passed":true,"expected":200,"actual":200}]""")
                it[headers] = Json.parseToJsonElement("""{"content-type":"application/json","x-count":"3"}""")
            }
        }
        val result = parse(send(address, "GET", "${PublicApi.V1}/services/${fx.service}/results/${fx.result}", fx.readKey).second)!!
        assertConforms(schemaOf("${PublicApi.V1}/services/{id}/results/{resultId}", "get", "200"), result, components)

        // A service whose metrics are null.
        val bare = ServiceController.create(fx.owner.orgId, fx.project, CreateServiceRequest(projectId = fx.project.toString(), name = "Bare metrics"), fx.owner.userId)
        val service = parse(send(address, "GET", "${PublicApi.V1}/services/${bare.id}", fx.readKey).second)!!
        assertTrue(service.jsonObject["metrics"] is JsonNull, service.toString())
        assertConforms(schemaOf("${PublicApi.V1}/services/{id}", "get", "200"), service, components)

        // A refusal carrying details.
        val refusal = parse(send(address, "GET", "${PublicApi.V1}/workspaces?pageSize=500", fx.readKey).second)!!
        assertConforms(schemaOf("${PublicApi.V1}/workspaces", "get", "400"), refusal, components)

        // Every nullable property of a public type allows null in its schema.
        for ((key, schema) in components) {
            val properties = schema.jsonObject["properties"]?.jsonObject ?: continue
            val descriptor = nullablePropertiesByComponent()[key] ?: continue
            for (name in descriptor) {
                val property = properties[name] ?: continue
                assertTrue(allowsNull(property, components), "$key.$name is nullable but its schema refuses null: $property")
            }
        }
    }

    /** Component key to the names of its nullable properties, from the public types' descriptors. */
    private fun nullablePropertiesByComponent(): Map<String, List<String>> {
        val out = mutableMapOf<String, List<String>>()
        val seen = mutableSetOf<String>()
        fun collect(descriptor: kotlinx.serialization.descriptors.SerialDescriptor) {
            val name = descriptor.serialName.removeSuffix("?")
            if (!seen.add(name + descriptor.elementsCount)) return
            if (descriptor.kind == kotlinx.serialization.descriptors.StructureKind.CLASS) {
                val key = name.split('.').dropWhile { it.firstOrNull()?.isLowerCase() == true }.joinToString(".")
                out[key] = (0 until descriptor.elementsCount)
                    .filter { descriptor.getElementDescriptor(it).isNullable }
                    .map(descriptor::getElementName)
            }
            for (i in 0 until descriptor.elementsCount) collect(descriptor.getElementDescriptor(i))
        }
        PublicApiOperations.types.forEach { collect(kotlinx.serialization.serializer(it).descriptor) }
        return out
    }

    private fun resolve(schema: JsonElement, components: JsonObject): JsonObject {
        val o = schema.jsonObject
        val ref = (o["\$ref"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return o
        return resolve(components[ref.substringAfterLast('/')]!!, components)
    }

    private fun allowsNull(schema: JsonElement, components: JsonObject): Boolean =
        conformanceErrors(schema, JsonNull, components, "").isEmpty()

    private fun assertConforms(schema: JsonElement, value: JsonElement, components: JsonObject) {
        val errors = conformanceErrors(schema, value, components, "$")
        assertTrue(errors.isEmpty(), "Does not fit its schema:\n${errors.joinToString("\n")}\n$value")
    }

    /**
     * A small JSON Schema check — enough for what the description uses:
     * `$ref`, `anyOf`, `type` (one or a list), `enum`, `properties`,
     * `required`, `additionalProperties`, `items`. Formats are not checked.
     */
    private fun conformanceErrors(schema: JsonElement, value: JsonElement, components: JsonObject, at: String): List<String> {
        val s = resolve(schema, components)
        s["anyOf"]?.jsonArray?.let { options ->
            return if (options.any { conformanceErrors(it, value, components, at).isEmpty() }) emptyList()
            else listOf("$at: none of anyOf fits")
        }
        val types = when (val t = s["type"]) {
            null -> null
            is JsonArray -> t.map { it.jsonPrimitive.content }
            else -> listOf(t.jsonPrimitive.content)
        }
        val actual = when {
            value is JsonNull -> "null"
            value is JsonObject -> "object"
            value is JsonArray -> "array"
            value.jsonPrimitive.isString -> "string"
            value.jsonPrimitive.content == "true" || value.jsonPrimitive.content == "false" -> "boolean"
            value.jsonPrimitive.content.toLongOrNull() != null -> "integer"
            else -> "number"
        }
        if (types != null && actual !in types && !(actual == "integer" && "number" in types)) {
            return listOf("$at: $actual where the schema allows $types")
        }
        val errors = mutableListOf<String>()
        s["enum"]?.jsonArray?.let { allowed -> if (value !is JsonNull && value !in allowed) errors += "$at: $value not in $allowed" }
        if (value is JsonObject) {
            s["required"]?.jsonArray?.forEach { r -> if (r.jsonPrimitive.content !in value) errors += "$at: missing ${r.jsonPrimitive.content}" }
            val properties = s["properties"]?.jsonObject ?: JsonObject(emptyMap())
            for ((name, v) in value) {
                val p = properties[name] ?: s["additionalProperties"]?.takeIf { it is JsonObject }
                if (p != null) errors += conformanceErrors(p, v, components, "$at.$name")
            }
        }
        if (value is JsonArray) s["items"]?.let { items -> value.forEachIndexed { i, v -> errors += conformanceErrors(items, v, components, "$at[$i]") } }
        return errors
    }

    // ── The read gate ──

    /**
     * A store client that answers as told: [size] is what it reports, [actual]
     * how many bytes are really there, and [read] what a read does instead of
     * reading, when given. Never allocates past what a read may take.
     */
    private fun stubClient(size: Long?, actual: Int, read: (() -> Nothing)? = null) = object : BodyStorageClient() {
        override fun sizeOf(uri: String): Long? = size
        override fun readBytes(uri: String, maxBytes: Long): BodyStorageClient.StoredBody {
            read?.invoke()
            return if (actual > maxBytes) BodyStorageClient.StoredBody.TooLarge(actual.toLong())
            else BodyStorageClient.StoredBody.Found(ByteArray(actual) { 'a'.code.toByte() }, "text/plain")
        }
    }

    /** Runs [block] with the gate's waits shortened and its seams restored afterwards. */
    private fun <T> withGate(wait: kotlin.time.Duration = 1.seconds, deadline: kotlin.time.Duration = 1.seconds, block: () -> T): T {
        val storage = ProbeResultController.defaultStorage
        val clients = ProbeResultController.storeClient
        ProbeResultController.readWait = wait
        ProbeResultController.readDeadline = deadline
        try {
            return block()
        } finally {
            ProbeResultController.readWait = ProbeResultController.BODY_READ_WAIT
            ProbeResultController.readDeadline = ProbeResultController.BODY_READ_DEADLINE
            ProbeResultController.storeClient = clients
            storage?.let { ProbeResultController.init(it) }
        }
    }

    /** An in_place filesystem store of [fx]'s organization, and a step of its result kept in it. */
    private fun storeStep(fx: Fx): Pair<UUID, UUID> {
        val root = Files.createDirectories(storeBase.resolve("gate-${UUID.randomUUID().toString().take(8)}"))
        val store = bodyStore(fx, BodyStoreInput(
            name = "gate-${UUID.randomUUID().toString().take(6)}", kind = "filesystem", mode = "in_place", rootPath = root.toString(),
        ))
        Files.writeString(root.resolve("body.txt"), "x")
        return store to stepAt(fx, "file://${root.resolve("body.txt")}", store)
    }

    private fun expectStatus(status: Int, block: suspend () -> Unit) {
        val e = assertThrows<ApiException> { runBlocking { block() } }
        assertEquals(status, e.status.value, e.code)
    }

    @Test
    fun `an under-reported body is read once more at the cap`() {
        val fx = fixtures(nextAddress())
        val o = fx.owner
        val (_, step) = storeStep(fx)
        withGate {
            // Says one byte; holds two MiB. The read is held to what was
            // reserved, finds more, reserves the cap and reads again.
            ProbeResultController.storeClient = { stubClient(size = 1, actual = 2 * 1024 * 1024) }
            val body = runBlocking { ProbeResultController.getStepBody(o.orgId, fx.service, fx.result, step, o.userId) }
            assertEquals(2 * 1024 * 1024, (body as BodyStorageClient.BodyContent.Inline).content.length)
            assertEquals(ProbeResultController.idleGate, ProbeResultController.gateState(o.orgId), "The gate is free again")

            // Says one byte; holds more than the cap.
            ProbeResultController.storeClient = { stubClient(size = 1, actual = BodyStoreRegistry.MAX_BODY_BYTES.toInt() + 1) }
            expectStatus(413) { ProbeResultController.getStepBody(o.orgId, fx.service, fx.result, step, o.userId) }
            assertEquals(ProbeResultController.idleGate, ProbeResultController.gateState(o.orgId), "The gate is free again")
        }
    }

    @Test
    fun `two reads per organization, six for body stores`() {
        val orgs = (1..4).map { fixtures(nextAddress()) }
        withGate(wait = 1.seconds, deadline = 30.seconds) {
            ProbeResultController.init(stubClient(size = 1, actual = 1))
            ProbeResultController.storeClient = { stubClient(size = 1, actual = 1) }
            runBlocking {
                val release = CompletableDeferred<Unit>()
                val holders = mutableListOf<kotlinx.coroutines.Job>()
                suspend fun hold(fx: Fx, step: UUID) {
                    val inside = CompletableDeferred<Unit>()
                    holders += launch(Dispatchers.IO) {
                        ProbeResultController.readStepBody(fx.owner.orgId, fx.service, fx.result, step, fx.owner.userId) {
                            inside.complete(Unit)
                            release.await()
                        }
                    }
                    inside.await()
                }
                try {
                    // Organization A: two default-store reads held; a third waits, then 503.
                    val a = orgs[0]
                    repeat(2) { hold(a, a.step) }
                    expectStatusAsync(503) { ProbeResultController.readStepBody(a.owner.orgId, a.service, a.result, a.step, a.owner.userId) { } }
                    // Organization B is not held up by A.
                    val b = orgs[1]
                    ProbeResultController.readStepBody(b.owner.orgId, b.service, b.result, b.step, b.owner.userId) { assertNotNull(it) }
                    release.complete(Unit)
                    holders.forEach { it.join() }
                    holders.clear()

                    // Six body-store reads held, two per organization: every
                    // place body stores may take. Dashboard reads, held inside
                    // the store call, each reserving one unit of the budget —
                    // six key-authenticated reads would not fit in it.
                    val entered = java.util.concurrent.CountDownLatch(6)
                    val open = java.util.concurrent.CountDownLatch(1)
                    ProbeResultController.storeClient = {
                        stubClient(size = 1, actual = 1) {
                            entered.countDown()
                            open.await()
                            throw IllegalStateException("released")
                        }
                    }
                    val parked = mutableListOf<kotlinx.coroutines.Job>()
                    for (fx in orgs.take(3)) {
                        val (_, step) = storeStep(fx)
                        repeat(2) {
                            parked += launch(Dispatchers.IO) {
                                runCatching { ProbeResultController.getStepBody(fx.owner.orgId, fx.service, fx.result, step, fx.owner.userId) }
                            }
                        }
                    }
                    assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS), "Six reads should be inside their stores")
                    assertEquals(0, ProbeResultController.gateState(orgs[3].owner.orgId).storeReads)
                    // A seventh body-store read waits for a place and is refused…
                    val d = orgs[3]
                    val (_, dStep) = storeStep(d)
                    expectStatusAsync(503) { ProbeResultController.readStepBody(d.owner.orgId, d.service, d.result, dStep, d.owner.userId) { } }
                    // …while a default-store read still gets one of the two kept for it.
                    ProbeResultController.readStepBody(d.owner.orgId, d.service, d.result, d.step, d.owner.userId) { assertNotNull(it) }
                    open.countDown()
                    parked.forEach { it.join() }
                } finally {
                    release.complete(Unit)
                }
            }
            orgs.forEach { assertEquals(ProbeResultController.idleGate, ProbeResultController.gateState(it.owner.orgId)) }
        }
    }

    private suspend fun expectStatusAsync(status: Int, block: suspend () -> Unit) {
        val e = try {
            block()
            null
        } catch (e: ApiException) {
            e
        }
        assertNotNull(e, "Expected $status")
        assertEquals(status, e!!.status.value, e.code)
    }

    @Test
    fun `a stalled read is cut off at the deadline`() {
        val fx = fixtures(nextAddress())
        val o = fx.owner
        val (store, step) = storeStep(fx)
        withGate(deadline = 1.seconds) {
            ProbeResultController.storeClient = {
                stubClient(size = 1, actual = 1) { Thread.sleep(60_000); error("unreachable") }
            }
            val started = System.nanoTime()
            expectStatus(503) { ProbeResultController.getStepBody(o.orgId, fx.service, fx.result, step, o.userId) }
            val took = Duration.ofNanos(System.nanoTime() - started)
            assertTrue(took < Duration.ofSeconds(10), "Cut off after $took")
            assertEquals(
                "timeout",
                transaction { BodyStores.selectAll().where { BodyStores.id eq store }.single()[BodyStores.lastFailureCode] },
            )
            assertEquals(ProbeResultController.idleGate, ProbeResultController.gateState(o.orgId))
        }
    }

    @Test
    fun `unexpected client failures are retryable, unreadable locations are not`() {
        val fx = fixtures(nextAddress())
        val o = fx.owner
        withGate {
            for ((failure, status) in listOf(
                IllegalStateException("something else went wrong") to 503,
                dev.tracedown.common.storage.StorageUriException("not a storage URI") to 410,
                dev.tracedown.common.storage.StorageUnconfiguredException("no object store") to 410,
            )) {
                ProbeResultController.init(stubClient(size = 1, actual = 1) { throw failure })
                expectStatus(status) {
                    ProbeResultController.readStepBody(o.orgId, fx.service, fx.result, fx.step, o.userId) { }
                }
            }
        }
    }

    @Test
    fun `enabling an invalid script names it and its errors`() {
        val address = nextAddress()
        val fx = fixtures(address)
        transaction {
            Services.update({ Services.id eq fx.service }) {
                it[script] = "this is not lace"
                it[isActive] = false
            }
        }
        val (status, raw) = send(address, "PATCH", "${PublicApi.V1}/services/${fx.service}/toggle", fx.writeKey, """{"isActive":true}""")
        assertEquals(400, status, raw)
        assertEquals("field_invalid", errorOf(raw))
        val details = obj(raw)["details"]!!.jsonObject
        assertEquals("script", details.str("field"))
        assertTrue(details["errors"]!!.jsonArray.isNotEmpty(), raw)
    }

    // ── The description ──

    @Test
    fun `the description is served without a credential and describes every public route`() {
        val address = nextAddress()
        val (status, raw) = send(address, "GET", PublicApi.DESCRIPTION_PATH, null)
        assertEquals(200, status, raw)
        val doc = obj(raw)
        assertTrue(doc.str("openapi").startsWith("3"), raw.take(200))
        val paths = doc["paths"]!!.jsonObject

        val operations = paths.flatMap { (path, item) ->
            item.jsonObject.filter { (key, value) -> key in HTTP_METHODS && value is JsonObject }
                .map { (method, op) -> Triple(method.uppercase(), path, op.jsonObject) }
        }
        val described = operations.map { "${it.first} ${it.second}" }.sorted()
        val mounted = PublicApiContractTest.publicRoutes(server.application.pluginOrNull(RoutingRoot)!!)
        assertEquals(mounted, described)
        assertTrue(paths.keys.all { it.startsWith("${PublicApi.V1}/") }, "Only the public tree: ${paths.keys}")

        for ((method, path, op) in operations) {
            val where = "$method $path"
            assertTrue(op["operationId"]?.jsonPrimitive?.content?.isNotBlank() == true, "No operationId on $where")
            assertTrue(op["summary"]?.jsonPrimitive?.content?.isNotBlank() == true, "No summary on $where")
            assertTrue(op["tags"]?.jsonArray?.isNotEmpty() == true, "No tag on $where")
            val entry = PublicApiOperations.find(HttpMethod.parse(method), path.removePrefix(PublicApi.V1))!!
            if (method in setOf("POST", "PATCH", "PUT") && entry.operationId !in BODILESS_WRITES) {
                assertNotNull(op["requestBody"], "No request body on $where")
            }
            val responses = op["responses"]!!.jsonObject
            assertTrue(responses.keys.any { it.startsWith("2") }, "No success response on $where")
            assertTrue("401" in responses && "429" in responses, "Missing shared errors on $where")
            val declared = op["parameters"]?.jsonArray?.map { it.jsonObject } ?: emptyList()
            for (q in entry.query) {
                val p = declared.firstOrNull { it.str("name") == q.name && it.str("in") == "query" }
                assertNotNull(p, "$where does not declare ?${q.name}")
                assertEquals(q.required, p!!["required"]?.jsonPrimitive?.content?.toBoolean() ?: false, "$where ?${q.name}")
            }
        }
        val ids = operations.map { it.third.str("operationId") }
        assertEquals(ids.size, ids.toSet().size, "operationIds repeat: $ids")

        assertEquals("bearer", doc["components"]!!.jsonObject["securitySchemes"]!!.jsonObject["apiKey"]!!.jsonObject.str("scheme"))
        assertEquals("td_…", doc["components"]!!.jsonObject["securitySchemes"]!!.jsonObject["apiKey"]!!.jsonObject.str("bearerFormat"))
        assertFalse(":null" in raw, "Absent fields are left out, not written as null")
        // A relative server, so a gateway published under a path prefix still gets its URLs right.
        doc["servers"]!!.jsonArray.forEach { server ->
            val url = server.jsonObject.str("url")
            assertFalse(url.contains("://"), "Absolute server URL in the description: $url")
        }
        assertTrue(doc["tags"]!!.jsonArray.isNotEmpty())
        assertEquals("https://tracedown.dev/guide/api/", doc["externalDocs"]!!.jsonObject.str("url"))
        // Timestamps the API keeps numeric say what they are.
        assertTrue("epoch seconds" in raw && "int64" in raw, "The epoch-second fields are not documented")

        // A credential changes nothing about it, and a key's namespace does not reach it.
        assertEquals(200, send(address, "GET", PublicApi.DESCRIPTION_PATH, "not-a-session").first)
    }

    /** The writes that take no body: a run is asked for, not described. */
    private val BODILESS_WRITES = setOf("runService")

    private val HTTP_METHODS = setOf("get", "put", "post", "delete", "patch", "head", "options")
}
