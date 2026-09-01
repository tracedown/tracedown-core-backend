package dev.tracedown.gateway

import at.favre.lib.crypto.bcrypt.BCrypt
import com.sun.net.httpserver.HttpServer
import com.typesafe.config.ConfigFactory
import dev.lacelang.lacetest.LaceTestSuite
import dev.tracedown.common.domain.HttpDnsDomainVerifier
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.gateway.controllers.domains.DomainController
import dev.tracedown.common.onboarding.OrgService
import dev.tracedown.common.onboarding.DefaultGroupConfig
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Testcontainers
class LaceIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("tracedown_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>
        private var serverPort: Int = 0

        /** Lightweight HTTP server for domain verification tests. */
        private lateinit var verifyServer: HttpServer
        private var verifyServerPort: Int = 0

        /**
         * Verification file registry: path → content.
         * Tests populate this to serve challenge tokens at well-known URLs.
         */
        val verifyFiles = ConcurrentHashMap<String, String>()

        private const val DEFAULT_PASSWORD = "MemberTest1!"
        private const val SNAPSHOT_FILE = "/tmp/test_snapshot.sql"

        data class TestUser(val name: String, val id: String, val orgUserId: String, val email: String, val password: String)
        data class TestOrg(val name: String, val id: String)

        /** A loose JSON Schema object: listed fields must be present and correctly
         *  typed, extra fields allowed. Injected as run variables so scripts can
         *  validate response shapes with `.expect(body: schema($xSchema))`. */
        private fun objSchema(props: Map<String, String>, required: List<String>): Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to props.mapValues { mapOf("type" to it.value) },
            "required" to required,
        )

        private fun arraySchema(items: Map<String, Any?>): Map<String, Any?> = mapOf(
            "type" to "array",
            "items" to items,
        )

        val RESPONSE_SCHEMAS: Map<String, Any?> = mapOf(
            "workspaceSchema" to objSchema(
                mapOf("id" to "string", "name" to "string", "createdAt" to "string"),
                listOf("id", "name", "createdAt"),
            ),
            "projectSchema" to objSchema(
                mapOf("id" to "string", "workspaceId" to "string", "name" to "string", "createdAt" to "string", "serviceCount" to "integer"),
                listOf("id", "workspaceId", "name", "createdAt", "serviceCount"),
            ),
            "serviceSchema" to objSchema(
                mapOf("id" to "string", "projectId" to "string", "name" to "string", "script" to "string", "schedule" to "string", "probeMode" to "string", "queuePolicy" to "string", "saveResponseBodies" to "boolean", "isActive" to "boolean", "version" to "integer", "createdAt" to "string"),
                listOf("id", "projectId", "name", "script", "schedule", "probeMode", "queuePolicy", "saveResponseBodies", "isActive", "version", "createdAt"),
            ),
            "usageSchema" to objSchema(
                mapOf("windowHours" to "integer", "requests" to "integer", "ingressBytes" to "integer", "egressBytes" to "integer", "agentEgressBytes" to "integer"),
                listOf("windowHours", "requests", "ingressBytes", "egressBytes", "agentEgressBytes"),
            ),
            "rulePresetSchema" to objSchema(
                mapOf("id" to "string", "name" to "string", "script" to "string", "scope" to "string"),
                listOf("id", "name", "script", "scope"),
            ),
            // Lace has no array-length operator, so list endpoints are validated
            // by asserting the response is an array of correctly-shaped elements.
            "rulePresetListSchema" to arraySchema(objSchema(
                mapOf("id" to "string", "name" to "string", "script" to "string", "scope" to "string"),
                listOf("id", "name", "script", "scope"),
            )),
            "resourceAccessListSchema" to arraySchema(objSchema(
                mapOf("principalType" to "string", "principalId" to "string", "name" to "string", "permissions" to "integer"),
                listOf("principalType", "principalId", "name", "permissions"),
            )),
        )

        private val testUsers = mutableMapOf<String, TestUser>()
        private val testOrgs = mutableMapOf<String, TestOrg>()

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
                // auth/20_delete_account.lace exercises the closure endpoint's
                // own validation (identity, then owned organizations). The
                // switch is off by default and its gate runs before any of
                // that, so the script needs it on to reach what it tests. The
                // gate itself is pinned in MeRoutesTest, whose server leaves
                // the default in place.
                "platform.allowAccountClosure" to "true",
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

            // Start a lightweight HTTP server for domain verification tests
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

            // Reinitialize DomainController with HTTP verifier pointing at the test server
            val aesKey = "0000000000000000000000000000000000000000000000000000000000000000"
            DomainController.init(aesKey, HttpDnsDomainVerifier(httpScheme = "http", httpPort = verifyServerPort, allowInternalTargets = true))

            transaction {
                val org = Organizations.selectAll().first()
                testOrgs["default"] = TestOrg("Default", org[Organizations.id].toString())
            }

            createTestUsers(setOf("member", "restricted"))
            createTestOrgs(setOf("secondOrg"))

            // Snapshot the DB after seeding — restored before each test
            snapshotDb()
        }

        private fun createTestUsers(names: Set<String>) {
            val orgUuid = UUID.fromString(testOrgs["default"]!!.id)
            val passwordHash = BCrypt.withDefaults().hashToString(12, DEFAULT_PASSWORD.toCharArray())

            transaction {
                for (name in names) {
                    val userId = UUID.randomUUID()
                    val orgUserId = UUID.randomUUID()
                    val email = "$name@tracedown.dev"

                    Users.insert {
                        it[id] = userId
                        it[Users.email] = email
                        it[Users.passwordHash] = passwordHash
                        it[displayName] = name.replaceFirstChar { c -> c.uppercase() }
                        it[isActive] = true
                        it[deleted] = false
                        it[selectedOrgId] = orgUuid
                        it[createdAt] = Instant.now()
                    }

                    OrgUsers.insert {
                        it[id] = orgUserId
                        it[organizationId] = orgUuid
                        it[OrgUsers.userId] = userId
                        it[joinedAt] = Instant.now()
                        it[status] = "active"
                        it[isActive] = true
                        it[deleted] = false
                        it[inviteToken] = ""
                    }

                    testUsers[name] = TestUser(name, userId.toString(), orgUserId.toString(), email, DEFAULT_PASSWORD)
                }
            }
        }

        private fun createTestOrgs(names: Set<String>) {
            transaction {
                val adminId = Users.selectAll()
                    .where { Users.email eq "admin@tracedown.dev" }
                    .first()[Users.id]

                for (name in names) {
                    val result = OrgService.createOrg(
                        name = name,
                        ownerId = adminId,
                        defaultGroups = emptyList(),
                    )
                    testOrgs[name] = TestOrg(name, result.orgId.toString())
                }
            }
        }

        /** Dumps the current DB state to a file inside the container. */
        private fun snapshotDb() {
            postgres.execInContainer(
                "pg_dump", "-U", postgres.username,
                "-d", postgres.databaseName,
                "--data-only", "--disable-triggers",
                "-f", SNAPSHOT_FILE,
            )
        }

        /** Restores DB to the snapshot state: truncates all tables and re-imports. */
        fun restoreSnapshot() {
            postgres.execInContainer(
                "psql", "-U", postgres.username,
                "-d", postgres.databaseName,
                "-c", "DO \$\$ DECLARE r RECORD; BEGIN FOR r IN (SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename != 'flyway_schema_history') LOOP EXECUTE 'TRUNCATE TABLE ' || quote_ident(r.tablename) || ' CASCADE'; END LOOP; END \$\$;",
            )
            postgres.execInContainer(
                "psql", "-U", postgres.username,
                "-d", postgres.databaseName,
                "-f", SNAPSHOT_FILE,
            )
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            server.stop(1000, 5000)
            verifyServer.stop(0)
        }
    }

    private val testVars: Map<String, Any?> get() {
        val vars = mutableMapOf<String, Any?>(
            "adminEmail" to "admin@tracedown.dev",
            "adminPassword" to "Down2trace!",
            "orgId" to testOrgs["default"]!!.id,
            "verifyServerPort" to verifyServerPort.toString(),
        )
        for ((name, user) in testUsers) {
            vars["${name}Email"] = user.email
            vars["${name}Password"] = user.password
            vars["${name}Id"] = user.id
        }
        for ((name, org) in testOrgs) {
            vars["${name}OrgId"] = org.id
        }
        vars.putAll(RESPONSE_SCHEMAS)
        return vars
    }

    /**
     * Single test factory that discovers all .lace files across all subdirectories.
     * DB is restored to the seed snapshot before each test for full isolation.
     */
    @TestFactory
    fun laceTests(): List<DynamicTest> {
        val baseDir = File("src/test/lace")
        val allScripts = baseDir.walkTopDown()
            .filter { it.extension == "lace" }
            .filter { !it.relativeTo(baseDir).path.startsWith("rate-limit") }
            .sortedBy { it.relativeTo(baseDir).path }
            .toList()

        return allScripts.map { script ->
            val relativePath = script.relativeTo(baseDir).path.replace('\\', '/')
            DynamicTest.dynamicTest(relativePath) {
                restoreSnapshot()
                verifyFiles.clear()

                val suite = LaceTestSuite.builder()
                    .scriptsDir(script.parentFile.path)
                    .baseUrl("http://localhost:$serverPort")
                    .vars(testVars)
                    .build()

                val results = suite.dynamicTests()
                    .filter { it.displayName == script.name }
                results.forEach { it.executable.execute() }
            }
        }
    }
}
