package dev.tracedown.gateway.util

import dev.tracedown.common.util.EndpointKeys

/**
 * Folds the method-less endpoint keys of a window into the real ones.
 *
 * A step stored before `probe_steps.endpoint_key` existed carries no key, and
 * the aggregation names it from its resolved URL instead — `"* <template>"`,
 * method `*`, because a step does not record a method (`EndpointKeys.fallbackKey`).
 * The same call dispatched after the upgrade is named `"GET <template>"`. A
 * service whose history straddles that upgrade therefore holds both, and a
 * statistics read that lists them side by side lists one endpoint twice.
 *
 * This is the read-time repair. **Nothing stored is rewritten**: the aggregate
 * rows are the record of what was measured, the duplicate is an artefact of
 * how the older half was labelled, and a fold that got it wrong would be
 * permanent if it were written down.
 */
object EndpointFolding {

    /**
     * Which method-less keys of [keys] fold into which real key, as
     * `"* <template>"` → `"<METHOD> <template>"`. Keys absent from the result
     * stand on their own.
     *
     * A `*` key folds when **exactly one** other key in the same set has a
     * method and the same template, character for character. Zero matches means
     * the endpoint is only known by its fallback name and there is nothing to
     * fold it into; two or more means the legacy rows mix methods that cannot
     * be told apart any more (`GET /x` and `DELETE /x` are one `* /x`), and
     * picking either would move somebody else's calls. Both are left alone.
     *
     * Templates are compared literally, so what folds in practice is a script
     * written with a literal URL — `get("https://api.example.com/x")` keys to
     * `GET https://api.example.com/x` and its legacy rows to
     * `* https://api.example.com/x`. A template that still holds a placeholder
     * never equals a fallback template (`{orderId}` is not `{id}`), and that is
     * deliberate: the two are not known to name the same thing.
     */
    fun foldMap(keys: Collection<String>): Map<String, String> {
        val byTemplate = mutableMapOf<String, MutableList<String>>()
        val legacy = mutableListOf<Pair<String, String>>()
        for (key in keys) {
            val (method, template) = EndpointKeys.split(key)
            if (method == EndpointKeys.UNKNOWN_METHOD) {
                legacy += key to template
            } else {
                byTemplate.getOrPut(template) { mutableListOf() } += key
            }
        }
        if (legacy.isEmpty() || byTemplate.isEmpty()) return emptyMap()

        val out = mutableMapOf<String, String>()
        for ((key, template) in legacy) {
            // `distinct()` because a key set may legitimately repeat — two
            // spellings of the same key cannot occur, but a caller assembling
            // the set from several queries can hand us duplicates, and one key
            // listed twice is still one method.
            val candidates = byTemplate[template]?.distinct() ?: continue
            if (candidates.size == 1) out[key] = candidates.single()
        }
        return out
    }

    /**
     * [foldMap] applied to a map keyed by endpoint key, merging each method-less
     * entry into its target with [merge] and leaving the rest untouched.
     *
     * Insertion order is preserved and follows the input: a folded entry lands
     * where its target already sat, never where the `*` row did, so ordering
     * the result afterwards sees the same list it would have seen had the
     * legacy rows never been labelled apart.
     *
     * [merge] receives `(target, legacy)` and must combine **raw totals** —
     * folding averages would weight the two halves equally regardless of how
     * many calls each one saw.
     */
    fun <T> fold(totals: Map<String, T>, merge: (T, T) -> T): Map<String, T> =
        fold(totals, foldMap(totals.keys), merge)

    /**
     * [fold] against a mapping decided elsewhere.
     *
     * The previous window is folded with the **current** window's mapping, not
     * with one of its own: an endpoint whose legacy rows fold in one window and
     * not in the other would make `phases` and `previousPhases` two different
     * populations, which is precisely the comparison the chart draws.
     */
    fun <T> fold(totals: Map<String, T>, folds: Map<String, String>, merge: (T, T) -> T): Map<String, T> {
        if (folds.isEmpty()) return totals

        val out = linkedMapOf<String, T>()
        for ((key, value) in totals) {
            if (folds.containsKey(key)) continue
            out[key] = value
        }
        for ((legacyKey, targetKey) in folds) {
            val legacy = totals[legacyKey] ?: continue
            val target = out[targetKey]
            // A target that is in the fold map but not in this particular map
            // (the previous window saw the legacy rows and not the new ones)
            // keeps the legacy totals under the target's name — the point of
            // the fold is that the two are one endpoint.
            out[targetKey] = if (target == null) legacy else merge(target, legacy)
        }
        return out
    }
}
