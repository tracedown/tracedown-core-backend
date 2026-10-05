package dev.tracedown.gateway

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * The migration that turns API keys into a working credential, against the
 * rows a deployed database actually holds, and its undo.
 *
 * Plain JDBC throughout: the table objects describe the schema as it is after
 * the migration, and half of this test happens before it.
 */
@Testcontainers
class ApiKeyMigrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_api_key_migration_test")
            .withUsername("test")
            .withPassword("test")

        /** The migration immediately before the one under test. */
        private const val PREVIOUS = "1790689566"
        private const val UNDER_TEST = "1791183901"

        /** The companion migration that adds the audit column, applied right after. */
        private const val AUDIT_COLUMN = "1791183961"

        private const val BCRYPT = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
        private val SHA256 = "ab".repeat(32)
    }

    private fun flyway(target: String? = null) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/initial_schema", "classpath:db/migrations")
        .baselineOnMigrate(true)
        .apply { if (target != null) target(MigrationVersion.fromVersion(target)) }
        .load()

    private fun script(name: String): String =
        javaClass.classLoader.getResource("db/migrations/$name")!!.readText()

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.one(sql: String): Any? =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null } }

    private fun Connection.hasColumn(table: String, column: String): Boolean = one(
        "SELECT 1 FROM information_schema.columns WHERE table_name = '$table' AND column_name = '$column'",
    ) != null

    private fun Connection.insertKey(orgId: UUID, createdBy: UUID?, name: String, hash: String): UUID {
        val id = UUID.randomUUID()
        val actsAs = createdBy?.let { "'$it'" } ?: "NULL"
        run(
            "INSERT INTO api_keys (id, organization_id, created_by, name, key_hash) " +
                "VALUES ('$id', '$orgId', $actsAs, '$name', '$hash')",
        )
        return id
    }

    @Test
    fun `revokes every key, undoes without leaving one usable, and keeps the audit trail`() {
        flyway(PREVIOUS).migrate()

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
            val userId = UUID.randomUUID()
            val orgId = UUID.randomUUID()
            // organizations.owner_id and users reference each other; the user goes in first.
            db.run(
                "INSERT INTO users (id, email, password_hash, display_name) " +
                    "VALUES ('$userId', 'migrate@tracedown.dev', '', 'Migrate')",
            )
            db.run("INSERT INTO organizations (id, name, owner_id) VALUES ('$orgId', 'Migration Org', '$userId')")

            // What the previous build wrote: a salted hash nothing can look up.
            val legacy = db.insertKey(orgId, userId, "legacy", BCRYPT)
            // What a database holds if this migration was applied, undone and is
            // being applied again — the undo has revoked it, as it must.
            val minted = db.insertKey(orgId, userId, "minted in between", SHA256)
            db.run("UPDATE api_keys SET revoked = true WHERE id = '$minted'")
            // And a row nobody can account for.
            val stray = db.insertKey(orgId, userId, "stray", "h")

            flyway(AUDIT_COLUMN).migrate()

            for (id in listOf(legacy, minted, stray)) {
                assertEquals(true, db.one("SELECT revoked FROM api_keys WHERE id = '$id'"), "every existing row is revoked")
            }
            assertNull(db.one("SELECT key_prefix FROM api_keys WHERE id = '$legacy'"))
            // Rows that arrive without a level are the narrower of the two, and nothing else is admitted.
            assertEquals(1, (db.one("SELECT access FROM api_keys WHERE id = '$legacy'") as Number).toInt())
            assertThrows<SQLException> { db.run("UPDATE api_keys SET access = 3 WHERE id = '$legacy'") }
            assertTrue(db.hasColumn("org_audit_log", "api_key_id"))

            // One key per digest.
            val duplicate = assertThrows<SQLException> { db.insertKey(orgId, userId, "duplicate", SHA256) }
            assertTrue("api_keys_key_hash_key" in duplicate.message.orEmpty(), duplicate.message)

            // A working key minted under the migration, and an action it took.
            val working = db.insertKey(orgId, userId, "working", "cd".repeat(32))
            val entry = UUID.randomUUID()
            db.run(
                "INSERT INTO org_audit_log (id, organization_id, user_id, action, comment, api_key_id) " +
                    "VALUES ('$entry', '$orgId', '$userId', 'test.act', 'did a thing', '$working')",
            )

            // The undo of the audit column keeps saying what the column said.
            db.run(script("U${AUDIT_COLUMN}__audit_log_api_key.sql"))
            assertFalse(db.hasColumn("org_audit_log", "api_key_id"))
            assertEquals("did a thing (via API key $working)", db.one("SELECT comment FROM org_audit_log WHERE id = '$entry'"))

            // The undo of the key changes leaves no key that could ever work —
            // including one whose user was erased, which an older undo would
            // otherwise hand to the organization's owner.
            val orphan = db.insertKey(orgId, null, "orphan", "ef".repeat(32))
            db.run(script("U${UNDER_TEST}__api_key_auth.sql"))
            for (id in listOf(working, orphan)) {
                assertEquals(true, db.one("SELECT revoked FROM api_keys WHERE id = '$id'"), "the undo revokes every key")
            }
            assertFalse(db.hasColumn("api_keys", "access"))
            assertFalse(db.hasColumn("api_keys", "key_prefix"))
            // Without the index, the previous build's schema is back.
            db.insertKey(orgId, userId, "duplicate, allowed again", SHA256)
            db.run("DELETE FROM api_keys WHERE name = 'duplicate, allowed again'")

            // And forward once more, over the rows the undos left.
            db.run(script("V${UNDER_TEST}__api_key_auth.sql"))
            db.run(script("V${AUDIT_COLUMN}__audit_log_api_key.sql"))
            assertEquals(true, db.one("SELECT revoked FROM api_keys WHERE id = '$working'"))
            assertEquals(true, db.one("SELECT revoked FROM api_keys WHERE id = '$orphan'"))
        }
    }
}
