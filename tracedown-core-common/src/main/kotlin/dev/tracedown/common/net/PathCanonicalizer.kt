package dev.tracedown.common.net

/**
 * Canonicalizes a request path so that path-based classification cannot be
 * dodged by an equivalent spelling.
 *
 * It percent-decodes each segment, drops empty segments (collapsing `//` and a
 * trailing slash), and rejects any path carrying a dot-segment (`.` or `..`, in
 * any encoding) by returning null. The motivating case: `//api/v1/auth/login`
 * and `/api/v1/auth/login` route to the same handler, but a classifier reading
 * the raw URI sees two different strings and can put the doubled-slash form in a
 * more permissive bucket than the real one.
 *
 * Generic and edition-agnostic: it owns no route vocabulary and makes no policy
 * decision. Callers decide what a canonical path — or a null (rejected) one —
 * means for them.
 */
object PathCanonicalizer {

    /**
     * The canonical form of [rawPath]: query and fragment stripped, a leading
     * `/`, then the percent-decoded, non-empty segments joined by `/`. Returns
     * null when any segment decodes to a dot-segment or carries a malformed
     * `%`-escape.
     */
    fun canonicalize(rawPath: String): String? {
        // Only the path participates in routing; drop query and fragment.
        val pathOnly = rawPath.substringBefore('?').substringBefore('#')
        val out = ArrayList<String>()
        for (raw in pathOnly.split('/')) {
            if (raw.isEmpty()) continue
            val decoded = percentDecode(raw) ?: return null
            if (decoded == "." || decoded == "..") return null
            out.add(decoded)
        }
        return "/" + out.joinToString("/")
    }

    /** Decodes `%XX` escapes; returns null on a malformed one. `+` is left as-is. */
    private fun percentDecode(segment: String): String? {
        if ('%' !in segment) return segment
        val sb = StringBuilder(segment.length)
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%') {
                if (i + 2 >= segment.length) return null
                val hi = hexValue(segment[i + 1])
                val lo = hexValue(segment[i + 2])
                if (hi < 0 || lo < 0) return null
                sb.append(((hi shl 4) or lo).toChar())
                i += 3
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }
}
