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
 * The migration that makes a webhook bindable to a resource once, against a
 * database that already holds the duplicates it exists to stop.
 *
 * Plain JDBC: half of this happens before the migration.
 */
@Testcontainers
class WebhookBindingMigrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_binding_migration_test")
            .withUsername("test")
            .withPassword("test")

        /** The migration immediately before the one under test. */
        private const val PREVIOUS = "1791183961"
        private const val UNDER_TEST = "1791184021"
    }

    private fun flyway(target: String) = Flyway.configure()
        .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        .locations("classpath:db/initial_schema", "classpath:db/migrations")
        .baselineOnMigrate(true)
        .target(MigrationVersion.fromVersion(target))
        .load()

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.ids(sql: String): List<UUID> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getObject(1) as UUID) } }
    }

    private fun Connection.bind(org: UUID, webhook: UUID, resource: UUID, enabled: Boolean, createdAt: String): UUID {
        val id = UUID.randomUUID()
        run(
            "INSERT INTO resource_webhook_access (id, org_id, resource_type, resource_id, webhook_delivery_id, enabled, created_at) " +
                "VALUES ('$id', '$org', 'service', '$resource', '$webhook', $enabled, '$createdAt')",
        )
        return id
    }

    @Test
    fun `duplicates are reduced to one binding, an enabled one when there is one`() {
        flyway(PREVIOUS).migrate()
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { db ->
            val user = UUID.randomUUID()
            val org = UUID.randomUUID()
            val webhook = UUID.randomUUID()
            db.run("INSERT INTO users (id, email, password_hash, display_name) VALUES ('$user', 'bind@tracedown.dev', '', 'Bind')")
            db.run("INSERT INTO organizations (id, name, owner_id) VALUES ('$org', 'Binding Org', '$user')")
            db.run(
                "INSERT INTO webhook_deliveries (id, organization_id, name, url) " +
                    "VALUES ('$webhook', '$org', 'Hook', 'https://example.com/hook')",
            )

            // An older, paused copy and a newer one that was firing.
            val firing = UUID.randomUUID()
            db.bind(org, webhook, firing, enabled = false, createdAt = "2026-01-01 00:00:00")
            val enabledCopy = db.bind(org, webhook, firing, enabled = true, createdAt = "2026-02-01 00:00:00")
            // Two paused copies: the older stays.
            val paused = UUID.randomUUID()
            val olderPaused = db.bind(org, webhook, paused, enabled = false, createdAt = "2026-01-01 00:00:00")
            db.bind(org, webhook, paused, enabled = false, createdAt = "2026-03-01 00:00:00")
            // A binding with no duplicate is untouched.
            val single = db.bind(org, webhook, UUID.randomUUID(), enabled = true, createdAt = "2026-01-01 00:00:00")

            flyway(UNDER_TEST).migrate()

            assertEquals(listOf(enabledCopy), db.ids("SELECT id FROM resource_webhook_access WHERE resource_id = '$firing'"))
            assertEquals(listOf(olderPaused), db.ids("SELECT id FROM resource_webhook_access WHERE resource_id = '$paused'"))
            assertEquals(1, db.ids("SELECT id FROM resource_webhook_access WHERE id = '$single'").size)

            val duplicate = runCatching { db.bind(org, webhook, firing, enabled = true, createdAt = "2026-04-01 00:00:00") }
            assertTrue(duplicate.isFailure, "The index refuses a second binding of the same webhook to the same resource")
        }
    }
}
