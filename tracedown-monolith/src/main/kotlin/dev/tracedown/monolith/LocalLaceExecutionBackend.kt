package dev.tracedown.monolith

import dev.lacelang.executor.runScript
import dev.lacelang.validator.parse
import dev.tracedown.scheduler.dispatch.ProbeExecutionBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import kotlin.io.path.absolutePathString

/**
 * Executes probes in-process with the Kotlin Lace executor — the monolith's
 * replacement for external probe agents. Mirrors the agent's behavior:
 * extension activation by config variables, recovery-message config, body
 * persistence with secret redaction, and a synthetic `timeout` result when the
 * run overruns its window.
 *
 * Bodies are written under [storageRoot]/{orgId}/{serviceId}/{runTs}/ and
 * referenced as `file://` URIs, matching what the filesystem-backed agent
 * produces — the result-ingestor relocates and serves them identically.
 */
class LocalLaceExecutionBackend(
    private val storageRoot: String,
) : ProbeExecutionBackend {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun execute(request: ProbeExecutionBackend.Request): List<ProbeExecutionBackend.Execution> {
        val result = withContext(Dispatchers.IO) {
            try {
                withTimeout(request.timeoutMs.toLong() + 15_000L) {
                    runOnce(request)
                }
            } catch (e: TimeoutCancellationException) {
                log.warn("embedded probe for service {} timed out — recording timeout result", request.serviceId)
                syntheticResult("timeout", "embedded executor did not finish within ${request.timeoutMs + 15_000}ms")
            } catch (e: Exception) {
                log.error("embedded probe for service {} failed: {}", request.serviceId, e.message)
                syntheticResult("failure", e.message ?: e.javaClass.simpleName)
            }
        }
        return listOf(ProbeExecutionBackend.Execution(agentId = null, result = result, egressBytes = 0L))
    }

    private fun runOnce(request: ProbeExecutionBackend.Request): JsonObject {
        @Suppress("UNCHECKED_CAST")
        val ast = parse(request.script).toMap() as Map<String, Any?>

        val variables = JsonInterop.toPlainMap(request.variables)
            .mapValues { (_, v) -> v?.toString() }

        // Same extension policy as the probe agent.
        val extensions = mutableListOf("laceNotifications")
        if (variables["trackBaseline"] == "true") extensions.add("laceBaseline")
        if (variables["notifyRecovery"] != "false") extensions.add("laceEmitRecovery")

        val config = mutableMapOf<String, Any?>()
        if ("laceEmitRecovery" in extensions) {
            config["extensions"] = mapOf(
                "laceEmitRecovery" to mapOf(
                    // The recovery text doubles as the dispatcher-side template.
                    "recovery_message" to "\${s.name} in \${w.name}.\${p.name} recovered",
                ),
            )
        }

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
            return if (bodiesDir != null) {
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

    private fun syntheticResult(outcome: String, error: String): JsonObject = buildJsonObject {
        put("outcome", outcome)
        put("elapsedMs", 0)
        put("calls", buildJsonArray {})
        put("error", error)
    }
}
