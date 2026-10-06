package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.typesafe.config.ConfigFactory
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.gateway.controllers.projects.ProjectController
import dev.tracedown.gateway.controllers.services.ServiceController
import dev.tracedown.gateway.controllers.workspaces.WorkspaceController
import dev.tracedown.gateway.data.projects.CreateProjectRequest
import dev.tracedown.gateway.data.services.CreateServiceRequest
import dev.tracedown.gateway.data.workspaces.CreateWorkspaceRequest
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A variable key is unique among live variables only, through the routes
 * people use.
 *
 * Deletes are soft, and the variable tables used to hold the key unique over
 * every row, so deleting a variable and creating one with the same key answered
 * 500 until the purge job erased the deleted row — a month, at a 30-day
 * retention. Uniqueness is now a partial index over live rows; these tests hold
 * every scope to it, and to the answers the races around it get.
 */
@Testcontainers
class LiveVariableKeysTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_live_variable_keys_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        private const val PASSWORD = "LiveKeys123!"

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            val overrides = ConfigFactory.parseMap(mapOf(
                "database.url" to postgres.jdbcUrl,
                "database.user" to postgres.username,
                "database.password" to postgres.password,
                "redis.a.url" to TestRedis.url,
                "redis.b.url" to TestRedis.url,
                "redis.c.url" to "",
                "rateLimit.enabled" to "false",
                "platform.trustedDomainMode" to "true",
            ))
            val env = applicationEnvironment {
                config = HoconApplicationConfig(overrides.withFallback(ConfigFactory.load()))
            }
            server = embeddedServer(Netty, env, configure = { connector { port = 0 } })
            server.start(wait = false)
            serverPort = runBlocking { server.engine.resolvedConnectors().first().port }
            awaitReady()
        }

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
        }
    }

    private val client = OkHttpClient.Builder().readTimeout(Duration.ofSeconds(30)).build()
    private val jsonType = "application/json".toMediaType()

    // ── Fixtures ──

    /** One organization with a workspace, a project and a service, and its owner signed in. */
    private class Fx(
        val orgId: UUID,
        val userId: UUID,
        val token: String,
        val workspace: UUID,
        val project: UUID,
        val service: UUID,
    )

    private fun fixtures(): Fx {
        val (userId, orgId, email) = transaction {
            val userId = UUID.randomUUID()
            val email = "names-${userId.toString().take(8)}@tracedown.dev"
            Users.insert {
                it[id] = userId
                it[Users.email] = email
                it[passwordHash] = BCrypt.withDefaults().hashToString(4, PASSWORD.toCharArray())
                it[displayName] = "names"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            val orgId = UUID.randomUUID()
            Organizations.insert {
                it[id] = orgId
                it[name] = "Names Org ${orgId.toString().take(6)}"
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
            Triple(userId, orgId, email)
        }
        val ws = UUID.fromString(WorkspaceController.create(orgId, CreateWorkspaceRequest("Names WS"), userId).id)
        val proj = UUID.fromString(ProjectController.create(orgId, ws, CreateProjectRequest(ws.toString(), "Names Project"), userId).id)
        val svc = UUID.fromString(
            ServiceController.create(orgId, proj, CreateServiceRequest(projectId = proj.toString(), name = "Names Service"), userId).id,
        )

        val (status, raw) = send("POST", "/api/v1/auth/login", null, """{"email":"$email","password":"$PASSWORD"}""")
        assertEquals(200, status, "Login response: $raw")
        return Fx(orgId, userId, obj(raw).str("token"), ws, proj, svc)
    }

    /** A variable scope: where its routes live and the table its rows land in. */
    private class Scope(
        val name: String,
        val base: (Fx) -> String,
        val table: Table,
        val parentColumn: org.jetbrains.exposed.v1.core.Column<UUID>,
        val parentId: (Fx) -> UUID,
        val keyColumn: org.jetbrains.exposed.v1.core.Column<String>,
        val deletedColumn: org.jetbrains.exposed.v1.core.Column<Boolean>,
    )

    private val scopes = listOf(
        Scope("org", { "/api/v1/org/variables" }, OrgVariables, OrgVariables.organizationId, { it.orgId },
            OrgVariables.key, OrgVariables.deleted),
        Scope("workspace", { "/api/v1/workspaces/${it.workspace}/variables" }, WorkspaceVariables,
            WorkspaceVariables.workspaceId, { it.workspace }, WorkspaceVariables.key, WorkspaceVariables.deleted),
        Scope("project", { "/api/v1/projects/${it.project}/variables" }, ProjectVariables,
            ProjectVariables.projectId, { it.project }, ProjectVariables.key, ProjectVariables.deleted),
        Scope("service", { "/api/v1/services/${it.service}/variables" }, ServiceVariables,
            ServiceVariables.serviceId, { it.service }, ServiceVariables.key, ServiceVariables.deleted),
    )

    // ── HTTP ──

    private fun send(method: String, path: String, token: String?, body: String? = null): Pair<Int, String> {
        val builder = Request.Builder().url("http://localhost:$serverPort$path")
        when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            else -> builder.method(method, (body ?: "{}").toRequestBody(jsonType))
        }
        token?.let { builder.header("Authorization", "Bearer $it") }
        return client.newCall(builder.build()).execute().use { it.code to it.body.string() }
    }

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw).jsonObject

    private fun JsonObject.str(field: String): String = this[field]!!.jsonPrimitive.content

    private fun create(fx: Fx, scope: Scope, key: String, value: String = "v", type: String = "variable"): UUID {
        val (status, raw) = send("POST", scope.base(fx), fx.token, """{"key":"$key","value":"$value","type":"$type"}""")
        assertEquals(200, status, "${scope.name}: create $key: $raw")
        return UUID.fromString(obj(raw).str("id"))
    }

    private fun delete(fx: Fx, scope: Scope, id: UUID) {
        val (status, raw) = send("DELETE", "${scope.base(fx)}/$id", fx.token)
        assertEquals(200, status, "${scope.name}: delete: $raw")
    }

    /** The keys the scope's list route shows. */
    private fun listedKeys(fx: Fx, scope: Scope): List<String> {
        val (status, raw) = send("GET", "${scope.base(fx)}?pageSize=100", fx.token)
        assertEquals(200, status, "${scope.name}: list: $raw")
        return obj(raw)["items"]!!.jsonArray.map { it.jsonObject.str("key") }
    }

    /** Every row of the scope's table under [fx]'s parent, as (key, deleted). */
    private fun rows(fx: Fx, scope: Scope): List<Pair<String, Boolean>> = transaction {
        scope.table.selectAll().where { scope.parentColumn eq scope.parentId(fx) }
            .map { it[scope.keyColumn] to it[scope.deletedColumn] }
    }

    /** Fires [count] copies of [call] at once and returns their statuses. */
    private fun race(count: Int, call: () -> Int): List<Int> {
        val pool = Executors.newFixedThreadPool(count)
        try {
            val start = CountDownLatch(1)
            val futures = (1..count).map { pool.submit<Int> { start.await(); call() } }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }

    // ── Variables ──

    @Test
    fun `a deleted variable's key can be created again at every scope`() {
        val fx = fixtures()
        for (scope in scopes) {
            val first = create(fx, scope, "REUSED")
            delete(fx, scope, first)
            val second = create(fx, scope, "REUSED")
            assertEquals(1, listedKeys(fx, scope).count { it == "REUSED" }, scope.name)

            // Deleted, re-created and deleted again: two deleted rows of one key,
            // each keeping it as it was.
            delete(fx, scope, second)
            create(fx, scope, "REUSED")
            val all = rows(fx, scope).filter { it.first == "REUSED" }
            assertEquals(1, all.count { !it.second }, scope.name)
            assertEquals(2, all.count { it.second }, scope.name)
            assertEquals(1, listedKeys(fx, scope).count { it == "REUSED" }, scope.name)
        }
    }

    @Test
    fun `two creates of one live key race to one 200 and a 409, never a 500`() {
        val fx = fixtures()
        for (scope in scopes) {
            repeat(3) { round ->
                val key = "RACED_$round"
                val statuses = race(8) {
                    send("POST", scope.base(fx), fx.token, """{"key":"$key","value":"v"}""").first
                }
                assertEquals(1, statuses.count { it == 200 }, "${scope.name}: $statuses")
                assertEquals(7, statuses.count { it == 409 }, "${scope.name}: $statuses")
                assertEquals(1, rows(fx, scope).count { it.first == key && !it.second }, scope.name)
            }
        }
    }

    @Test
    fun `two deletes of one variable race to one 200 and a 404`() {
        val fx = fixtures()
        for (scope in scopes) {
            repeat(3) { round ->
                val id = create(fx, scope, "DOOMED_$round")
                val statuses = race(8) { send("DELETE", "${scope.base(fx)}/$id", fx.token).first }
                assertEquals(1, statuses.count { it == 200 }, "${scope.name}: $statuses")
                assertEquals(7, statuses.count { it == 404 }, "${scope.name}: $statuses")
                val deletedAt = transaction {
                    scope.table.selectAll().where { scope.parentColumn eq scope.parentId(fx) }
                        .filter { it[scope.keyColumn] == "DOOMED_$round" }
                        .map { it[scope.deletedColumn] }
                }
                assertEquals(listOf(true), deletedAt, scope.name)
            }
        }
    }

    @Test
    fun `a hierarchy shows the live variable of a key, not a deleted one`() {
        val fx = fixtures()
        for (scope in scopes) {
            val key = "SHOWN_${scope.name.uppercase()}"
            delete(fx, scope, create(fx, scope, key, value = "deleted-value", type = "metric"))
            create(fx, scope, key, value = "live-value", type = "metric")
        }
        val (status, raw) = send("GET", "/api/v1/services/${fx.service}/variables/hierarchy", fx.token)
        assertEquals(200, status, raw)
        assertFalse(raw.contains("deleted-value"), raw)
        for (scope in scopes) {
            assertEquals(1, Regex("\"SHOWN_${scope.name.uppercase()}\"").findAll(raw).count(), "${scope.name}: $raw")
        }
        assertEquals(4, Regex("live-value").findAll(raw).count(), raw)
    }
}
