package dev.tracedown.scheduler.results

import dev.tracedown.common.util.EndpointKeys
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Names the endpoint every call in a script belongs to, in the script's own
 * order.
 *
 * ## Why here and not at ingest
 *
 * An endpoint key is a property of the *script*, not of the result: it is the
 * URL as written, with the parts that float left as placeholders. Only the
 * dispatcher holds the script that a given run was actually handed, so only the
 * dispatcher can key a run's calls without a race — someone editing a service's
 * script while a probe is in flight would otherwise have the ingestor reading a
 * newer script than the one that produced the result in front of it, and the
 * keys would describe calls that never ran.
 *
 * ## Why static order is enough
 *
 * The Lace grammar's `script` is `call+` — a flat sequence. There is no loop,
 * no branch and no call nested in another construct, so the n-th call in the
 * source is the n-th call executed. A run that stops early (a failed `.expect`)
 * produces a *prefix* of the calls, which leaves index alignment intact; the
 * keys are simply longer than the result's `calls` array, and the extra ones
 * are dropped at ingest. Should the grammar ever grow a construct in which
 * static order is not execution order, this is the function that has to
 * change — it would then have to return null for such a script and let the
 * fallback name those endpoints from their resolved URLs.
 *
 * ## Failure
 *
 * A script that does not parse yields null, never an exception and never a
 * failed dispatch: a probe that runs is worth more than a statistic about it.
 * Those runs are keyed by the aggregation's fallback instead.
 *
 * Results are cached per script text, because the same handful of scripts are
 * parsed on every tick of every service.
 */
object ScriptEndpointKeys {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Distinct scripts remembered. One entry is a short list of short strings,
     * and an install has far fewer distinct scripts than services, so this is
     * only a guard against a pathological churn of one-off scripts.
     */
    private const val MAX_CACHED_SCRIPTS = 2_000

    /** Digest of the script text → its keys, or an empty list for "does not parse". */
    private val cache = ConcurrentHashMap<String, List<String>>()

    /**
     * The endpoint key of each call in [script], in source order, or null when
     * the script does not parse.
     *
     * [script] is the service's script as it is written — before the scheduler
     * rewrites scoped references (`$p.baseUrl` → `$p_baseUrl`) for the
     * executor. Both spellings describe the same calls in the same order, and
     * the written one is what a person reads back off a statistic.
     */
    fun keysFor(script: String): List<String>? {
        if (script.isBlank()) return null
        val keys = cache.getOrPut(digest(script)) {
            if (cache.size >= MAX_CACHED_SCRIPTS) cache.clear()
            derive(script)
        }
        return keys.ifEmpty { null }
    }

    /** Test seam: drops everything remembered. */
    fun clearCache() = cache.clear()

    @Suppress("UNCHECKED_CAST")
    private fun derive(script: String): List<String> = try {
        val ast = dev.lacelang.validator.parse(script)
        val calls = ast["calls"] as? List<Map<String, Any?>> ?: emptyList()
        calls.map { call -> EndpointKeys.key(call["method"] as? String, call["url"]) }
    } catch (e: Exception) {
        // Expected: a service can hold a script that no longer parses (the
        // grammar moved, or it was written through an interface that does not
        // validate). It still gets probed, and its calls are keyed from their
        // resolved URLs at aggregation time.
        log.debug("script does not parse — dispatching without endpoint keys: {}", e.message)
        emptyList()
    }

    private fun digest(script: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(script.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
