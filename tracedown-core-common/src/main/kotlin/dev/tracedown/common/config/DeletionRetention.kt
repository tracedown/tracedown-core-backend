package dev.tracedown.common.config

import java.time.Instant

/**
 * How long a soft-deleted row is kept before the purge job may erase it.
 *
 * Three-tier deletion writes three columns — `deleted`, `deleted_at` and
 * `purge_after` — and the purge job reads exactly one of them: it erases a row
 * once `purge_after` is in the past. So `purge_after` is the whole answer to
 * "when does this go", and every delete path has to write it.
 *
 * Until this existed each path decided for itself. Some stamped
 * `deleted_at + retention`, some stamped the deletion instant (erasing within
 * minutes whatever the operator had configured), and some left the column NULL
 * (never purgeable at all, so the row outlived the delete indefinitely). Three
 * answers to one question, drifting further apart with every new delete
 * endpoint.
 *
 * There is now one answer, and one place that computes it:
 *
 *     purge_after = deleted_at + retention
 *
 * applied to the row the user deleted *and* to every row the delete carries
 * down with it. With a retention of N days nothing is erased before N days;
 * with the default of zero — [DEFAULT_RETENTION_DAYS], what a self-hosted
 * install ships with — deleted means deleted and the row is purgeable at once.
 *
 * The value is the operator's (`systemLimits.purgeRetentionDays`,
 * `PURGE_RETENTION_DAYS`), wired once at startup by every service that either
 * deletes rows or purges them. Until [init] runs the default stands, which is
 * the conservative reading: a test or a tool that never configures it behaves
 * exactly like the install default.
 */
object DeletionRetention {

    /** Retention used until [init] runs: none — a deleted row is purgeable at once. */
    const val DEFAULT_RETENTION_DAYS: Int = 0

    private const val SECONDS_PER_DAY: Long = 86_400L

    @Volatile
    private var retentionDays: Int = DEFAULT_RETENTION_DAYS

    /**
     * Wires the operator's configured retention. A negative value is ignored as
     * misconfiguration: "keep forever" is not a thing a deleted row can be, and
     * a purge date before the deletion would erase on the next run.
     */
    fun init(days: Int) {
        if (days >= 0) retentionDays = days
    }

    /** The configured retention in days — for anything that has to *say* it. */
    fun days(): Int = retentionDays

    /** The configured retention in seconds. */
    fun seconds(): Long = retentionDays * SECONDS_PER_DAY

    /**
     * The purge date for a row deleted at [deletedAt].
     *
     * [extraGraceSeconds] is for a deletion nobody asked for — an account that
     * lost its last membership, say, held open so a re-invite can revive it.
     * Both windows are floors on the same row, so the later of the two wins:
     * the grace never shortens the operator's retention, and the retention
     * never shortens a grace window the product promised.
     */
    fun purgeAfter(deletedAt: Instant, extraGraceSeconds: Long = 0L): Instant =
        deletedAt.plusSeconds(maxOf(seconds(), extraGraceSeconds))
}
