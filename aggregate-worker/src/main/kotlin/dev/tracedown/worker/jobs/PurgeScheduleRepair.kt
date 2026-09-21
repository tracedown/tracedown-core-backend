package dev.tracedown.worker.jobs

import dev.tracedown.common.config.DeletionRetention
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.ZoneId

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.PurgeScheduleRepair")

/**
 * Gives a purge date to soft-deleted rows that were left without one.
 *
 * `purge_after` is the only thing [PurgeJob] reads, so a soft-deleted row that
 * never got one is not "deleted with a long window" — it is a row nothing will
 * ever erase. Deployed databases are full of them: for a long time only the
 * organization and account deletes stamped the column, so every deleted
 * service, variable, webhook, domain, key, template, preset and membership went
 * in with `purge_after` NULL and stayed. Rows stamped by a delete that used the
 * deletion instant itself are the same problem seen from the other side: on an
 * install that keeps deleted data for N days, those rows are due immediately.
 *
 * This runs once at startup, before any job, and applies the rule the delete
 * paths now apply — `purge_after = deleted_at + retention` — to the rows that
 * predate it:
 *
 *  1. **No purge date at all.** `purge_after` is NULL on a deleted row. It gets
 *     the rule. (`deleted_at` itself can be NULL on the oldest rows; those are
 *     measured from now, so the repair can never erase something sooner than
 *     the retention it was given.)
 *  2. **The deletion instant, with a retention configured.** `purge_after`
 *     equals `deleted_at` while the retention is positive — an arithmetic
 *     impossibility for anything the current code wrote, so the row can only
 *     have come from a delete that ignored the setting. It gets the rule too.
 *     With a retention of zero, `purge_after = deleted_at` is exactly what the
 *     rule produces and nothing is touched.
 *
 * Both passes only ever move a purge date *later*, never sooner, and both are
 * idempotent: once repaired a row matches neither predicate, so restarting the
 * worker changes nothing.
 *
 * The tables are read out of `information_schema` rather than listed here, so
 * the repair covers every table that carries the three deletion columns —
 * including ones added after this was written, and including those owned by
 * other modules' migrations.
 *
 * **Why here, and not in a migration or in the purge itself.** A migration
 * cannot know the retention: it is application config, not schema. And having
 * [PurgeJob] treat NULL as "deleted_at + retention" would give every row two
 * purge dates — the one stored in the column and the one the job infers — so
 * the column would stop answering the question an operator asks it. Repairing
 * the column instead keeps `purge_after` the single, readable answer to "when
 * does this row go", and leaves the purge with the one predicate it has always
 * had.
 */
object PurgeScheduleRepair {

    /** Tables carrying the full three-tier deletion set, in a stable order. */
    private const val THREE_TIER_TABLES = """
        SELECT table_name
          FROM information_schema.columns
         WHERE table_schema = current_schema()
           AND column_name IN ('deleted', 'deleted_at', 'purge_after')
         GROUP BY table_name
        HAVING count(DISTINCT column_name) = 3
         ORDER BY table_name
    """

    /** Runs the repair in its own transaction. Returns the number of rows repaired. */
    fun run(): Long = transaction { repair(DeletionRetention.days()) }

    internal fun JdbcTransaction.repair(retentionDays: Int): Long {
        val tables = mutableListOf<String>()
        exec(THREE_TIER_TABLES) { rs ->
            while (rs.next()) tables.add(rs.getString(1))
        }

        // The repair has to land on exactly what the delete paths write, and
        // they add an absolute number of seconds to an `Instant`. These columns
        // are `timestamp` without a zone, written and read as wall time in the
        // JVM's zone, so adding "30 days" in the database would add 30 *calendar*
        // days — an hour out from the delete paths whenever the window crosses a
        // daylight-saving change. Lifting the value into that same zone, adding
        // the window in hours (absolute on a zoned value) and dropping it back
        // reproduces the delete paths' arithmetic exactly.
        val zone = ZoneId.systemDefault().id.replace("'", "''")
        val window = "make_interval(hours => ${retentionDays * 24})"
        fun zoned(column: String) = "(($column AT TIME ZONE '$zone') + $window) AT TIME ZONE '$zone'"
        var total = 0L
        val repaired = linkedMapOf<String, Long>()

        for (table in tables) {
            var rows = update(
                """
                UPDATE $table
                   SET purge_after = ((COALESCE(deleted_at AT TIME ZONE '$zone', now()) + $window)
                                      AT TIME ZONE '$zone')
                 WHERE deleted = true AND purge_after IS NULL
                """
            )

            if (retentionDays > 0) {
                rows += update(
                    """
                    UPDATE $table SET purge_after = ${zoned("deleted_at")}
                     WHERE deleted = true AND deleted_at IS NOT NULL AND purge_after = deleted_at
                    """
                )
            }

            if (rows > 0) repaired[table] = rows
            total += rows
        }

        if (total > 0) {
            log.warn(
                "Gave a purge date to {} soft-deleted row(s) that had none or had the deletion instant " +
                    "itself: {} — they are kept {} day(s) from when they were deleted, and the purge job " +
                    "erases them once that is up",
                total, repaired, retentionDays,
            )
        } else {
            log.info("Purge schedule repair: nothing to repair across {} three-tier table(s)", tables.size)
        }
        return total
    }

    private fun JdbcTransaction.update(sql: String): Long =
        connection.prepareStatement(sql, false).executeUpdate().toLong()
}
