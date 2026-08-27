package dev.tracedown.worker.jobs

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/** A recurring background job executed on a fixed interval. */
interface ScheduledJob {
    /** Human-readable name for logging. */
    val name: String
    /** Interval between executions in seconds. */
    val intervalSeconds: Long
    /** Executes one iteration of the job. */
    suspend fun execute()
}

private val log = LoggerFactory.getLogger("dev.tracedown.worker.jobs.JobRunner")

/**
 * Launches a [ScheduledJob] as a coroutine that loops until cancelled.
 *
 * Every job in this service shares one process and one heap, so a job that dies
 * takes nothing with it only if the loop actually survives what killed it.
 * `Exception` alone did not cover that: an `Error` — an `OutOfMemoryError` out
 * of a job that read too much, a `StackOverflowError`, a `NoClassDefFoundError`
 * from a lazily-loaded class — escaped the catch and ended the coroutine. Under
 * a `SupervisorJob` that is silent by design: the siblings keep running, and the
 * job that died never runs again for the lifetime of the process. Retention
 * stops, or aggregation stops, and nothing says so.
 *
 * So the loop catches [Throwable] and continues. It backs off for the job's own
 * interval first, which is the property that makes this safe rather than a spin:
 * whatever failed gets time to clear (a heap freed by the collector, a peer that
 * came back) before the next attempt. [kotlinx.coroutines.CancellationException]
 * is re-thrown untouched — shutdown must still stop the loop.
 */
fun CoroutineScope.launchJob(job: ScheduledJob): Job = launch {
    log.info("{}: started (interval={}s)", job.name, job.intervalSeconds)
    while (isActive) {
        try {
            job.execute()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log.error("{}: execution failed — retrying in {}s", job.name, job.intervalSeconds, e)
        }
        delay(job.intervalSeconds * 1000)
    }
}
