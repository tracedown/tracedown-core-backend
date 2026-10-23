package dev.tracedown.worker.jobs

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.tracedown.common.models.Outbox
import dev.tracedown.common.models.OutboxRetention
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteAll
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * The outbox purge and the mark it leaves for the event feed: how far it has
 * deleted, in the order the feed reads (xid, then seq), and a row's age taken
 * from when it was written rather than from the run it describes.
 */
@Testcontainers
class OutboxPurgeMarkTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18-alpine")
            .withDatabaseName("tracedown_outbox_purge_mark_test")
            .withUsername("test")
            .withPassword("test")

        @BeforeAll
        @JvmStatic
        fun setup() {
            Flyway.configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .locations("classpath:db/initial_schema", "classpath:db/migrations")
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

    @BeforeEach
    fun clean() {
        transaction {
            Outbox.deleteAll()
            OutboxRetention.update({ OutboxRetention.id eq 1 }) {
                it[purgedXid] = 0
                it[purgedSeq] = 0
            }
        }
    }

    /** A row of [type] whose run was [createdAt], written [writtenDaysAgo] days ago. */
    private fun row(type: String, createdAt: Instant, writtenDaysAgo: Long?, published: Boolean = false): UUID {
        val id = UUID.randomUUID()
        transaction {
            Outbox.insert {
                it[Outbox.id] = id
                it[aggregateType] = "x"
                it[aggregateId] = UUID.randomUUID()
                it[eventType] = type
                it[payload] = buildJsonObject { put("orgId", UUID.randomUUID().toString()) }
                it[Outbox.published] = published
                it[Outbox.createdAt] = createdAt
            }
            if (writtenDaysAgo != null) {
                exec("UPDATE outbox SET inserted_at = now() - interval '$writtenDaysAgo days' WHERE id = '$id'")
            }
        }
        return id
    }

    private fun mark(): Pair<Long, Long> = transaction {
        OutboxRetention.selectAll().where { OutboxRetention.id eq 1 }.single()
            .let { it[OutboxRetention.purgedXid] to it[OutboxRetention.purgedSeq] }
    }

    private fun position(id: UUID): Pair<Long, Long> = transaction {
        Outbox.selectAll().where { Outbox.id eq id }.single().let { it[Outbox.xid]!! to it[Outbox.seq] }
    }

    private fun present(): Set<UUID> = transaction { Outbox.selectAll().map { it[Outbox.id] }.toSet() }

    @Test
    fun `the purge moves the mark to the last row it deleted, and only forward`() = runBlocking {
        val old = Instant.now().minus(30, ChronoUnit.DAYS)
        val first = row("resource.service.updated", old, writtenDaysAgo = 30)
        val second = row("resource.service.updated", old, writtenDaysAgo = 30)
        val kept = row("resource.service.updated", Instant.now(), writtenDaysAgo = null)
        val last = listOf(position(first), position(second)).maxWith(compareBy({ it.first }, { it.second }))

        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(setOf(kept), present())
        assertEquals(last, mark())

        // A purge that deletes nothing leaves it where it was.
        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(last, mark())
    }

    @Test
    fun `a result recorded late is aged from when it was written, not from its run`() = runBlocking {
        // A run from 30 days ago, recorded just now: not purgeable yet.
        val late = row("resource.service.updated", Instant.now().minus(30, ChronoUnit.DAYS), writtenDaysAgo = null)
        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(setOf(late), present())
        assertEquals(0L to 0L, mark())
    }

    @Test
    fun `the mark never moves back`() = runBlocking {
        transaction {
            OutboxRetention.update({ OutboxRetention.id eq 1 }) {
                it[purgedXid] = Long.MAX_VALUE / 2
                it[purgedSeq] = 7
            }
        }
        row("resource.service.updated", Instant.now().minus(30, ChronoUnit.DAYS), writtenDaysAgo = 30)
        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(emptySet<UUID>(), present())
        assertEquals(Long.MAX_VALUE / 2 to 7L, mark())
    }

    @Test
    fun `the mark is the last deleted row in the order of transactions, not of numbers`() = runBlocking {
        // The first transaction takes its id before the second, and writes its
        // row after: its seq is the higher, its xid the lower.
        val early = java.sql.DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
        val earlyId = UUID.randomUUID()
        try {
            early.autoCommit = false
            early.prepareStatement("SELECT pg_current_xact_id()").use { it.executeQuery().close() }
            val later = row("resource.service.updated", Instant.now().minus(30, ChronoUnit.DAYS), writtenDaysAgo = 30)
            early.prepareStatement(
                "INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, published, created_at, inserted_at) " +
                    "VALUES (?, 'x', ?, 'resource.service.updated', '{}'::jsonb, false, now() - interval '30 days', now() - interval '30 days')",
            ).use { stmt ->
                stmt.setObject(1, earlyId)
                stmt.setObject(2, UUID.randomUUID())
                stmt.executeUpdate()
            }
            early.commit()
            val (earlyXid, earlySeq) = position(earlyId)
            val (laterXid, laterSeq) = position(later)
            assertTrue(earlyXid < laterXid && earlySeq > laterSeq, "the setup did not cross the orders")

            OutboxPurgeJob(retentionDays = 7).execute()
            assertEquals(laterXid to laterSeq, mark(), "the row of the later transaction, though its seq is the lower")
        } finally {
            early.close()
        }
    }

    @Test
    fun `a backlog larger than a batch is purged in several, and all of it goes`() = runBlocking {
        transaction {
            exec(
                "INSERT INTO outbox (id, aggregate_type, aggregate_id, event_type, payload, published, created_at, inserted_at) " +
                    "SELECT gen_random_uuid(), 'x', gen_random_uuid(), 'resource.service.updated', '{}'::jsonb, false, " +
                    "now() - interval '30 days', now() - interval '30 days' FROM generate_series(1, 5001)",
            )
        }
        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(emptySet<UUID>(), present())
        val (xid, seq) = mark()
        assertTrue(xid > 0 && seq > 0)
    }

    @Test
    fun `a row of another history cannot drag the mark past the oldest open transaction`() = runBlocking {
        val stray = row("resource.service.updated", Instant.now().minus(30, ChronoUnit.DAYS), writtenDaysAgo = 30)
        transaction { exec("UPDATE outbox SET xid = pg_snapshot_xmax(pg_current_snapshot())::text::bigint + 1000000 WHERE id = '$stray'") }
        OutboxPurgeJob(retentionDays = 7).execute()
        assertEquals(emptySet<UUID>(), present())
        val horizon = transaction {
            var x = 0L
            exec("SELECT pg_snapshot_xmin(pg_current_snapshot())::text::bigint") { rs -> rs.next(); x = rs.getLong(1) }
            x
        }
        assertTrue(mark().first < horizon, "the mark went to ${mark()}, past the horizon $horizon")
    }
}
