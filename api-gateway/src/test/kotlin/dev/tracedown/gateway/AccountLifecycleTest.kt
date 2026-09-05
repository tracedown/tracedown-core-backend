package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.NotificationSilences
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Sessions
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.AccountLifecycle
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Direct-DB tests for the account/membership lifecycle rules
 * ([AccountLifecycle]) — no HTTP layer. Uses an explicit [Database] handle so it
 * never collides with the default connection other test classes open.
 */
@Testcontainers
class AccountLifecycleTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_lifecycle_test")
            .withUsername("test")
            .withPassword("test")

        private lateinit var db: Database

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
                .baselineOnMigrate(true)
                .load()
                .migrate()

            db = Database.connect(HikariDataSource(HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                driverClassName = "org.postgresql.Driver"
            }))
        }

        private fun insertUser(email: String, createdAt: Instant = Instant.now()): UUID {
            val id = UUID.randomUUID()
            Users.insert {
                it[Users.id] = id
                it[Users.email] = email
                it[passwordHash] = "x"
                it[displayName] = email.substringBefore("@")
                it[deleted] = false
                it[Users.createdAt] = createdAt
            }
            return id
        }

        private fun insertOrg(ownerId: UUID): UUID {
            val id = UUID.randomUUID()
            Organizations.insert {
                it[Organizations.id] = id
                it[name] = "Org ${id.toString().take(6)}"
                it[Organizations.ownerId] = ownerId
                it[deleted] = false
                it[createdAt] = Instant.now()
            }
            return id
        }

        private fun insertMembership(orgId: UUID, userId: UUID, status: String = "active", deleted: Boolean = false): UUID {
            val orgUserId = UUID.randomUUID()
            OrgUsers.insert {
                it[id] = orgUserId
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[OrgUsers.status] = status
                it[isActive] = status == "active"
                it[OrgUsers.deleted] = deleted
                it[inviteToken] = ""
            }
            return orgUserId
        }

        private fun insertSilence(orgUserId: UUID): UUID {
            val id = UUID.randomUUID()
            NotificationSilences.insert {
                it[NotificationSilences.id] = id
                it[NotificationSilences.orgUserId] = orgUserId
                it[channel] = "email"
            }
            return id
        }

        private fun silenceCount(orgUserId: UUID): Long =
            NotificationSilences.selectAll().where { NotificationSilences.orgUserId eq orgUserId }.count()

        private fun insertSession(userId: UUID) {
            val now = Instant.now()
            Sessions.insert {
                it[id] = UUID.randomUUID()
                it[Sessions.userId] = userId
                it[sessionTokenHash] = UUID.randomUUID().toString()
                it[expiresAt] = now.plusSeconds(3600)
                it[lastActiveAt] = now
                it[createdAt] = now
            }
        }

        private fun sessionCount(userId: UUID): Long =
            Sessions.selectAll().where { Sessions.userId eq userId }.count()

        private fun isDeleted(userId: UUID): Boolean =
            Users.selectAll().where { Users.id eq userId }.first()[Users.deleted]
    }

    @Test
    fun `reconcile keeps an account that still has a membership`() = transaction(db) {
        val uid = insertUser("keep-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        insertMembership(org, uid, status = "active")

        AccountLifecycle.reconcile(uid)

        assertFalse(isDeleted(uid), "account with an active membership must be kept")
    }

    @Test
    fun `reconcile keeps an account whose only membership is inactive`() = transaction(db) {
        val uid = insertUser("inactive-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        // A pending invite is a non-deleted but inactive assignment.
        insertMembership(org, uid, status = "invited")

        AccountLifecycle.reconcile(uid)

        assertFalse(isDeleted(uid), "an inactive (pending) membership still keeps the account")
        assertFalse(AccountLifecycle.hasActiveMembership(uid), "but it does not count as active for sign-in")
        assertTrue(AccountLifecycle.hasAnyMembership(uid))
    }

    @Test
    fun `reconcile soft-deletes an account with no remaining membership`() = transaction(db) {
        val uid = insertUser("orphan-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        insertMembership(org, uid, status = "active", deleted = true) // removed

        AccountLifecycle.reconcile(uid)

        assertTrue(isDeleted(uid), "account with no non-deleted membership must be scheduled for deletion")
    }

    @Test
    fun `soft-delete hard-deletes the account's notification silences`() = transaction(db) {
        val uid = insertUser("silence-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        // Removed from its only org, but the silence it created still points at
        // the (now soft-deleted) membership.
        val orgUserId = insertMembership(org, uid, status = "active", deleted = true)
        insertSilence(orgUserId)
        assertEquals(1L, silenceCount(orgUserId))

        AccountLifecycle.reconcile(uid)

        assertTrue(isDeleted(uid))
        assertEquals(0L, silenceCount(orgUserId), "the removed account's notification silences are hard-deleted")
    }

    @Test
    fun `soft-delete immediately kills the account's live sessions`() = transaction(db) {
        val uid = insertUser("session-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        insertMembership(org, uid, status = "active", deleted = true) // removed from only org
        insertSession(uid)
        insertSession(uid)
        assertEquals(2L, sessionCount(uid))

        AccountLifecycle.reconcile(uid)

        assertTrue(isDeleted(uid))
        assertEquals(0L, sessionCount(uid), "a removed account's live sessions are revoked at removal time")
    }

    @Test
    fun `revive resets the account to a brand-new clean stub`() = transaction(db) {
        val uid = insertUser("revive-clean-${UUID.randomUUID()}@t.dev")
        // Give it the prior owner's state: credentials, 2FA, active, plus a session.
        Users.update({ Users.id eq uid }) {
            it[Users.passwordHash] = "oldhash"
            it[Users.totpEnabled] = true
            it[Users.isActive] = true
            it[Users.deleted] = true
            it[Users.deletedAt] = Instant.now()
        }
        insertSession(uid)
        assertEquals(1L, sessionCount(uid))

        AccountLifecycle.revive(uid)

        val u = Users.selectAll().where { Users.id eq uid }.first()
        assertFalse(u[Users.deleted], "revived (un-deleted)")
        assertEquals("", u[Users.passwordHash], "credential wiped — set later at invite acceptance")
        assertFalse(u[Users.totpEnabled], "prior owner's 2FA wiped")
        assertFalse(u[Users.isActive], "inactive stub until acceptance")
        assertEquals(0L, sessionCount(uid), "prior owner's sessions revoked")
    }

    @Test
    fun `reconcile revives a soft-deleted account that regained a membership`() = transaction(db) {
        val uid = insertUser("revive-${UUID.randomUUID()}@t.dev")
        val org = insertOrg(uid)
        insertMembership(org, uid, status = "active", deleted = true)
        AccountLifecycle.reconcile(uid)
        assertTrue(isDeleted(uid))

        // Re-invited: a fresh membership appears.
        insertMembership(insertOrg(uid), uid, status = "invited")
        AccountLifecycle.reconcile(uid)

        assertFalse(isDeleted(uid), "regaining a membership revives the account")
    }

    @Test
    fun `markAbandonedOrphans sweeps only aged orphans`() = transaction(db) {
        val now = Instant.now()
        val aged = insertUser("aged-${UUID.randomUUID()}@t.dev", createdAt = now.minus(8, ChronoUnit.DAYS))
        val fresh = insertUser("fresh-${UUID.randomUUID()}@t.dev", createdAt = now)
        val member = insertUser("member-${UUID.randomUUID()}@t.dev", createdAt = now.minus(8, ChronoUnit.DAYS))
        insertMembership(insertOrg(member), member, status = "active")

        AccountLifecycle.markAbandonedOrphans(now)

        assertTrue(isDeleted(aged), "an aged account with no org is swept")
        assertFalse(isDeleted(fresh), "a fresh signup within the grace window is spared")
        assertFalse(isDeleted(member), "an aged account that has an org is spared")
    }
}
