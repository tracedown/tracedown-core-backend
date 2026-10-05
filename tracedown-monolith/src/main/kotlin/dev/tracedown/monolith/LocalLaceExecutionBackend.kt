package dev.tracedown.monolith

import dev.lacelang.executor.runScript
import dev.lacelang.validator.parse
import dev.tracedown.scheduler.dispatch.AgentDispatchService
import dev.tracedown.scheduler.dispatch.ProbeExecutionBackend
import dev.tracedown.scheduler.dispatch.SyntheticProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.absolutePathString

/**
 * Executes probes in-process with the Kotlin Lace executor — the monolith's
 * replacement for external probe agents. Mirrors the agent's behavior:
 * extension activation by config variables, message defaults for the events
 * the extensions emit, body persistence with secret redaction, and the whole-
 * run budget: a run that outlives it is answered with a synthetic `timeout`
 * result carrying its own notification event, the way the agent answers at
 * its run budget. The budget is read the way the agent reads the dispatched
 * one (`AgentDispatchService.runBudgetMs`): clamped to the same range, and
 * omitted — the run then goes to the script's own per-call timeouts — for a
 * non-positive configured timeout, which would otherwise time every run out.
 *
 * The executor blocks and offers no cancellation, so runs go to a bounded
 * pool and the budget is a wall clock over the run's future. An over-budget
 * run keeps running after the answer has been given, until its own per-call
 * timeouts expire, and its result is discarded — the same residual gap the
 * agent documents, bounded the same way: enough such runs fill the pool, and
 * a run queued behind them that its budget expires on before a worker takes
 * it is reported as nothing learned (`agent_rejected`), which the queue
 * records as a skipped tick with its alert — never as a timeout of a target
 * that was not contacted.
 *
 * Bodies are written under [storageRoot]/{orgId}/{serviceId}/{runTs}/ and
 * referenced as `file://` URIs, matching what the filesystem-backed agent
 * produces — the result-ingestor relocates and serves them identically.
 */
class LocalLaceExecutionBackend(
    private val storageRoot: String,
    poolSize: Int = DEFAULT_POOL_SIZE,
    private val runner: (ProbeExecutionBackend.Request, AtomicBoolean) -> JsonObject = { request, abandoned ->
        LocalLaceRun(storageRoot, abandoned).runOnce(request)
    },
) : ProbeExecutionBackend {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Where embedded runs block. Bounded, so abandoned runs cannot multiply
     * without limit; daemon, so one never holds the JVM's exit.
     */
    private val probePool: ExecutorService = Executors.newFixedThreadPool(poolSize) { r ->
        Thread(r, "embedded-probe-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
    }

    override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
        val started = AtomicBoolean(false)
        val abandoned = AtomicBoolean(false)
        val run = CompletableFuture.supplyAsync({
            started.set(true)
            val result = runner(request, abandoned)
            if (abandoned.get()) {
                log.info("abandoned embedded probe for service {} has ended; its result is discarded", request.serviceId)
            }
            result
        }, probePool)

        val budgetMs = AgentDispatchService.runBudgetMs(request.timeoutMs)?.toLong()
        val startedNanos = System.nanoTime()
        val result = try {
            if (budgetMs == null) run.await() else withTimeout(budgetMs) { run.await() }
        } catch (e: TimeoutCancellationException) {
            abandoned.set(true)
            val elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000
            if (!started.get()) {
                // Never picked up: the pool is full of runs past their own
                // budgets. Nothing was learned about the target.
                log.warn(
                    "embedded probe for service {} did not start within its {}ms run budget — the probe pool is full",
                    request.serviceId, budgetMs,
                )
                return listOf(ProbeExecutionBackend.Execution(agentId = null, result = null, failureReason = "agent_rejected"))
            }
            log.warn(
                "embedded probe for service {} exceeded its {}ms run budget after {}ms — recording timeout result; " +
                    "the execution thread runs on until its per-call timeouts expire",
                request.serviceId, budgetMs, elapsedMs,
            )
            SyntheticProbeResult.timeout(
                elapsedMs,
                diagnostic = "probe did not finish within its ${budgetMs}ms run budget",
                text = "${SyntheticProbeResult.SERVICE_PREFIX} timed out: the run did not complete within ${budgetMs}ms",
            )
        } catch (e: CancellationException) {
            throw e // shutdown, not a probe outcome
        } catch (e: Exception) {
            // The check did not evaluate, so this is an `error`, not a
            // `failure`: it must not become the next run's `prev` or announce
            // a recovery on the next good run — the same as the scheduler
            // records when an external agent fails inside the run.
            log.error("embedded probe for service {} failed: {}", request.serviceId, e.message)
            SyntheticProbeResult.error(e.message ?: e.javaClass.simpleName)
        }
        return listOf(ProbeExecutionBackend.Execution(agentId = null, result = result, egressBytes = 0L))
    }

    override fun close() {
        probePool.shutdownNow()
    }

    companion object {
        /**
         * Runs in flight at most. The monolith dispatches from 8 workers; the
         * headroom is for abandoned runs waiting out their per-call timeouts.
         */
        const val DEFAULT_POOL_SIZE = 32

        private val threadCounter = AtomicInteger()
    }
}

/**
 * One embedded run: parse, configure extensions, execute, persist bodies —
 * unless [abandoned] was set meanwhile, in which case the result is going to
 * be discarded and bodies nothing would ever reference are not written.
 */
internal class LocalLaceRun(private val storageRoot: String, private val abandoned: AtomicBoolean) {

    fun runOnce(request: ProbeExecutionBackend.Request): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ast = parse(request.script).toMap() as Map<String, Any?>

        val variables = JsonInterop.toPlainMap(request.variables)
            .mapValues { (_, v) -> v?.toString() }

        // Same extension policy as the probe agent.
        val extensions = mutableListOf("laceNotifications")
        if (variables["trackBaseline"] == "true") extensions.add("laceBaseline")
        if (variables["notifyRecovery"] != "false") extensions.add("laceEmitRecovery")

        val config = mutableMapOf<String, Any?>("extensions" to extensionConfig(extensions))

        val bodiesDir = if (request.allowBodySave) {
            Files.createTempDirectory("lace-bodies-").absolutePathString()
        } else null

        try {
            val raw = runScript(
                ast = ast,
                scriptVars = variables,
                prev = request.prev?.let { JsonInterop.toPlainMap(it) },
                bodiesDir = bodiesDir,
                activeExtensions = extensions,
                config = config,
            )
            val result = JsonInterop.toJsonObject(raw)
            return if (bodiesDir != null && !abandoned.get()) {
                persistBodies(result, bodiesDir, request)
            } else result
        } finally {
            bodiesDir?.let { File(it).deleteRecursively() }
        }
    }

    /**
     * Moves saved response bodies from the executor's temp dir into the
     * storage root and rewrites each `bodyPath` to a `file://` URI. Secret
     * plaintexts are masked out of the body bytes before they touch storage —
     * a monitored endpoint that reflects a credential never lands it on disk.
     */
    companion object {
        /**
         * Message defaults for the events the extensions emit, as dispatcher-
         * side templates: the bundled per-call timeout text is a bare "Request
         * timed out", which names neither the service nor the call. No "after
         * Nms": the dispatcher's `${ms}` is the whole run's elapsed time when
         * the call has no response, which is wrong past one call. The recovery
         * text is composed by the dispatcher itself (it alone knows the
         * downtime), so that one only shapes what the raw result shows.
         */
        internal fun extensionConfig(extensions: List<String>): Map<String, Any?> {
            val config = mutableMapOf<String, Any?>(
                "laceNotifications" to mapOf(
                    "timeout_message" to "${SyntheticProbeResult.SERVICE_PREFIX} call to \${url} timed out",
                ),
            )
            if ("laceEmitRecovery" in extensions) {
                config["laceEmitRecovery"] = mapOf(
                    "recovery_message" to "${SyntheticProbeResult.SERVICE_PREFIX} recovered",
                )
            }
            return config
        }
    }

    private fun persistBodies(
        result: JsonObject,
        bodiesDir: String,
        request: ProbeExecutionBackend.Request,
    ): JsonObject {
        val runDir = File(storageRoot, "${request.orgId}/${request.serviceId}/${System.currentTimeMillis()}")
        var moved = false

        val calls = result["calls"] ?: return result
        val rewritten = buildJsonArray {
            (calls as? kotlinx.serialization.json.JsonArray)?.forEach { call ->
                val callObj = call as? JsonObject ?: run { add(call); return@forEach }
                val response = callObj["response"] as? JsonObject ?: run { add(call); return@forEach }
                val bodyPath = (response["bodyPath"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                val src = bodyPath?.let { File(it) }
                if (src == null || !src.isFile || !src.absolutePath.startsWith(bodiesDir)) {
                    add(call); return@forEach
                }
                runDir.mkdirs()
                val dest = File(runDir, src.name)
                dest.writeBytes(redact(src.readBytes(), request.secretValues))
                moved = true
                add(JsonObject(callObj + ("response" to JsonObject(
                    response + ("bodyPath" to kotlinx.serialization.json.JsonPrimitive("file://${dest.absolutePath}")),
                ))))
            }
        }
        if (!moved) return result
        return JsonObject(result + ("calls" to rewritten))
    }

    /** Byte-exact masking, longest secret first so substrings can't survive. */
    private fun redact(bytes: ByteArray, secrets: Set<String>): ByteArray {
        if (secrets.isEmpty()) return bytes
        var text = String(bytes, Charsets.ISO_8859_1)
        secrets.sortedByDescending { it.length }.forEach { secret ->
            if (secret.isNotEmpty()) {
                text = text.replace(String(secret.toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1), "*****")
            }
        }
        return text.toByteArray(Charsets.ISO_8859_1)
    }
}
