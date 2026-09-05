package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.OrgEncryptionKeys
import dev.tracedown.common.models.OrgVariables
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.ProjectVariables
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.ServiceVariables
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.WorkspaceVariables
import dev.tracedown.common.models.Workspaces
import dev.tracedown.common.onboarding.DefaultGroupConfig
import dev.tracedown.common.onboarding.OrgService
import dev.tracedown.common.util.VariableCrypto
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * DB-backed tests for per-org envelope encryption of secret variables: DEK
 * minting at org creation, lazy minting for orgs that predate the feature,
 * and the startup re-encryption pass that migrates legacy ciphertexts.
 */
@Testcontainers
class SecretEncryptionIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_secret_enc_test")
            .withUsername("test")
            .withPassword("test")

        private const val AES_KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        private val NOW: Instant = Instant.now()

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
        }

        // ── Insert helpers ──

        private fun insertUser(): UUID {
            val id = UUID.randomUUID()
            Users.insert {
                it[Users.id] = id
                it[email] = "u-$id@t.dev"
                it[passwordHash] = "x"
                it[displayName] = "u"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertOrg(ownerId: UUID): UUID {
            val id = UUID.randomUUID()
            Organizations.insert {
                it[Organizations.id] = id
                it[name] = "org"
                it[Organizations.ownerId] = ownerId
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertWorkspace(orgId: UUID): UUID {
            val id = UUID.randomUUID()
            Workspaces.insert {
                it[Workspaces.id] = id
                it[organizationId] = orgId
                it[name] = "ws"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertProject(wsId: UUID): UUID {
            val id = UUID.randomUUID()
            Projects.insert {
                it[Projects.id] = id
                it[workspaceId] = wsId
                it[name] = "proj"
                it[createdAt] = NOW
            }
            return id
        }

        private fun insertService(projectId: UUID): UUID {
            val id = UUID.randomUUID()
            Services.insert {
                it[Services.id] = id
                it[Services.projectId] = projectId
                it[name] = "svc"
                it[createdAt] = NOW
            }
            return id
        }

        /** One variable row in any scope table; value/iv/secret/encrypted as given. */
        private fun insertVariable(
            table: Table,
            scopeCol: Column<UUID>,
            scopeId: UUID,
            key: String,
            value: String,
            iv: String?,
            secret: Boolean,
            encrypted: Boolean,
        ): UUID {
            val id = UUID.randomUUID()
            @Suppress("UNCHECKED_CAST")
            table.insert {
                it[table.columns.first { c -> c.name == "id" } as Column<UUID>] = id
                it[scopeCol] = scopeId
                it[table.columns.first { c -> c.name == "key" } as Column<String>] = key
                it[table.columns.first { c -> c.name == "value" } as Column<String>] = value
                it[table.columns.first { c -> c.name == "value_iv" } as Column<String?>] = iv
                it[table.columns.first { c -> c.name == "secret" } as Column<Boolean>] = secret
                it[table.columns.first { c -> c.name == "encrypted" } as Column<Boolean>] = encrypted
                it[table.columns.first { c -> c.name == "created_at" } as Column<Instant>] = NOW
                it[table.columns.first { c -> c.name == "updated_at" } as Column<Instant>] = NOW
            }
            return id
        }

        private fun keyRowCount(orgId: UUID): Long =
            OrgEncryptionKeys.selectAll().where { OrgEncryptionKeys.orgId eq orgId }.count()

        private data class VarState(val value: String, val iv: String?)

        private fun varState(table: Table, id: UUID): VarState {
            @Suppress("UNCHECKED_CAST")
            val idCol = table.columns.first { it.name == "id" } as Column<UUID>
            @Suppress("UNCHECKED_CAST")
            val valueCol = table.columns.first { it.name == "value" } as Column<String>
            @Suppress("UNCHECKED_CAST")
            val ivCol = table.columns.first { it.name == "value_iv" } as Column<String?>
            val row = table.selectAll().where { idCol eq id }.first()
            return VarState(row[valueCol], row[ivCol])
        }
    }

    // ── DEK minting ──

    @Test
    fun `org creation mints the org's data-encryption key`() {
        val ownerId = transaction { insertUser() }
        val result = OrgService.createOrg(
            name = "Envelope Test",
            ownerId = ownerId,
            defaultGroups = listOf(DefaultGroupConfig("Admins", 2, 2, 2, 2, 2, 2, 2)),
        )
        transaction {
            assertEquals(1, keyRowCount(result.orgId), "DEK minted at org creation")
        }
    }

    @Test
    fun `first secret write lazily mints a DEK for an org that predates the feature`() {
        lateinit var orgId: UUID
        transaction {
            orgId = insertOrg(insertUser())
            assertEquals(0, keyRowCount(orgId), "pre-envelope org has no DEK")
        }

        val stored = transaction { VariableCrypto.encrypt(orgId, "hunter2", "org", "apiKey") }

        transaction {
            assertEquals(1, keyRowCount(orgId), "first secret write minted the DEK")
            assertTrue(stored.startsWith("v2:"))
            assertEquals("hunter2", VariableCrypto.decrypt(orgId, stored, null, "org", "apiKey"))
        }
    }

    // ── Startup re-encryption pass ──

    @Test
    fun `re-encryption pass migrates legacy secrets in every scope and leaves the rest alone`() {
        lateinit var orgId: UUID
        lateinit var ids: Map<String, UUID>
        lateinit var nonSecretBefore: VarState
        lateinit var brokenBefore: VarState
        lateinit var brokenId: UUID

        transaction {
            orgId = insertOrg(insertUser())
            val wsId = insertWorkspace(orgId)
            val projId = insertProject(wsId)
            val svcId = insertService(projId)

            fun legacy(plain: String) = VariableCrypto.encrypt(plain)

            val (ov, oi) = legacy("org-secret")
            val (wv, wi) = legacy("ws-secret")
            val (pv, pi) = legacy("proj-secret")
            val (sv, si) = legacy("svc-secret")
            val (nv, ni) = legacy("plain-variable")

            ids = mapOf(
                "org" to insertVariable(OrgVariables, OrgVariables.organizationId, orgId, "oKey", ov, oi, secret = true, encrypted = true),
                "workspace" to insertVariable(WorkspaceVariables, WorkspaceVariables.workspaceId, wsId, "wKey", wv, wi, secret = true, encrypted = true),
                "project" to insertVariable(ProjectVariables, ProjectVariables.projectId, projId, "pKey", pv, pi, secret = true, encrypted = true),
                "service" to insertVariable(ServiceVariables, ServiceVariables.serviceId, svcId, "sKey", sv, si, secret = true, encrypted = true),
            )
            // Non-secret encrypted variable: stays on the platform key, untouched.
            val nonSecretId = insertVariable(OrgVariables, OrgVariables.organizationId, orgId, "nKey", nv, ni, secret = false, encrypted = true)
            nonSecretBefore = varState(OrgVariables, nonSecretId)
            // A row the pass cannot decrypt: logged + skipped, never aborts the batch.
            brokenId = insertVariable(OrgVariables, OrgVariables.organizationId, orgId, "bKey", "!!not-base64!!", "!!bad!!", secret = true, encrypted = true)
            brokenBefore = varState(OrgVariables, brokenId)
        }

        val stats = dev.tracedown.gateway.jobs.SecretReencryption.run()
        assertEquals(4, stats.converted, "all four scopes converted")
        assertEquals(1, stats.failed, "the undecryptable row is reported, not fatal")

        transaction {
            val expected = mapOf(
                "org" to Triple(OrgVariables as Table, "oKey", "org-secret"),
                "workspace" to Triple(WorkspaceVariables as Table, "wKey", "ws-secret"),
                "project" to Triple(ProjectVariables as Table, "pKey", "proj-secret"),
                "service" to Triple(ServiceVariables as Table, "sKey", "svc-secret"),
            )
            for ((scope, spec) in expected) {
                val (table, key, plain) = spec
                val state = varState(table, ids.getValue(scope))
                assertTrue(state.value.startsWith("v2:"), "$scope secret is envelope-encrypted now")
                assertNull(state.iv, "$scope secret sheds its legacy IV")
                assertEquals(plain, VariableCrypto.decrypt(orgId, state.value, null, scope, key))
            }

            // Untouched bystanders.
            val nonSecretAfter = varState(OrgVariables, OrgVariables.selectAll().where { OrgVariables.key eq "nKey" }.first()[OrgVariables.id])
            assertEquals(nonSecretBefore, nonSecretAfter, "non-secret variable stays platform-key encrypted")
            assertEquals(brokenBefore, varState(OrgVariables, brokenId), "undecryptable row left as-is")
        }

        // Idempotent: nothing left to convert; the broken row still just fails.
        val second = dev.tracedown.gateway.jobs.SecretReencryption.run()
        assertEquals(0, second.converted)
        assertEquals(1, second.failed)
    }
}
