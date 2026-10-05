package dev.tracedown.gateway.controllers.services

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
        assertTrue(ServiceController.get(orgId, serviceId, ownerId).unverifiedTargets == listOf("plain.example.net"))
    }
}
