package dev.tracedown.worker.jobs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.OrgUsers
import dev.tracedown.common.models.Organizations
import dev.tracedown.common.models.Projects
import dev.tracedown.common.models.Services
import dev.tracedown.common.models.Users
import dev.tracedown.common.models.Workspaces
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The upgrade repair: rows soft-deleted before every delete path stamped a
 * purge date get one now, from the operator's retention.
 *
 * These are the two shapes a deployed database holds. A row with
 * `purge_after IS NULL` was deleted by a path that never set the column, and
 * [PurgeJob] reads that column and nothing else, so it was never going to be
 * erased. A row whose `purge_after` equals its `deleted_at` was deleted by a
 * path that stamped the deletion instant and ignored the retention, so on an
 * install that keeps deleted data it is due immediately.
 *
 * Both get `deleted_at + retention`. Neither is ever brought *forward*.
 */
@Testcontainers
class PurgeScheduleRepairTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_purge_repair_test")
            .withUsername("test")
            .withPassword("test")

        private val NOW: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)
        private val LONG_AGO: Instant = NOW.minus(200, ChronoUnit.DAYS)

        private const val RETENTION_DAYS = 30
        private val RETENTION_WINDOW: Duration = Duration.ofDays(RETENTION_DAYS.toLong())

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
        }
    }

    private fun repair(retentionDays: Int): Long =
        transaction { with(PurgeScheduleRepair) { repair(retentionDays) } }

    // ── Fixtures ──

    private fun seedUser(): UUID = transaction {
        val id = UUID.randomUUID()
        Users.insert {
            it[Users.id] = id
            it[email] = "repair-$id@t.dev"
            it[passwordHash] = "x"
            it[displayName] = "u"
            it[createdAt] = LONG_AGO
        }
        id
    }

    private fun seedOrg(): UUID = transaction {
        val id = UUID.randomUUID()
        Organizations.insert {
            it[Organizations.id] = id
            it[name] = "org-${id.toString().take(8)}"
            it[ownerId] = seedUser()
            it[createdAt] = LONG_AGO
        }
        id
    }

    /** A service soft-deleted long ago with the given purge date (null = none). */
    private fun seedDeletedService(purge: Instant?, deletedAt: Instant = LONG_AGO): UUID = transaction {
        val workspaceId = UUID.randomUUID()
        Workspaces.insert {
            it[Workspaces.id] = workspaceId
            it[organizationId] = seedOrg()
            it[name] = "ws-${workspaceId.toString().take(8)}"
            it[createdAt] = LONG_AGO
        }
        val projectId = UUID.randomUUID()
        Projects.insert {
            it[Projects.id] = projectId
            it[Projects.workspaceId] = workspaceId
            it[name] = "proj-${projectId.toString().take(8)}"
            it[createdAt] = LONG_AGO
        }
        val id = UUID.randomUUID()
        Services.insert {
            it[Services.id] = id
            it[Services.projectId] = projectId
            it[name] = "svc-${id.toString().take(8)}"
            it[createdAt] = LONG_AGO
            it[deleted] = true
            it[Services.deletedAt] = deletedAt
            it[purgeAfter] = purge
        }
        id
    }

    private fun serviceRow(id: UUID) = transaction {
        Services.selectAll().where { Services.id eq id }.single()
    }

    // ── The two shapes ──

    @Test
    fun `a soft-deleted row with no purge date gets one from the retention`() {
        val serviceId = seedDeletedService(purge = null)

        repair(RETENTION_DAYS)

        assertEquals(
            LONG_AGO.plus(RETENTION_WINDOW), serviceRow(serviceId)[Services.purgeAfter],
            "measured from when it was deleted, not from the upgrade",
        )
    }

    @Test
    fun `a row stamped with the deletion instant is pushed out to the retention`() {
        val serviceId = seedDeletedService(purge = LONG_AGO)

        repair(RETENTION_DAYS)

        assertEquals(
            LONG_AGO.plus(RETENTION_WINDOW), serviceRow(serviceId)[Services.purgeAfter],
            "a delete that ignored the retention scheduled this row for erasure at once",
        )
    }

    @Test
    fun `a row already on the rule is left exactly where it is`() {
        val alreadyRight = LONG_AGO.plus(RETENTION_WINDOW)
        val serviceId = seedDeletedService(purge = alreadyRight)

        repair(RETENTION_DAYS)

        assertEquals(alreadyRight, serviceRow(serviceId)[Services.purgeAfter])
    }

    @Test
    fun `a purge date an operator pushed further out is not pulled back`() {
        val farOut = NOW.plus(900, ChronoUnit.DAYS)
        val serviceId = seedDeletedService(purge = farOut)

        repair(RETENTION_DAYS)

        assertEquals(farOut, serviceRow(serviceId)[Services.purgeAfter], "the repair never erases sooner")
    }

    @Test
    fun `a live row is never given a purge date`() {
        val workspaceId = UUID.randomUUID()
        transaction {
            Workspaces.insert {
                it[Workspaces.id] = workspaceId
                it[organizationId] = seedOrg()
                it[name] = "live-ws"
                it[createdAt] = LONG_AGO
            }
        }

        repair(RETENTION_DAYS)

        val row = transaction { Workspaces.selectAll().where { Workspaces.id eq workspaceId }.single() }
        assertNull(row[Workspaces.purgeAfter], "nothing about a live row is deleted")
    }

    @Test
    fun `with no retention configured the rule is the deletion instant itself`() {
        val serviceId = seedDeletedService(purge = null)

        repair(0)

        assertEquals(
            LONG_AGO, serviceRow(serviceId)[Services.purgeAfter],
            "a self-hosted install's default is that deleted means purgeable at once",
        )
    }

    @Test
    fun `with no retention configured a row already stamped at the deletion instant is untouched`() {
        val serviceId = seedDeletedService(purge = LONG_AGO)

        assertEquals(0L, repair(0), "nothing to repair: the stamp already is the rule")
        assertEquals(LONG_AGO, serviceRow(serviceId)[Services.purgeAfter])
    }

    // ── Idempotency ──

    @Test
    fun `running the repair twice changes nothing the second time`() {
        val serviceId = seedDeletedService(purge = null)

        val first = repair(RETENTION_DAYS)
        val after = serviceRow(serviceId)[Services.purgeAfter]
        val second = repair(RETENTION_DAYS)

        assertTrue(first > 0, "the first pass had something to do")
        assertEquals(0L, second, "the second pass must find nothing")
        assertEquals(after, serviceRow(serviceId)[Services.purgeAfter])
    }

    // ── Coverage ──

    @Test
    fun `the repair reaches every table that carries the three deletion columns`() {
        // Deliberately spread across tables owned by different modules'
        // migrations, and including one the repair has no hand-written
        // knowledge of.
        val orgId = seedOrg()
        val userId = seedUser()
        val membershipId = UUID.randomUUID()
        transaction {
            OrgUsers.insert {
                it[OrgUsers.id] = membershipId
                it[organizationId] = orgId
                it[OrgUsers.userId] = userId
                it[status] = "active"
                it[inviteToken] = "t-$membershipId"
                it[deleted] = true
                it[deletedAt] = LONG_AGO
            }
            Users.update({ Users.id eq userId }) {
                it[deleted] = true
                it[deletedAt] = LONG_AGO
            }
            Organizations.update({ Organizations.id eq orgId }) {
                it[deleted] = true
                it[deletedAt] = LONG_AGO
            }
        }

        repair(RETENTION_DAYS)

        val expected = LONG_AGO.plus(RETENTION_WINDOW)
        assertEquals(expected, transaction {
            OrgUsers.selectAll().where { OrgUsers.id eq membershipId }.single()[OrgUsers.purgeAfter]
        })
        assertEquals(expected, transaction {
            Users.selectAll().where { Users.id eq userId }.single()[Users.purgeAfter]
        })
        assertEquals(expected, transaction {
            Organizations.selectAll().where { Organizations.id eq orgId }.single()[Organizations.purgeAfter]
        })

        // And nothing anywhere is left unscheduled.
        val unscheduled = transaction {
            val tables = mutableListOf<String>()
            exec(
                """
                SELECT table_name FROM information_schema.columns
                 WHERE table_schema = current_schema()
                   AND column_name IN ('deleted', 'deleted_at', 'purge_after')
                 GROUP BY table_name HAVING count(DISTINCT column_name) = 3
                """
            ) { rs -> while (rs.next()) tables.add(rs.getString(1)) }

            assertTrue(tables.size >= 17, "expected the three-tier tables to be found, got $tables")

            tables.filter { table ->
                var any = false
                exec("SELECT count(*) FROM $table WHERE deleted = true AND purge_after IS NULL") { rs ->
                    if (rs.next()) any = rs.getLong(1) > 0
                }
                any
            }
        }
        assertTrue(unscheduled.isEmpty(), "still unscheduled after the repair: $unscheduled")
    }
}
