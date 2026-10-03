package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.errors.ErrorCodes
import dev.tracedown.common.models.SessionStatus
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.PasswordResetTokens
import dev.tracedown.common.email.EmailPublisher
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.AccountService
import dev.tracedown.common.onboarding.PasswordHasher
import dev.tracedown.gateway.controllers.auth.AuthController
import dev.tracedown.gateway.data.auth.LoginRequest
import dev.tracedown.gateway.util.ApiException
import dev.tracedown.gateway.util.PasswordPolicyConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID

/**
 * An account may exist without a password (`password_hash IS NULL`). These pin
 * what that state means everywhere a password is read: credentials never sign
 * it in, nothing that re-verifies a password lets it through, and a sign-in
 * whose identity was established some other way still meets the second factor.
 */
@Testcontainers
class PasswordlessAccountTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_passwordless_test")
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

            AuthController.init("0".repeat(64))
        }

        private val policy = PasswordPolicyConfig(minLength = 8, minUppercase = 0, minDigits = 0, minSpecial = 0)
    }

    private fun newAccount(): Pair<UUID, String> {
        val email = "nopw-${UUID.randomUUID()}@t.dev"
        return AccountService.createUser(email, password = null, displayName = "No Password") to email
    }

    private fun row(userId: UUID): ResultRow = transaction {
        Users.selectAll().where { Users.id eq userId }.first()
    }

    private fun codeOf(block: () -> Unit): String =
        assertThrows(ApiException::class.java) { block() }.code

    @Test
    fun `an account created without a password stores no hash and is not an invite stub`() {
        val (userId, _) = newAccount()
        val user = row(userId)

        assertNull(user[Users.passwordHash])
        assertFalse(Users.hasPassword(user))
        assertFalse(Users.isUnclaimedStub(user), "null is a real account; only the empty hash is a stub")
    }

    @Test
    fun `an invite stub neither has a password nor is a real account`() {
        val (userId, _) = newAccount()
        transaction { Users.update({ Users.id eq userId }) { it[passwordHash] = "" } }
        val stub = row(userId)

        assertTrue(Users.isUnclaimedStub(stub))
        assertFalse(Users.hasPassword(stub), "an empty hash is nothing to sign in with or re-verify")
    }

    @Test
    fun `the second factor alone is verifiable for an account that has no password`() {
        val (plain, _) = newAccount()
        assertFalse(AuthController.verifySecondFactor(plain, null), "none enrolled: nothing to verify")

        val (enrolled, _) = newAccount()
        val secret = ByteArray(20) { it.toByte() }
        val (encrypted, iv) = dev.tracedown.gateway.controllers.auth.TotpUtil.encryptSecret(secret, ByteArray(32))
        transaction {
            Users.update({ Users.id eq enrolled }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = encrypted
                it[totpSecretIv] = iv
            }
        }

        assertEquals(ErrorCodes.INVALID_TOTP_CODE, codeOf { AuthController.verifySecondFactor(enrolled, null) })
        assertTrue(
            AuthController.verifySecondFactor(enrolled, dev.tracedown.gateway.controllers.auth.TotpUtil.generateCode(secret)),
            "the right code passes without any password",
        )
    }

    @Test
    fun `credentials never sign in an account that has no password`() {
        val (_, email) = newAccount()

        assertEquals(ErrorCodes.INVALID_CREDENTIALS, codeOf {
            AuthController.login(LoginRequest(email, "anything-at-all"), 60, null, null)
        })
    }

    @Test
    fun `a sign-in established elsewhere gets a session and reports no password`() {
        val (userId, _) = newAccount()

        val resp = AuthController.completeSignIn(row(userId), 60, "203.0.113.7", "test")

        assertNotNull(resp.token)
        assertFalse(resp.user!!.hasPassword)
    }

    @Test
    fun `a sign-in established elsewhere still meets an enrolled second factor`() {
        val (userId, _) = newAccount()
        transaction {
            Users.update({ Users.id eq userId }) {
                it[totpEnabled] = true
                it[totpSecretEncrypted] = "x"
                it[totpSecretIv] = "x"
            }
        }

        val resp = AuthController.completeSignIn(row(userId), 60, null, null)

        assertNull(resp.token, "no session before the second factor")
        assertTrue(resp.totpRequired)
        transaction {
            val pending = Sessions.selectAll()
                .where { Sessions.id eq UUID.fromString(resp.challenge!!) }
                .first()
            assertEquals(SessionStatus.PENDING_TOTP, pending[Sessions.status])
            assertNull(pending[Sessions.sessionTokenHash])
        }
    }

    @Test
    fun `a deactivated account is refused however its identity was established`() {
        val (userId, _) = newAccount()
        transaction { Users.update({ Users.id eq userId }) { it[isActive] = false } }

        assertEquals(ErrorCodes.ACCOUNT_DEACTIVATED, codeOf {
            AuthController.completeSignIn(row(userId), 60, null, null)
        })
    }

    @Test
    fun `password re-verification answers that there is no password`() {
        val (userId, _) = newAccount()

        assertEquals(ErrorCodes.PASSWORD_NOT_SET, codeOf {
            AuthController.verifyIdentity(userId, "anything-at-all", null)
        })
        assertEquals(ErrorCodes.PASSWORD_NOT_SET, codeOf {
            AuthController.changePassword(userId, "anything-at-all", "a-new-password", policy)
        })
        assertNull(row(userId)[Users.passwordHash], "nothing was set on a session's say-so")
    }

    @Test
    fun `a reset link is never the way to claim an unclaimed row`() {
        // An unclaimed row — nobody's yet — gets no reset link: the mailbox's
        // owner has a signup or an invitation for that, which also records who
        // agreed to what. (An account with no password at all is a real account
        // and may reset; that is how it gets one.)
        val stubEmail = "stub-${UUID.randomUUID()}@t.dev"
        val stubId = AccountService.createUser(stubEmail, password = null, displayName = "stub", unclaimed = true)
        val publisher = EmailPublisher { throw IllegalStateException("no mail may be sent for an unclaimed row") }

        AuthController.requestPasswordReset(stubEmail, publisher, { "https://app/reset/$it" }, 60)

        assertEquals(0L, transaction { PasswordResetTokens.selectAll().where { PasswordResetTokens.userId eq stubId }.count() })
        assertEquals("", row(stubId)[Users.passwordHash], "still unclaimed")
    }

    @Test
    fun `once a password is set the account behaves like any other`() {
        val (userId, email) = newAccount()
        transaction {
            Users.update({ Users.id eq userId }) { it[passwordHash] = PasswordHasher.hash("a-real-password") }
        }

        val resp = AuthController.login(LoginRequest(email, "a-real-password"), 60, null, null)

        assertNotNull(resp.token)
        assertTrue(resp.user!!.hasPassword)
        AuthController.verifyIdentity(userId, "a-real-password", null)
    }
}
