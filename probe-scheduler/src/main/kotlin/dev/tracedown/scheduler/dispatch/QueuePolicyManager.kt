package dev.tracedown.scheduler.dispatch

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
     * @return [Acquisition] with ACQUIRED + a token, or SKIPPED/ENQUEUED
     */
    fun tryAcquire(serviceId: UUID, queuePolicy: String, timeoutMs: Int): Acquisition {
        val activeKey = "probe_active:$serviceId"
        val ttlSeconds = lockTtlSeconds(timeoutMs)
        val token = UUID.randomUUID().toString()

        val acquired = redis.set(activeKey, token, SetArgs().nx().ex(ttlSeconds))
        if (acquired != null) return Acquisition(AcquireResult.ACQUIRED, token)

        // Lock already held
        if (queuePolicy == "enqueue_once") {
            val pendingKey = "probe_pending:$serviceId"
            val alreadyPending = redis.exists(pendingKey) > 0
            if (!alreadyPending) {
                redis.set(pendingKey, "1", SetArgs().ex(ttlSeconds))
                return Acquisition(AcquireResult.ENQUEUED, null)
            }
        }

        return Acquisition(AcquireResult.SKIPPED, null)
    }

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
    fun release(serviceId: UUID, token: String): Boolean {
        val activeKey = "probe_active:$serviceId"
        val pendingKey = "probe_pending:$serviceId"

        // Atomic compare-and-delete of the lock we own, plus clearing (and
        // reporting) the pending flag in the same step. Returns:
        //   1  -> we owned the lock and a pending run was cleared
        //   0  -> we owned the lock, no pending run
        //  -1  -> we no longer own the lock (do not re-dispatch)
        val result = redis.eval<Long>(
            RELEASE_SCRIPT,
            ScriptOutputType.INTEGER,
            arrayOf(activeKey, pendingKey),
            token,
        )
        return result == 1L
    }

    /**
     * Rate limit for unverified-domain probes: at most one dispatch per
     * window. Returns true when this tick may proceed.
     */
    fun allowUnverifiedTick(serviceId: UUID, windowSeconds: Long): Boolean {
        val acquired = redis.set(
            "unverified_throttle:$serviceId", "1",
            SetArgs().nx().ex(windowSeconds),
        )
        return acquired != null
    }

    companion object {
        /** Matches AgentDispatchService's per-agent client overhead over the probe timeout. */
        const val DISPATCH_OVERHEAD_MS = 15_000L

        /** Extra headroom so the lock never lapses mid-dispatch. */
        const val SAFETY_MARGIN_MS = 15_000L

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
        const val RETRY_WINDOW_MS = 20_000L

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
         * lock outlives the dispatch it protects.
         */
        fun lockTtlSeconds(timeoutMs: Int): Long {
            val ceilingMs = timeoutMs.toLong() + DISPATCH_OVERHEAD_MS + RETRY_WINDOW_MS + SAFETY_MARGIN_MS
            return (ceilingMs + 999) / 1000 // ceil to whole seconds
        }

        /**
         * KEYS[1]=active, KEYS[2]=pending, ARGV[1]=token.
         * Delete the lock only if we still own it; clear+report pending only then.
         */
        private val RELEASE_SCRIPT = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                redis.call('del', KEYS[1])
                if redis.call('del', KEYS[2]) == 1 then
                    return 1
                else
                    return 0
                end
            else
                return -1
            end
        """.trimIndent()
    }
}
