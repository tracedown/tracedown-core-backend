package dev.tracedown.common.util

/**
 * Derives the identity of an **endpoint** from a probe script.
 *
 * An endpoint is `METHOD` + the URL *template* of a call **as the script
 * writes it** — not the URL a run resolved and not the call's position in the
 * script. The script already says which parts of an address float: Lace
 * interpolates variables inside string bodies (spec §3.5), and every one of
 * those is, by construction, the part that is allowed to differ between runs.
 * Replacing each with a placeholder leaves exactly the shape that is stable,
 * which is what a per-endpoint statistic has to be grouped by.
 *
 * Two properties this buys, both deliberate:
 *
 * - **A value never reaches a key.** These functions take a URL string and
 *   nothing else — there is no variable map in any signature, so a token
 *   sitting in a path cannot leak into a stored aggregate key, and a key does
 *   not change when a base URL does.
 * - **Two calls with the same method and template are one endpoint.** Their
 *   counts add up; the position of a call in the script is not part of its
 *   identity.
 *
 * The consequence, accepted: editing a call's URL (or renaming a variable in
 * it) starts a new endpoint, and the old one ages out of the window. History
 * stays honest rather than silently re-labelled.
 *
 * Everything here is pure — no I/O, no clock, no database.
 */
object EndpointKeys {

    /**
     * Longest a template may be. A key is `"<METHOD> <template>"`, and the
     * longest method is `DELETE` (6) plus a space, so 207 characters is the
     * worst case a key can reach — which is what the `VARCHAR(210)` columns
     * that store one are sized for.
     */
    const val MAX_TEMPLATE_CHARS = 200

    /** Method recorded when nothing says what it was — see [fallbackKey]. */
    const val UNKNOWN_METHOD = "*"

    /** Template recorded for a URL that is not a string literal. */
    const val EXPRESSION_TEMPLATE = "{expr}"

    /**
     * The scope letters the platform's variable scoping uses: a user writes
     * `$o.key` / `$w.key` / `$p.key` / `$s.key` and the scheduler rewrites each
     * to a plain Lace identifier (`$s_key`) before the script is executed.
     *
     * The rewrite is what makes a scoped reference interpolate at all — the
     * §3.5 grammar's identifier has no dot in it, so `$foo.bar` interpolates
     * `$foo` and leaves `.bar` as text. [template] therefore treats a dotted
     * name as one reference **only** for these four letters, exactly mirroring
     * the rewrite, and leaves `$foo.bar` split the way a run would see it.
     */
    private val SCOPE_LETTERS = setOf("o", "w", "p", "s")

    private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

    private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9'

    /**
     * The URL template of a call, from the body of its URL string literal.
     *
     * The input is the string's *value* — what the Lace lexer produced, with
     * its escape sequences already resolved — which is what a parsed AST
     * carries. It is also correct to hand this the raw source between the
     * quotes: the one escape that matters here, `\$`, is handled the same way
     * either side of the lexer (below).
     *
     * In order:
     *
     * 1. Every §3.5 interpolation becomes a placeholder: `$$orderId` →
     *    `{orderId}`, `$p.baseUrl` → `{p.baseUrl}`, `$token` → `{token}`,
     *    `${$host}` → `{host}`. **Values are never substituted.**
     * 2. Everything from the first `?` or `#` is dropped. The cut is made on
     *    the template, so `?page=$$n` goes with it — a query that varies per
     *    run is precisely the floating part being grouped away.
     * 3. A literal scheme and host are lower-cased (either is left alone when
     *    it contains a placeholder, since a placeholder's case is not ours to
     *    change).
     * 4. A trailing `/` is removed, unless the template is a bare `/`.
     * 5. Surrounding whitespace is trimmed and the result truncated to
     *    [MAX_TEMPLATE_CHARS].
     */
    fun template(urlStringBody: String): String {
        val interpolated = interpolationPlaceholders(urlStringBody.trim())
        val cut = interpolated.indexOfFirst { it == '?' || it == '#' }
        val path = (if (cut >= 0) interpolated.substring(0, cut) else interpolated).trim()
        val cased = lowercaseLiteralSchemeAndHost(path)
        val trimmed = if (cased.length > 1 && cased.endsWith('/')) cased.dropLast(1) else cased
        return trimmed.take(MAX_TEMPLATE_CHARS)
    }

    /**
     * The endpoint key of one parsed call: `"<METHOD> <template>"`.
     *
     * [urlNode] is the call's URL as the AST carries it. The Lace grammar's
     * `urlArg` is a `STRING`, so in practice it is always the string body and
     * always takes the [template] path; anything else is keyed
     * [EXPRESSION_TEMPLATE], which keeps this total rather than throwing if a
     * future grammar admits an expression there.
     */
    fun key(method: String?, urlNode: Any?): String {
        val verb = method?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: UNKNOWN_METHOD
        val template = if (urlNode is String) template(urlNode) else EXPRESSION_TEMPLATE
        return "$verb $template"
    }

    /**
     * The key for a step that carries none — a row ingested before endpoint
     * keys existed, or one whose script would not parse.
     *
     * Derived from the resolved `request_url`, which means the floating parts
     * have to be guessed back out of it rather than read off the script: the
     * query and fragment go, and a path segment that looks like an identifier
     * (all digits, a UUID, a long run of hex, or a long opaque token) becomes
     * `{id}`. The method is not recorded on a step, so it is
     * [UNKNOWN_METHOD] — a fallback key is never mistaken for a real one.
     *
     * The aggregation applies the same rule in SQL, over the rows it is
     * already scanning; a parity test pins the two together.
     */
    fun fallbackKey(requestUrl: String): String =
        "$UNKNOWN_METHOD ${normalizeResolvedUrl(requestUrl)}"

    /**
     * Splits a key back into its method and template halves. The inverse of
     * the `"<METHOD> <template>"` spelling [key] and [fallbackKey] produce.
     */
    fun split(key: String): Pair<String, String> {
        val space = key.indexOf(' ')
        if (space < 0) return key to ""
        return key.substring(0, space) to key.substring(space + 1)
    }

    /**
     * A resolved URL reduced to a comparable shape — the body of
     * [fallbackKey], exposed for the parity test and for callers that want the
     * template without the `* ` prefix.
     */
    fun normalizeResolvedUrl(requestUrl: String): String {
        val trimmed = requestUrl.trim()
        val cut = trimmed.indexOfFirst { it == '?' || it == '#' }
        val path = (if (cut >= 0) trimmed.substring(0, cut) else trimmed).trim()
        val authority = authorityPrefixLength(path)
        val head = path.substring(0, authority)
        val tail = path.substring(authority)
        return (head + maskIdSegments(tail)).take(MAX_TEMPLATE_CHARS)
    }

    // ── §3.5 interpolation ──────────────────────────────────────────────

    /**
     * Rewrites every §3.5 reference in a string body to `{name}`, leaving
     * everything else byte for byte.
     *
     * The forms, in the order the canonical executor matches them:
     * `${$$run}`, `${$run}`, `$$run`, `$run`, each name being
     * `[A-Za-z_][A-Za-z0-9_]*` with an optional `.name` suffix for the four
     * scope letters (see [SCOPE_LETTERS]). A `$` that begins none of them is
     * literal text: `$1`, `$/`, a trailing `$`, `$$` before a digit.
     *
     * `\$` is a §2.2 escape that yields a literal `$` but, per §3.5,
     * **does not prevent interpolation** — the executor scans the unescaped
     * value, so `"\$name"` interpolates `name` just as `"$name"` does. The
     * backslash is therefore dropped here and the `$` behind it scanned
     * normally, which also makes this function agree with itself whether it is
     * handed raw source or a lexed value.
     */
    private fun interpolationPlaceholders(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length && s[i + 1] == '$') {
                // The escape is consumed; the `$` behind it is still a `$`.
                i++
                continue
            }
            if (c != '$') {
                out.append(c)
                i++
                continue
            }
            val match = matchReference(s, i)
            if (match == null) {
                out.append('$')
                i++
            } else {
                out.append('{').append(match.name).append('}')
                i = match.end
            }
        }
        return out.toString()
    }

    private class Reference(val name: String, val end: Int)

    /** Matches one reference starting at the `$` in [i], or null if that `$` is text. */
    private fun matchReference(s: String, i: Int): Reference? {
        // `${$name}` / `${$$name}` — the braced forms, which exist so a name
        // can sit next to other text (`${$host}name`).
        if (i + 1 < s.length && s[i + 1] == '{') {
            var j = i + 2
            if (j >= s.length || s[j] != '$') return null
            j++
            if (j < s.length && s[j] == '$') j++
            val name = readName(s, j) ?: return null
            val after = j + name.length
            if (after >= s.length || s[after] != '}') return null
            return Reference(name, after + 1)
        }
        // `$$name`, then `$name`.
        var j = i + 1
        if (j < s.length && s[j] == '$') j++
        val name = readName(s, j) ?: return null
        return Reference(name, j + name.length)
    }

    /**
     * Reads an identifier at [start], plus a `.name` suffix when the
     * identifier is one of the scope letters. Null when no identifier begins
     * there.
     */
    private fun readName(s: String, start: Int): String? {
        if (start >= s.length || !isNameStart(s[start])) return null
        var end = start + 1
        while (end < s.length && isNameChar(s[end])) end++
        val head = s.substring(start, end)
        if (head !in SCOPE_LETTERS || end >= s.length || s[end] != '.') return head
        var tail = end + 1
        if (tail >= s.length || !isNameStart(s[tail])) return head
        tail++
        while (tail < s.length && isNameChar(s[tail])) tail++
        return s.substring(start, tail)
    }

    // ── Casing ──────────────────────────────────────────────────────────

    private val SCHEME_RE = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*)://")

    /**
     * Lower-cases the scheme and the host, each only when it survived
     * [interpolationPlaceholders] without a placeholder in it. Userinfo is
     * left alone — it is not case-insensitive, and it is not an address.
     */
    private fun lowercaseLiteralSchemeAndHost(s: String): String {
        val scheme = SCHEME_RE.find(s) ?: return s
        val schemeText = scheme.groupValues[1]
        val head = if ('{' in schemeText) "$schemeText://" else "${schemeText.lowercase()}://"
        val rest = s.substring(scheme.value.length)
        val slash = rest.indexOf('/').let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, slash)
        val at = authority.lastIndexOf('@')
        val userinfo = if (at >= 0) authority.substring(0, at + 1) else ""
        val host = if (at >= 0) authority.substring(at + 1) else authority
        val casedHost = if ('{' in host) host else host.lowercase()
        return head + userinfo + casedHost + rest.substring(slash)
    }

    // ── Fallback normalisation ──────────────────────────────────────────

    /**
     * How far into a URL the authority runs (`https://host:port`), so the
     * id-masking below only ever looks at path segments. Zero when the string
     * has no `scheme://` prefix, i.e. it is a bare path.
     */
    private fun authorityPrefixLength(s: String): Int {
        val scheme = SCHEME_RE.find(s) ?: return 0
        val rest = s.substring(scheme.value.length)
        val slash = rest.indexOf('/')
        return scheme.value.length + (if (slash < 0) rest.length else slash)
    }

    /**
     * A path segment that reads as an identifier rather than a name. Kept
     * deliberately conservative — a segment has to be all digits, UUID-shaped,
     * a long run of hex, or a long run of token characters before it is
     * masked, so `/orders` and `/v1` survive.
     *
     * Mirrored character-for-character by the SQL the aggregation runs; both
     * spellings use explicit ranges so a POSIX regex and a Java one agree.
     */
    private val ID_SEGMENT_RE = Regex(
        "^(?:[0-9]+" +
            "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" +
            "|[0-9a-fA-F]{16,}" +
            "|[A-Za-z0-9_\\-]{24,})$",
    )

    /** The path part of a resolved URL with every id-shaped segment replaced. */
    private fun maskIdSegments(path: String): String {
        if (path.isEmpty()) return path
        return path.split('/').joinToString("/") { segment ->
            if (segment.isNotEmpty() && ID_SEGMENT_RE.matches(segment)) "{id}" else segment
        }
    }
}
