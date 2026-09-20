package dev.tracedown.common.util

/**
 * [EndpointKeys.fallbackKey] written a second time, as a SQL expression.
 *
 * A step that carries no endpoint key of its own — every row ingested before
 * the column existed, and any run whose script would not parse — has one
 * derived from its resolved `request_url` instead. Two callers need that:
 * the aggregation, which files such rows under a key, and the statistics read,
 * which has to name the same key when it looks for an example URL.
 *
 * It is a SQL expression rather than a Kotlin call because on the day of an
 * upgrade *every* row in an aggregation window is key-less: labelling them in
 * the JVM would mean dragging a day of raw steps through it on every tick.
 * Being a second spelling of a rule that already exists in Kotlin, it is
 * pinned to the original by a parity test that runs a corpus through both and
 * requires them to agree character for character.
 */
object EndpointKeySql {

    /** The regex `$` anchor, kept clear of Kotlin's string templating. */
    private const val END = "\$"

    /**
     * The `scheme://authority` prefix of a URL, held aside so that a host is
     * never mistaken for an identifier.
     */
    private const val AUTHORITY = "substring(__u FROM '^[A-Za-z][A-Za-z0-9+.-]*://[^/]*')"

    /**
     * A path segment that reads as an identifier rather than a name: all
     * digits, a UUID, a long run of hex, or a long run of token characters.
     *
     * Written out in explicit ranges rather than with `\d` / `\w` shorthands,
     * which mean different things to a POSIX regex and to a Java one — the two
     * spellings of this rule have to behave alike.
     */
    private const val ID_SEGMENT =
        "[0-9]+" +
            "|[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}" +
            "|[0-9a-fA-F]{16,}" +
            "|[A-Za-z0-9_-]{24,}"

    /**
     * The key a step without one is filed under, as a SQL expression over
     * [urlExpression] (a column or any other expression yielding the resolved
     * request URL).
     *
     * Mirrors [EndpointKeys.fallbackKey] step for step:
     *
     * 1. trim, drop everything from the first `?` or `#`, trim again;
     * 2. hold the `scheme://authority` prefix aside;
     * 3. replace every id-shaped path segment with `{id}` — the lookahead is
     *    what lets two adjacent ones both match under the `g` flag, and the
     *    `^` alternative is what reaches a first segment with no `/` in front
     *    of it;
     * 4. cut to [EndpointKeys.MAX_TEMPLATE_CHARS].
     *
     * A step does not record its method, so the key is prefixed
     * [EndpointKeys.UNKNOWN_METHOD], which no key derived from a script can be.
     */
    fun fallbackKey(urlExpression: String): String {
        val sql = """
            '${EndpointKeys.UNKNOWN_METHOD} ' || left(
                coalesce($AUTHORITY, '') ||
                regexp_replace(
                    substr(__u, length(coalesce($AUTHORITY, '')) + 1),
                    '(^|/)(?:$ID_SEGMENT)(?=/|$END)',
                    '\1{id}',
                    'g'
                ),
                ${EndpointKeys.MAX_TEMPLATE_CHARS}
            )
        """.trimIndent()
        return sql.replace("__u", "btrim(regexp_replace(btrim($urlExpression), '[?#].*$END', ''))")
    }

    /** Everything from the first `?` or `#` dropped, as a SQL expression. */
    fun withoutQuery(urlExpression: String): String =
        "regexp_replace($urlExpression, '[?#].*$END', '')"
}
