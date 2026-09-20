package dev.tracedown.gateway

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.interceptors.Interceptors
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Users
import dev.tracedown.common.onboarding.DefaultGroupConfig
import dev.tracedown.common.onboarding.OrgService
import dev.tracedown.gateway.controllers.orgs.OrgSettingsController
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.util.UUID

/**
 * The two interception points through which a user becomes the owner of an
 * organization: `org.create` and `org.ownership.transfer`.
 *
 * Both matter to a host that has its own rules about who may hold an
 * organization — a cap enforced on creation alone is bypassed by transferring
 * one instead — and both run inside the transaction that does the write, so a
 * hook that counts what a user already holds cannot be raced by a second
 * request reading the same pre-write state.
 */
@Testcontainers
class OwnershipInterceptionTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_ownership_test")
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
    }

    @AfterEach
    fun clearHooks() = Interceptors.clearAll()

    private fun insertUser(): UUID = transaction(db) {
        val id = UUID.randomUUID()
        Users.insert {
            it[Users.id] = id
            it[email] = "u-${id.toString().take(8)}@example.test"
            it[passwordHash] = "x"
            it[displayName] = "U"
            it[deleted] = false
            it[createdAt] = Instant.now()
        }
        id
    }

    private fun groups() = listOf(
        DefaultGroupConfig("Admins", 2, 2, 2, 2, 2, 2, 2),
    )

    private fun ownerOf(orgId: UUID): UUID = transaction(db) {
        Organizations.selectAll().where { Organizations.id eq orgId }.first()[Organizations.ownerId]
    }

    /** A hook that refuses stops the organization from being created at all. */
    @Test
    fun `a before-hook can refuse org creation`() {
        val userId = insertUser()
        Interceptors.before("org.create") { throw IllegalStateException("refused") }

        assertThrows<IllegalStateException> {
            transaction(db) { OrgService.createOrg("Refused", userId, groups()) }
        }

        val any = transaction(db) {
            Organizations.selectAll().where { Organizations.ownerId eq userId }.empty()
        }
        assertTrue(any, "no organization should have been written")
    }

    /**
     * The hook's read has to be able to see — and be serialized against — the
     * insert it guards, which is only true if it runs in the same transaction.
     */
    @Test
    fun `the org-create hook runs inside the creating transaction`() {
        val userId = insertUser()
        var sawTransaction = false
        var countedBefore = -1L
        Interceptors.before("org.create") {
            sawTransaction = TransactionManager.currentOrNull() != null
            countedBefore = Organizations.selectAll()
                .where { (Organizations.ownerId eq it.userId!!) and (Organizations.deleted eq false) }
                .count()
        }

        transaction(db) { OrgService.createOrg("First", userId, groups()) }
        assertTrue(sawTransaction, "the hook must run inside a transaction")
        assertEquals(0L, countedBefore)

        transaction(db) { OrgService.createOrg("Second", userId, groups()) }
        assertEquals(1L, countedBefore, "the second hook must see the first org")
    }

    /** With nothing registered, creation is unchanged. */
    @Test
    fun `org creation is unaffected when no hook is registered`() {
        val userId = insertUser()
        val result = transaction(db) { OrgService.createOrg("Plain", userId, groups()) }
        assertEquals(userId, ownerOf(result.orgId))
    }

    /** The transfer hook is handed the org, the caller and the incoming owner. */
    @Test
    fun `a before-hook can refuse an ownership transfer`() {
        val owner = insertUser()
        val member = insertUser()
        val org = transaction(db) { OrgService.createOrg("Transferable", owner, groups()) }
        transaction(db) {
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = org.orgId
                it[userId] = member
                it[joinedAt] = Instant.now()
                it[status] = "active"
                it[deleted] = false
                it[inviteToken] = ""
            }
        }

        var seenOrg: UUID? = null
        var seenCaller: UUID? = null
        var seenNewOwner: Any? = null
        var sawTransaction = false
        Interceptors.before("org.ownership.transfer") { ctx ->
            seenOrg = ctx.orgId
            seenCaller = ctx.userId
            seenNewOwner = ctx.extra["newOwnerId"]
            sawTransaction = TransactionManager.currentOrNull() != null
            throw IllegalStateException("refused")
        }

        assertThrows<IllegalStateException> {
            OrgSettingsController.transferOwnership(org.orgId, member, owner)
        }

        assertEquals(org.orgId, seenOrg)
        assertEquals(owner, seenCaller)
        assertEquals(member, seenNewOwner)
        assertTrue(sawTransaction, "the hook must run inside the transfer's transaction")
        assertEquals(owner, ownerOf(org.orgId), "ownership must not have moved")
    }

    /** A hook that does not throw leaves the transfer to proceed as before. */
    @Test
    fun `a permissive hook leaves the transfer alone`() {
        val owner = insertUser()
        val member = insertUser()
        val org = transaction(db) { OrgService.createOrg("Handover", owner, groups()) }
        transaction(db) {
            OrgUsers.insert {
                it[id] = UUID.randomUUID()
                it[organizationId] = org.orgId
                it[userId] = member
                it[joinedAt] = Instant.now()
                it[status] = "active"
                it[deleted] = false
                it[inviteToken] = ""
            }
        }

        var ran = false
        Interceptors.before("org.ownership.transfer") { ran = true }

        OrgSettingsController.transferOwnership(org.orgId, member, owner)

        assertTrue(ran)
        assertEquals(member, ownerOf(org.orgId))
        assertFalse(owner == ownerOf(org.orgId))
    }
}
