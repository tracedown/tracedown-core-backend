package dev.tracedown.gateway

import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * The migration that makes variable keys and agent slugs unique among live
 * rows only, against rows a deployed database holds, and its undo.
 *
 * Plain JDBC: half of this happens before the migration.
 */
@Testcontainers
class FreeDeletedNamesMigrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_free_deleted_names_test")
            .withUsername("test")
            .withPassword("test")

        /** The migration immediately before the one under test. */
        private const val PREVIOUS = "1791186905"
        private const val UNDER_TEST = "1791203755"
    }

    private fun flyway(target: String) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/initial_schema", "classpath:db/migrations")
        .baselineOnMigrate(true)
        .target(MigrationVersion.fromVersion(target))
        .load()

    private fun script(name: String): String =
        javaClass.classLoader.getResource("db/migrations/$name")!!.readText()

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.one(sql: String): Any? =
        createStatement().use { st -> st.executeQuery(sql).use { rs -> if (rs.next()) rs.getObject(1) else null } }

    private fun Connection.exists(sql: String): Boolean = one(sql) != null

    private fun Connection.hasIndex(name: String) = exists("SELECT 1 FROM pg_indexes WHERE indexname = '$name'")

    private fun Connection.hasConstraint(name: String) = exists("SELECT 1 FROM pg_constraint WHERE conname = '$name'")

    /** One variable scope: its table, its parent column, and a parent row to hang variables off. */
    private class Scope(val table: String, val parentColumn: String, val parentId: UUID) {
        val constraint = "${table}_${parentColumn}_key_key"
    }

    private fun Connection.variable(
        scope: Scope, key: String, deleted: Boolean,
        deletedAt: String? = null, id: UUID = UUID.randomUUID(),
    ): UUID {
        val at = if (deletedAt != null) "'$deletedAt'" else "NULL"
        run(
            "INSERT INTO ${scope.table} (id, ${scope.parentColumn}, key, value, secret, encrypted, deleted, deleted_at) " +
                "VALUES ('$id', '${scope.parentId}', '$key', 'v', false, false, $deleted, $at)",
        )
        return id
    }

    private fun Connection.rowExists(scope: Scope, id: UUID) = exists("SELECT 1 FROM ${scope.table} WHERE id = '$id'")

    private fun Connection.agent(slug: String, deleted: Boolean): Long = (one(
        "INSERT INTO probe_agents (slug, label, agent_uri, public_key, last_ping, last_status, last_ping_delay_ms, last_pong_delta_ms, deleted, is_active) " +
            "VALUES ('$slug', '$slug', 'https://$slug', 'k', now(), 'success', 0, 0, $deleted, ${!deleted}) RETURNING id",
    ) as Number).toLong()

    private fun Connection.slugOf(id: Long) = one("SELECT slug FROM probe_agents WHERE id = $id") as String

    private fun Connection.fails(sql: String): Boolean = runCatching { run(sql) }.isFailure

    @Test
    fun `uniqueness moves to live rows, and the undo keeps one row per name`() {
        flyway(PREVIOUS).migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
            val user = UUID.randomUUID()
            val org = UUID.randomUUID()
            val ws = UUID.randomUUID()
            val proj = UUID.randomUUID()
            val svc = UUID.randomUUID()
            db.run("INSERT INTO users (id, email, password_hash, display_name) VALUES ('$user', 'names@tracedown.dev', 'x', 'Names')")
            db.run("INSERT INTO organizations (id, name, owner_id) VALUES ('$org', 'Names Org', '$user')")
            db.run("INSERT INTO workspaces (id, organization_id, name) VALUES ('$ws', '$org', 'WS')")
            db.run("INSERT INTO projects (id, workspace_id, name) VALUES ('$proj', '$ws', 'Project')")
            db.run("INSERT INTO services (id, project_id, name) VALUES ('$svc', '$proj', 'Service')")

            val scopes = listOf(
                Scope("org_variables", "organization_id", org),
                Scope("workspace_variables", "workspace_id", ws),
                Scope("project_variables", "project_id", proj),
                Scope("service_variables", "service_id", svc),
            )

            // What a deployed database holds: a deleted variable still holding
            // its key, which blocks re-creating it.
            val heldBefore = scopes.associateWith { db.variable(it, "HELD", deleted = true, deletedAt = "2026-01-01 00:00:00") }
            for (scope in scopes) {
                assertTrue(db.fails(
                    "INSERT INTO ${scope.table} (id, ${scope.parentColumn}, key, value, secret, encrypted) " +
                        "VALUES ('${UUID.randomUUID()}', '${scope.parentId}', 'HELD', 'v', false, false)",
                ), "${scope.table}: the old constraint blocks the key")
            }
            val oldAgent = db.agent("same-slug", deleted = true)

            flyway(UNDER_TEST).migrate()

            for (scope in scopes) {
                val t = scope.table
                assertTrue(!db.hasConstraint(scope.constraint), t)
                assertTrue(db.hasIndex("ux_${t}_live_key"), t)
                assertTrue(db.hasIndex("idx_${t}_${scope.parentColumn}"), t)
                // The deleted row is untouched, and its key can be created again…
                assertEquals("HELD", db.one("SELECT key FROM $t WHERE id = '${heldBefore.getValue(scope)}'"), t)
                db.variable(scope, "HELD", deleted = false)
                // …once: two live rows of one key are still refused.
                assertTrue(db.fails(
                    "INSERT INTO $t (id, ${scope.parentColumn}, key, value, secret, encrypted) " +
                        "VALUES ('${UUID.randomUUID()}', '${scope.parentId}', 'HELD', 'v', false, false)",
                ), "$t: two live rows of one key")
            }
            assertTrue(db.hasIndex("ux_probe_agents_live_slug"))
            assertTrue(!db.hasConstraint("probe_agents_slug_key"))
            val liveAgent = db.agent("same-slug", deleted = false)
            assertTrue(db.fails(
                "INSERT INTO probe_agents (slug, label, agent_uri, public_key, last_ping, last_status, last_ping_delay_ms, last_pong_delta_ms) " +
                    "VALUES ('same-slug', 'x', 'https://x', 'k', now(), 'success', 0, 0)",
            ))
            val newerDeletedAgent = db.agent("same-slug", deleted = true)
            // A slug at the full width: the old form has to cut it to fit.
            val longSlug = "s".repeat(64)
            db.agent(longSlug, deleted = false)
            val longDeleted = db.agent(longSlug, deleted = true)

            // ── Undo ──
            // Ids are chosen so that id order and deletion order disagree:
            // the older deletion has the larger id, so only a ranking on the
            // deletion date keeps the right row.
            val small = UUID.fromString("00000000-0000-0000-0000-000000000001")
            val large = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
            val seeded = scopes.associateWith { scope ->
                // TWICE: two deleted rows, no live one. The later deletion stays.
                val newer = db.variable(scope, "TWICE", deleted = true, deletedAt = "2026-03-01 00:00:00", id = small)
                val older = db.variable(scope, "TWICE", deleted = true, deletedAt = "2026-02-01 00:00:00", id = large)
                // ONCE: one deleted row and nothing else. It stays.
                val alone = db.variable(scope, "ONCE", deleted = true, deletedAt = "2026-02-01 00:00:00")
                listOf(newer, older, alone)
            }

            db.run(script("U${UNDER_TEST}__free_deleted_names.sql"))

            for (scope in scopes) {
                val t = scope.table
                val (newer, older, alone) = seeded.getValue(scope)
                // HELD: the live row keeps it, the deleted one is erased.
                assertEquals(1L, db.one("SELECT count(*) FROM $t WHERE key = 'HELD'"), t)
                assertEquals(false, db.one("SELECT deleted FROM $t WHERE key = 'HELD'"), t)
                assertTrue(!db.rowExists(scope, heldBefore.getValue(scope)), t)
                // TWICE: the later deletion stays, though its id is the smaller.
                assertTrue(db.rowExists(scope, newer), t)
                assertTrue(!db.rowExists(scope, older), t)
                assertTrue(db.rowExists(scope, alone), t)
                assertTrue(db.hasConstraint(scope.constraint), t)
                assertTrue(!db.hasIndex("ux_${t}_live_key") && !db.hasIndex("idx_${t}_${scope.parentColumn}"), t)
            }

            // Agents are renamed, not erased: the live one keeps the slug.
            assertEquals("same-slug", db.slugOf(liveAgent))
            assertEquals("same-slug-deleted-$oldAgent", db.slugOf(oldAgent))
            assertEquals("same-slug-deleted-$newerDeletedAgent", db.slugOf(newerDeletedAgent))
            val cut = db.slugOf(longDeleted)
            assertTrue(cut.length <= 64 && cut.endsWith("-deleted-$longDeleted"), cut)
            assertEquals(longSlug, db.one("SELECT slug FROM probe_agents WHERE deleted = false AND slug LIKE 'sss%'"))
            assertTrue(db.hasConstraint("probe_agents_slug_key"))
            assertTrue(!db.hasIndex("ux_probe_agents_live_slug"))
        }
    }
}
