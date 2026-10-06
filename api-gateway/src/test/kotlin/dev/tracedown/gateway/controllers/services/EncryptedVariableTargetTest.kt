package dev.tracedown.gateway.controllers.services

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.OrgDomains
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.net.ProbeTargetPolicy
import dev.tracedown.gateway.TestRedis
import dev.tracedown.gateway.controllers.metrics.DashboardMetricsController
import dev.tracedown.gateway.data.services.ServiceSummary
import dev.tracedown.gateway.data.services.UpdateServiceRequest
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.VariableCrypto
import io.lettuce.core.RedisClient
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The save-time target checks judge an encrypted variable by its real value.
 *
 * They used to see plaintext ("metric") variables only, so a base URL kept in
 * an ordinary encrypted variable — the place the documentation tells users to
 * keep one — stayed `$p.baseUrl` to them: refused outright on an install that
 * only probes public addresses, and always an unverifiable host to the
 * unverified-domain rule. Dispatch decrypts these values; the save-time checks
 * now decrypt them the same way, and never show what they decrypted.
 */
@Testcontainers
class EncryptedVariableTargetTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = org.testcontainers.postgresql.PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_encrypted_target_test")
            .withUsername("test")
            .withPassword("test")

        private const val AES_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        private const val D = "$"

        private lateinit var orgId: UUID
        private lateinit var ownerId: UUID
        private lateinit var workspaceId: UUID
        private lateinit var projectId: UUID
        private lateinit var redis: RedisClient

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))

            VariableCrypto.init(AES_KEY)
            redis = RedisClient.create(TestRedis.url)
            val connection = redis.connect()
            DashboardMetricsController.init({ connection.sync() })
            ServiceController.init(ProbeTargetPolicy.Mode.PUBLIC_ONLY)
            ServiceController.init(trustedDomainMode = false)

            transaction {
                ownerId = UUID.randomUUID()
                Users.insert {
                    it[id] = ownerId
                    it[email] = "enc-target-$ownerId@tracedown.test"
                    it[passwordHash] = "x"
                    it[displayName] = "owner"
                    it[createdAt] = NOW
                }
                orgId = UUID.randomUUID()
                Organizations.insert {
                    it[id] = orgId
                    it[name] = "enc-target-org"
                    it[Organizations.ownerId] = EncryptedVariableTargetTest.ownerId
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
                workspaceId = UUID.randomUUID()
                Workspaces.insert {
                    it[id] = workspaceId
                    it[organizationId] = orgId
                    it[name] = "enc-target-ws"
                    it[createdAt] = NOW
                }
                projectId = UUID.randomUUID()
                Projects.insert {
                    it[id] = projectId
                    it[Projects.workspaceId] = EncryptedVariableTargetTest.workspaceId
                    it[name] = "enc-target-proj"
                    it[createdAt] = NOW
                }
                // Verified, with subdomains: covers api.verified.example.
                OrgDomains.insert {
                    it[id] = UUID.randomUUID()
                    it[organizationId] = orgId
                    it[domain] = "verified.example"
                    it[challenge] = "c"
                    it[verificationType] = "dns"
                    it[status] = "verified"
                    it[verifiedAt] = NOW
                    it[wildcardEnabled] = true
                }
            }
        }

        @AfterAll
        @JvmStatic
        fun teardown() {
            // The controller is a process-wide object: hand the next class the defaults.
            ServiceController.init(ProbeTargetPolicy.Mode.ALLOW_PRIVATE)
            ServiceController.init(trustedDomainMode = true)
            redis.shutdown()
        }

        private fun seedService(): UUID {
            val id = UUID.randomUUID()
            transaction {
                Services.insert {
                    it[Services.id] = id
                    it[Services.projectId] = EncryptedVariableTargetTest.projectId
                    it[name] = "svc-$id"
                    it[script] = ""
                    it[version] = 1
                    it[isActive] = false
                    it[schedule] = "*/5 * * * *"
                    it[createdAt] = NOW
                }
            }
            return id
        }

        private enum class Kind { VARIABLE, SECRET }

        private fun table(scope: String): Pair<Table, Column<UUID>> = when (scope) {
            "org" -> OrgVariables to OrgVariables.organizationId
            "workspace" -> WorkspaceVariables to WorkspaceVariables.workspaceId
            "project" -> ProjectVariables to ProjectVariables.projectId
            else -> ServiceVariables to ServiceVariables.serviceId
        }

        private fun scopeId(scope: String, serviceId: UUID): UUID = when (scope) {
            "org" -> orgId
            "workspace" -> workspaceId
            "project" -> projectId
            else -> serviceId
        }

        /** One variable stored exactly as the variable endpoints store it. */
        private fun seedVariable(scope: String, key: String, plaintext: String, kind: Kind, serviceId: UUID) {
            transaction {
                val (stored, iv) = when (kind) {
                    Kind.SECRET -> VariableCrypto.encrypt(orgId, plaintext, scope, key) to null
                    Kind.VARIABLE -> VariableCrypto.encrypt(plaintext)
                }
                insertRaw(scope, key, stored, iv, secret = kind == Kind.SECRET, serviceId = serviceId)
            }
        }

        private fun insertRaw(scope: String, key: String, value: String, iv: String?, secret: Boolean, serviceId: UUID) {
            val (table, scopeCol) = table(scope)
            transaction {
                @Suppress("UNCHECKED_CAST")
                table.insert {
                    it[table.columns.first { c -> c.name == "id" } as Column<UUID>] = UUID.randomUUID()
                    it[scopeCol] = scopeId(scope, serviceId)
                    it[table.columns.first { c -> c.name == "key" } as Column<String>] = key
                    it[table.columns.first { c -> c.name == "value" } as Column<String>] = value
                    it[table.columns.first { c -> c.name == "value_iv" } as Column<String?>] = iv
                    it[table.columns.first { c -> c.name == "secret" } as Column<Boolean>] = secret
                    it[table.columns.first { c -> c.name == "encrypted" } as Column<Boolean>] = true
                    it[table.columns.first { c -> c.name == "created_at" } as Column<Instant>] = NOW
                    it[table.columns.first { c -> c.name == "updated_at" } as Column<Instant>] = NOW
                }
            }
        }

        private fun save(serviceId: UUID, script: String): ServiceSummary =
            ServiceController.update(orgId, serviceId, UpdateServiceRequest(script = script, version = 1), ownerId)

        private fun refusal(serviceId: UUID, script: String): ApiException =
            assertThrows<ApiException> { save(serviceId, script) }

        private fun storedScript(serviceId: UUID): String = transaction {
            Services.selectAll().where { Services.id eq serviceId }.single()[Services.script]
        }

        /** A unique key per case: project/workspace/org variables outlive the service. */
        private fun key(prefix: String) = prefix + UUID.randomUUID().toString().replace("-", "").take(8)
    }

    @Test
    fun `an encrypted variable holding a public base URL is a valid host, at every scope`() {
        for ((scope, ref) in listOf("org" to "o", "workspace" to "w", "project" to "p", "service" to "s")) {
            for (kind in Kind.entries) {
                val serviceId = seedService()
                val k = key("baseUrl")
                seedVariable(scope, k, "https://example.org", kind, serviceId)
                val script = """get("$D$ref.$k/health").expect(status: 200)"""

                val summary = save(serviceId, script)

                assertEquals(2, summary.version, "$scope $kind")
                assertEquals(script, storedScript(serviceId), "$scope $kind")
            }
        }
    }

    @Test
    fun `an encrypted variable holding a private or loopback address is refused`() {
        for (kind in Kind.entries) {
            for (target in listOf("http://10.0.0.5", "http://localhost")) {
                val serviceId = seedService()
                val k = key("baseUrl")
                seedVariable("project", k, target, kind, serviceId)

                val e = refusal(serviceId, """get("${D}p.$k/health").expect(status: 200)""")

                assertEquals(ErrorCodes.BLOCKED_PROBE_TARGET, e.code, "$kind $target")
                // The refusal carries a code and nothing of the value.
                assertFalse(e.message.contains(target), "$kind $target")
                assertFalse(e.details?.toString().orEmpty().contains(target), "$kind $target")
                assertEquals("", storedScript(serviceId), "nothing written")
            }
        }
    }

    @Test
    fun `a host variable that does not exist is still refused`() {
        val serviceId = seedService()
        val e = refusal(serviceId, """get("${D}p.${key("missing")}/health").expect(status: 200)""")
        assertEquals(ErrorCodes.BLOCKED_PROBE_TARGET, e.code)
    }

    @Test
    fun `a value that will not decrypt is unresolved, not an error`() {
        // Garbage in the legacy format, garbage in the envelope format, and a
        // real secret bound to another key — the AAD refuses it under this one.
        val legacy = key("legacy")
        val envelope = key("envelope")
        val moved = key("moved")
        val serviceId = seedService()
        insertRaw("project", legacy, "bm90LWNpcGhlcnRleHQ=", "AAAAAAAAAAAAAAAAAAAAAA==", secret = false, serviceId = serviceId)
        insertRaw("project", envelope, "v2:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", null, secret = true, serviceId = serviceId)
        val boundElsewhere = transaction { VariableCrypto.encrypt(orgId, "https://example.org", "project", "other") }
        insertRaw("project", moved, boundElsewhere, null, secret = true, serviceId = serviceId)

        for (k in listOf(legacy, envelope, moved)) {
            val e = refusal(serviceId, """get("${D}p.$k/health").expect(status: 200)""")
            assertEquals(ErrorCodes.BLOCKED_PROBE_TARGET, e.code, k)
        }
    }

    @Test
    fun `an encrypted base URL on a verified domain counts as covered`() {
        for (kind in Kind.entries) {
            val serviceId = seedService()
            val k = key("baseUrl")
            seedVariable("project", k, "https://api.verified.example", kind, serviceId)
            // Four calls: over the unverified-domain limit, so this saves only
            // when every host is proven owned.
            val script = (1..4).joinToString("\n") { """get("${D}p.$k/v$it").expect(status: 200)""" }

            save(serviceId, script)

            val summary = ServiceController.get(orgId, serviceId, ownerId)
            assertEquals(emptyList<String>(), summary.unverifiedTargets, "$kind")
        }
    }

    @Test
    fun `a host from an encrypted value is named by its reference, never resolved`() {
        for (kind in Kind.entries) {
            val serviceId = seedService()
            val k = key("baseUrl")
            val host = "hidden-${UUID.randomUUID().toString().take(8)}.example.net"
            seedVariable("project", k, "https://$host", kind, serviceId)
            val script = """get("${D}p.$k/health").expect(status: 200)
                |get("https://literal.example.com/${D}p.$k").expect(status: 200)""".trimMargin()

            save(serviceId, script)
            val summary = ServiceController.get(orgId, serviceId, ownerId)

            // The literal host is the script's own text and is named; the one
            // built from the encrypted value is named as the script spells it.
            assertEquals(listOf("${D}p.$k/health", "literal.example.com"), summary.unverifiedTargets, "$kind")
            assertFalse(Json.encodeToString(ServiceSummary.serializer(), summary).contains(host), "$kind")
        }
    }

    @Test
    fun `braces with no reference inside are not a reference`() {
        // Lace's braced form is `${$p.key}`. The save-time checks used to
        // resolve `${p.key}` as well — a spelling Lace leaves as text, so the
        // host they passed was not the one the agent is sent to.
        val k = key("baseUrl")
        transaction {
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = EncryptedVariableTargetTest.projectId
                it[key] = k
                it[value] = "https://api.verified.example"
                it[secret] = false
                it[encrypted] = false
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }
        val refused = seedService()
        assertEquals(
            ErrorCodes.BLOCKED_PROBE_TARGET,
            refusal(refused, """get("${D}{p.$k}/health").expect(status: 200)""").code,
        )
        assertEquals("", storedScript(refused), "nothing written")

        // A templated host is left for dispatch by the address check, and the
        // unverified-domain rule counts it unverified though the value sits on
        // a verified domain.
        val counted = seedService()
        val url = "https://${D}{p.$k}/health"
        save(counted, """get("$url").expect(status: 200)""")
        assertEquals(listOf(url), ServiceController.get(orgId, counted, ownerId).unverifiedTargets)
    }

    @Test
    fun `a plaintext variable host is still named as resolved`() {
        val serviceId = seedService()
        val k = key("baseUrl")
        transaction {
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = EncryptedVariableTargetTest.projectId
                it[key] = k
                it[value] = "https://plain.example.net"
                it[secret] = false
                it[encrypted] = false
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }
        save(serviceId, """get("${D}p.$k/health").expect(status: 200)""")
        assertEquals(listOf("plain.example.net"), ServiceController.get(orgId, serviceId, ownerId).unverifiedTargets)
    }

    @Test
    fun `a braced reference is judged on its value, as dispatch judges it`() {
        for (kind in Kind.entries) {
            val k = key("host")
            val privateService = seedService()
            seedVariable("project", k, "http://10.0.0.5", kind, privateService)
            assertEquals(
                ErrorCodes.BLOCKED_PROBE_TARGET,
                refusal(privateService, """get("${D}{${D}p.$k}/health").expect(status: 200)""").code,
                "$kind",
            )
            val p = key("host")
            val publicService = seedService()
            seedVariable("project", p, "https://example.org", kind, publicService)
            assertEquals(2, save(publicService, """get("${D}{${D}p.$p}/health").expect(status: 200)""").version, "$kind")
        }
    }

    @Test
    fun `a subdomain built from a variable is judged on its value`() {
        // Four calls, so this saves only when every host is seen to be on the
        // verified domain. The dotted form used to be read greedily — as a
        // variable named `s.sub.verified.example` — and so never resolved.
        val serviceId = seedService()
        seedVariable("service", "sub", "api", Kind.VARIABLE, serviceId)
        val script = listOf(
            """get("https://${D}{${D}s.sub}.verified.example/a").expect(status: 200)""",
            """get("https://${D}s.sub.verified.example/b").expect(status: 200)""",
            """get("https://${D}{${D}s.sub}.verified.example/c").expect(status: 200)""",
            """get("https://${D}s.sub.verified.example/d").expect(status: 200)""",
        ).joinToString("\n")

        save(serviceId, script)

        assertEquals(emptyList<String>(), ServiceController.get(orgId, serviceId, ownerId).unverifiedTargets)
    }

    @Test
    fun `computed variables win over a stored one, as at dispatch`() {
        // `$s.name` is the service's name on every run, whatever a stored
        // variable called `name` holds — so it is judged as the name here too.
        val serviceId = seedService()
        seedVariable("service", "name", "10.0.0.5", Kind.VARIABLE, serviceId)

        val summary = save(serviceId, """get("http://${D}s.name.example.com/").expect(status: 200)""")

        assertEquals(2, summary.version)
        val host = "svc-$serviceId.example.com"
        assertEquals(listOf(host), ServiceController.get(orgId, serviceId, ownerId).unverifiedTargets)
    }

    @Test
    fun `an organization with no key and a legacy value with no IV are unresolved`() {
        // A legacy value stored without its IV.
        val legacyService = seedService()
        val legacy = key("legacy")
        val (stored, _) = VariableCrypto.encrypt("https://example.org")
        insertRaw("project", legacy, stored, null, secret = false, serviceId = legacyService)
        assertEquals(
            ErrorCodes.BLOCKED_PROBE_TARGET,
            refusal(legacyService, """get("${D}p.$legacy/health").expect(status: 200)""").code,
        )

        // An envelope value under an organization that never had a key.
        val (otherOrg, otherProject) = transaction {
            val org = UUID.randomUUID()
            Organizations.insert {
                it[id] = org
                it[name] = "keyless-org"
                it[Organizations.ownerId] = EncryptedVariableTargetTest.ownerId
                it[createdAt] = NOW
            }
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = org
                it[userId] = ownerId
                it[status] = "active"
                it[joinedAt] = NOW
                it[inviteToken] = "t-${UUID.randomUUID()}"
            }
            val ws = UUID.randomUUID()
            Workspaces.insert {
                it[id] = ws
                it[organizationId] = org
                it[name] = "keyless-ws"
                it[createdAt] = NOW
            }
            val proj = UUID.randomUUID()
            Projects.insert {
                it[id] = proj
                it[Projects.workspaceId] = ws
                it[name] = "keyless-proj"
                it[createdAt] = NOW
            }
            org to proj
        }
        val serviceId = UUID.randomUUID()
        transaction {
            Services.insert {
                it[id] = serviceId
                it[Services.projectId] = otherProject
                it[name] = "svc-$serviceId"
                it[script] = ""
                it[version] = 1
                it[isActive] = false
                it[schedule] = "*/5 * * * *"
                it[createdAt] = NOW
            }
            ProjectVariables.insert {
                it[id] = UUID.randomUUID()
                it[ProjectVariables.projectId] = otherProject
                it[ProjectVariables.key] = "host"
                // Encrypted for the org that has a key; this one has none.
                it[value] = VariableCrypto.encrypt(orgId, "https://example.org", "project", "host")
                it[valueIv] = null
                it[secret] = true
                it[encrypted] = true
                it[createdAt] = NOW
                it[updatedAt] = NOW
            }
        }
        val e = assertThrows<ApiException> {
            ServiceController.update(
                otherOrg, serviceId,
                UpdateServiceRequest(script = """get("${D}p.host/health").expect(status: 200)""", version = 1),
                ownerId,
            )
        }
        assertEquals(ErrorCodes.BLOCKED_PROBE_TARGET, e.code)
    }

    @Test
    fun `no log line carries a decrypted target`() {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logger = LoggerFactory.getLogger("dev.tracedown") as Logger
        val previous = logger.level
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        try {
            val serviceId = seedService()
            val k = key("host")
            seedVariable("project", k, "http://10.0.0.5", Kind.SECRET, serviceId)
            refusal(serviceId, """get("${D}p.$k/health").expect(status: 200)""")
            val moved = key("moved")
            insertRaw(
                "project", moved,
                transaction { VariableCrypto.encrypt(orgId, "http://10.9.9.9", "project", "elsewhere") },
                null, secret = true, serviceId = serviceId,
            )
            refusal(serviceId, """get("${D}p.$moved/health").expect(status: 200)""")
        } finally {
            logger.detachAppender(appender)
            logger.level = previous
        }
        val lines = appender.list.map { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertTrue(lines.any { "rejected" in it && "${D}p." in it }, lines.toString())
        assertTrue(lines.none { "10.0.0.5" in it || "10.9.9.9" in it }, lines.toString())
    }
}
