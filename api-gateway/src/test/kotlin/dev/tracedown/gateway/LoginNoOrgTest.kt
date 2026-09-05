package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.PasswordHasher
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.auth.LoginRequest
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * A user with no active organization may now sign in — they land on the app's
 * "no organizations" screen instead of being blocked. This asserts login returns
 * a real (org-less) session for such an account.
 */
@Testcontainers
class LoginNoOrgTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_login_noorg_test")
            .withUsername("test")
            .withPassword("test")

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

            // AES/HMAC key only used on the TOTP path; a dummy is fine here.
            AuthController.init("0".repeat(64))
        }
    }

    @Test
    fun `login succeeds for an active account with no organization`() {
        val email = "noorg-${UUID.randomUUID()}@t.dev"
        transaction {
            Users.insert {
                it[id] = UUID.randomUUID()
                it[Users.email] = email
                it[passwordHash] = PasswordHasher.hash("password123")
                it[displayName] = "No Org"
                it[isActive] = true
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
        }

        val resp = AuthController.login(LoginRequest(email, "password123"), 60, null, null)

        // A real session is issued (no TOTP challenge, no setup requirement)...
        assertNotNull(resp.token, "an org-less account still gets a session")
        // ...and it carries no organization context.
        transaction {
            val session = Sessions.selectAll()
                .where { Sessions.sessionTokenHash eq dev.tracedown.common.auth.TokenHasher.sha256Hex(resp.token!!) }
                .first()
            assertNull(session[Sessions.organizationId], "the session has no org")
        }
    }
}
