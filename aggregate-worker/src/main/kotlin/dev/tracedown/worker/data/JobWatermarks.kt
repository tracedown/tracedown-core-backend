package dev.tracedown.worker.data

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.time.Instant

/**
 * How far a resumable job has processed.
 *
 * Only this service reads or writes these, so the table object lives here
 * rather than in the shared models. Both accessors expect to be called inside
 * an existing transaction: the watermark must advance in the *same* transaction
 * as the work it describes, or a crash between the two either loses the window
 * (advanced without the work) or repeats it forever (work without the advance).
 */
object JobWatermarks : Table("job_watermarks") {
    val jobName = varchar("job_name", 64)
    val watermark = timestamp("watermark")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(jobName)

    /** The recorded watermark for [job], or null if it has never run. */
    fun read(job: String): Instant? =
        selectAll().where { jobName eq job }.firstOrNull()?.get(watermark)

    /**
     * Records [mark] for [job].
     *
     * Monotonic: a lower value than the stored one is ignored, so a run that
     * planned a narrower window (a shortened retention, a clock stepping back)
     * can never rewind the job and cause the same buckets to be rebuilt
     * indefinitely.
     */
    fun write(job: String, mark: Instant) {
        val existing = selectAll().where { jobName eq job }.firstOrNull()
        val now = Instant.now()
        if (existing == null) {
            insert {
                it[jobName] = job
                it[watermark] = mark
                it[updatedAt] = now
            }
        } else if (mark.isAfter(existing[watermark])) {
            update({ jobName eq job }) {
                it[watermark] = mark
                it[updatedAt] = now
            }
        }
    }
}
