package dev.tracedown.scheduler.dispatch

import dev.tracedown.common.runs.RunBounds
import io.lettuce.core.ScriptOutputType
import io.lettuce.core.SetArgs
import io.lettuce.core.api.sync.RedisCommands
import java.util.UUID

/**
 * Manages probe execution concurrency via Redis flags.
 *
 * The ``probe_active:{serviceId}`` flag serves dual purpose:
 * 1. Prevents concurrent execution of the same service probe (queue policy)
 * 2. Acts as a distributed lock across scheduler replicas — only the
 *    instance that acquires the flag dispatches
 *
 * The lock carries a **random per-acquire token** as its value, and is released
 * with a compare-and-delete: a replica only ever deletes a lock it still owns.
 * Without that, a slow dispatch whose lock had already expired (TTL) would, on
 * release, blindly ``DEL`` the lock a *different* replica had since acquired —
 * letting two replicas dispatch the same service at once (double probe, double
 * usage attribution). The TTL is likewise sized to the actual dispatch ceiling
 * (probe timeout + agent overhead + the agent-level retry window + margin), not
 * an unrelated constant, so the lock cannot lapse mid-dispatch — this class is
 * the single place that budget is defined, and the dispatch path reads it from
 * here rather than keeping its own copy.
 *
 * Queue policies:
 * - ``skip``: if active flag exists, skip this run
 * - ``enqueue_once``: if active, set a pending flag; on release, caller
 *   checks pending and re-dispatches
 *
 * A run somebody asked for under an id rides on the pending flag: its id is
 * kept beside it (``probe_pending_run:{serviceId}``) and handed back with the
 * pending run ([releaseWithPending]), so the run that follows is filed under it.
 */
class QueuePolicyManager(private val redis: RedisCommands<String, String>) {

    enum class AcquireResult { ACQUIRED, SKIPPED, ENQUEUED }

    /**
     * Result of an acquire attempt. [token] is non-null only when [result] is
     * [AcquireResult.ACQUIRED]; it must be handed back to [release] so the lock
     * is only deleted by its owner.
     */
    data class Acquisition(val result: AcquireResult, val token: String?)

    /**
     * Attempts to acquire the execution lock for a service.
     *
     * @param serviceId the service to probe
     * @param queuePolicy "skip" or "enqueue_once"
     * @param timeoutMs the probe request timeout the dispatch will use; the lock
     *   TTL is derived from it so the lock outlives the whole dispatch
     * @param runId the id a run somebody asked for is filed under, when it
     *   has one. Under `enqueue_once` it becomes the pending run's id — also
     *   when the pending run was already set by a scheduled tick, which this
     *   run then answers for — unless another run's id is already waiting
     *   there, in which case this run is SKIPPED.
     * @return [Acquisition] with ACQUIRED + a token, or SKIPPED/ENQUEUED
     */
    fun tryAcquire(serviceId: UUID, queuePolicy: String, timeoutMs: Int, runId: UUID? = null): Acquisition {
        val ttlSeconds = lockTtlSeconds(timeoutMs)
        val token = UUID.randomUUID().toString()
        // One step: take the lock, or else — under `enqueue_once` — leave a
        // pending run behind it (with this run's id, when it has one). Done as
        // two calls, the holder could release between them and the pending
        // run would wait for a release that already happened.
        val result = redis.eval<Long>(
            ACQUIRE_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf("probe_active:$serviceId", "probe_pending:$serviceId", pendingRunKey(serviceId)),
            token, ttlSeconds.toString(), queuePolicy, runId?.toString() ?: "",
        )
        return when (result) {
            1L -> Acquisition(AcquireResult.ACQUIRED, token)
            2L -> Acquisition(AcquireResult.ENQUEUED, null)
            else -> Acquisition(AcquireResult.SKIPPED, null)
        }
    }

    private fun pendingRunKey(serviceId: UUID) = "probe_pending_run:$serviceId"

    /** What a release found: whether a run is waiting to follow, and the id it was asked under, if any. */
    data class Released(val hasPending: Boolean, val pendingRunId: UUID?)

    /**
     * Releases the execution lock IF this replica still owns it (its [token]
     * still matches the stored value), then reports whether a pending run was
     * enqueued.
     *
     * If the lock is no longer ours (it expired and another replica re-acquired
     * it), we delete nothing and report no pending run — the current owner runs
     * its own release cycle.
     *
     * @return true if we owned the lock and a pending run was found (caller
     *   should re-dispatch)
     */
    fun release(serviceId: UUID, token: String): Boolean = releaseWithPending(serviceId, token).hasPending

    /**
     * [release], also handing over the id of the pending run when it was
     * asked for under one — taken in the same step, so it goes to this
     * release's re-dispatch and to no other, and no stale id stays behind.
     */
    fun releaseWithPending(serviceId: UUID, token: String): Released {
        // Atomic compare-and-delete of the lock we own, plus clearing (and
        // reporting) the pending flag and its run id in the same step.
        // Returns "-1" when we no longer own the lock, "0" when there is no
        // pending run, "1" for one without an id, or the id.
        val result = redis.eval<String>(
            RELEASE_SCRIPT,
            ScriptOutputType.VALUE,
            arrayOf("probe_active:$serviceId", "probe_pending:$serviceId", pendingRunKey(serviceId)),
            token,
        )
        return when (result) {
            null, "-1", "0" -> Released(false, null)
            "1" -> Released(true, null)
            else -> Released(true, runCatching { UUID.fromString(result) }.getOrNull())
        }
    }

    /**
     * Rate limit for unverified-domain probes: at most one dispatch per
     * window. Returns true when this tick may proceed.
     *
     * The key lives slightly *less* than the window (see
     * [unverifiedThrottleTtlSeconds]): a service scheduled at exactly the
     * minimum interval fires each tick a few milliseconds after the previous
     * one set the key, so a full-length TTL would still be running and every
     * second tick would vanish. Cron granularity is a minute, so the next
     * faster schedule is a whole minute shorter and stays throttled.
     */
    fun allowUnverifiedTick(serviceId: UUID, windowSeconds: Long): Boolean {
        val acquired = redis.set(
            "unverified_throttle:$serviceId", "1",
            SetArgs().nx().ex(unverifiedThrottleTtlSeconds(windowSeconds)),
        )
        return acquired != null
    }

    companion object {
        /** Matches AgentDispatchService's per-agent client overhead over the probe timeout. */
        const val DISPATCH_OVERHEAD_MS = RunBounds.DISPATCH_OVERHEAD_MS

        /**
         * How much earlier than the window the unverified-domain throttle key
         * expires. Absorbs the tick-versus-key race at a schedule equal to the
         * window without admitting the next faster cron schedule (a minute
         * shorter). Never lets the TTL drop below one second.
         */
        const val UNVERIFIED_THROTTLE_TOLERANCE_SECONDS = 30L

        fun unverifiedThrottleTtlSeconds(windowSeconds: Long): Long =
            (windowSeconds - UNVERIFIED_THROTTLE_TOLERANCE_SECONDS).coerceAtLeast(1L)

        /** Extra headroom so the lock never lapses mid-dispatch. */
        const val SAFETY_MARGIN_MS = RunBounds.SAFETY_MARGIN_MS

        /**
         * Wall clock the lock reserves for re-dispatching a run after an
         * agent-level failure (see [AgentFailure]).
         *
         * The retry loop may only *start* another attempt while the run is
         * still inside this window, so the worst case is one full attempt
         * begun at its very edge:
         *
         *     RETRY_WINDOW_MS + timeoutMs + DISPATCH_OVERHEAD_MS
         *
         * which is exactly what [lockTtlSeconds] covers before the safety
         * margin. Retries therefore cannot push a run past its own lock and
         * let the next tick dispatch the same service concurrently.
         */
        const val RETRY_WINDOW_MS = RunBounds.RETRY_WINDOW_MS

        /**
         * Attempts (initial + retries) allowed for one probe leg. Bounds the
         * work even where the window would allow more: an agent-level failure
         * is usually instant (connection refused), so without a count cap a
         * large dead fleet would be walked end to end every tick.
         */
        const val MAX_DISPATCH_ATTEMPTS = 3

        /**
         * TTL for the execution lock, in whole seconds. Sized to the longest a
         * run can legitimately take — including the retry chain above — so the
         * lock outlives the dispatch it protects. Defined in [RunBounds], where
         * the gateway reads it too: a run handle must not call a run lost
         * while its lock may still be held.
         */
        fun lockTtlSeconds(timeoutMs: Int): Long = RunBounds.lockTtlSeconds(timeoutMs)

        /**
         * KEYS[1]=active, KEYS[2]=pending flag, KEYS[3]=pending run id;
         * ARGV[1]=token, ARGV[2]=TTL, ARGV[3]=queue policy, ARGV[4]=run id or ''.
         * 1 acquired; 2 left pending (with the run id, if any); 0 skipped —
         * under `skip`, or when a run is already waiting (another run's id
         * already there, or, for a run with no id, any pending run).
         */
        private val ACQUIRE_SCRIPT = """
            if redis.call('set', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2]) then
                return 1
            end
            if ARGV[3] ~= 'enqueue_once' then
                return 0
            end
            if ARGV[4] ~= '' then
                if redis.call('set', KEYS[3], ARGV[4], 'NX', 'EX', ARGV[2]) then
                    redis.call('set', KEYS[2], '1', 'EX', ARGV[2])
                    return 2
                end
                return 0
            end
            if redis.call('exists', KEYS[2]) == 0 then
                redis.call('set', KEYS[2], '1', 'EX', ARGV[2])
                return 2
            end
            return 0
        """.trimIndent()

        /**
         * KEYS[1]=active, KEYS[2]=pending, KEYS[3]=pending run id, ARGV[1]=token.
         * Delete the lock only if we still own it; then clear and report the
         * pending flag and its run id.
         */
        private val RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('del', KEYS[1])
                local run = redis.call('get', KEYS[3])
                redis.call('del', KEYS[3])
                if redis.call('del', KEYS[2]) == 1 then
                    if run then
                        return run
                    end
                    return '1'
                end
                return '0'
            end
            return '-1'
        """.trimIndent()
    }
}
