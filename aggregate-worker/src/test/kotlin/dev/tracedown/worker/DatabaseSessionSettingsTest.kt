package dev.tracedown.worker

import dev.tracedown.common.config.DatabaseFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.sql.Connection

/**
 * The pool's sessions carry the idle-in-transaction timeout from the moment
 * they open — a startup parameter, not a statement run on a fresh connection,
 * which with auto-commit off opened a transaction the pool then held idle.
 */
@Testcontainers
class DatabaseSessionSettingsTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_session_settings_test")
            .withUsername("test")
            .withPassword("test")
    }

    @Test
    fun `pooled sessions carry the timeout, sit idle outside a transaction, and keep it past a rollback`() {
        Flyway.configure().dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .locations("classpath:db/initial_schema", "classpath:db/migrations").load().migrate()
        val pool = DatabaseFactory.init(postgres.jdbcUrl, postgres.username, postgres.password, maximumPoolSize = 4)
        try {
            // Open every connection the pool will hold, and give them back.
            val held = (1..4).map { pool.connection }
            held.forEach { it.close() }

            pool.connection.use { first ->
                // Its first transaction rolls back; the setting outlives it.
                first.createStatement().use { it.execute("SELECT 1") }
                first.rollback()
                // The next transaction may still choose its isolation level…
                first.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
                // …and the setting is there in it.
                assertEquals((DatabaseFactory.idleInTransactionTimeoutSeconds * 1000L).toString(), timeoutMillis(first))
                first.createStatement().use { it.executeQuery("SHOW transaction_isolation").use { rs -> rs.next(); assertEquals("read committed", rs.getString(1)) } }
                first.rollback()
            }

            // Nothing in the pool sits idle inside a transaction.
            java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { probe ->
                probe.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() " +
                            "AND pid <> pg_backend_pid() AND state = 'idle in transaction'",
                    ).use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
                }
            }
        } finally {
            pool.close()
        }
    }

    @Test
    fun `options a URL already names are kept, with the timeout added`() {
        val url = "jdbc:postgresql://db/x?options=-c%20search_path%3Dapp&ssl=false"
        val merged = DatabaseFactory.withIdleTimeoutInUrl(url, 60)
        assertEquals(
            "jdbc:postgresql://db/x?options=" +
                java.net.URLEncoder.encode("-c search_path=app -c idle_in_transaction_session_timeout=60s", Charsets.UTF_8) + "&ssl=false",
            merged,
        )
        assertEquals(url, DatabaseFactory.withIdleTimeoutInUrl(url, 0))
    }

    private fun timeoutMillis(connection: Connection): String = connection.createStatement().use { stmt ->
        stmt.executeQuery("SELECT setting FROM pg_settings WHERE name = 'idle_in_transaction_session_timeout'")
            .use { rs -> rs.next(); rs.getString(1) }
    }
}
