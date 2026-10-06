package dev.tracedown.common.variables

/**
 * Reads variable references out of probe-script text the way Lace reads them
 * (spec §2.2, §3.5), and the platform's scoped references on top.
 *
 * Every place that has to know what a reference in a script will become uses
 * this one reader: the scheduler's rewrite of `$p.key` to `$p_key`, the
 * endpoint templates, and both target policies at save and at dispatch. Two
 * readers disagreeing about where a reference ends is how a host one of them
 * judged differs from the host the agent is sent to.
 *
 * The forms, in the order the canonical executor matches them: `${$$name}`,
 * `${$name}`, `$$name`, `$name`. A name is `[A-Za-z_][A-Za-z0-9_]*`. For the
 * four scope letters — `o`, `w`, `p`, `s` — one `.name` suffix belongs to the
 * name (`$p.baseUrl`), because the scheduler rewrites exactly that much into a
 * single Lace identifier; any further dot is text (`$s.sub.example.com` is
 * `$s.sub` then `.example.com`). A `$` that begins none of the forms is text.
 */
object ScriptReferences {

    /** The scope letters of the platform's variable scoping. */
    val SCOPE_LETTERS = setOf("o", "w", "p", "s")

    /** One reference, spanning `[start, end)` of the text it was read from. */
    class Reference(
        val start: Int,
        val end: Int,
        /** The name as written: `p.baseUrl`, `token`. */
        val name: String,
        /** A run-scope reference (`$$name`), which no injected variable resolves. */
        val run: Boolean,
        /** Written in the braced form, `${$name}`. */
        val braced: Boolean,
    ) {
        /** The scope letter of a scoped reference (`p` for `$p.baseUrl`), else null. */
        val scope: String? = name.substringBefore('.', "").takeIf { '.' in name }

        /** The key of a scoped reference (`baseUrl` for `$p.baseUrl`), else null. */
        val key: String? = if (scope != null) name.substringAfter('.') else null

        /**
         * The injected variable this reference reads: `p_baseUrl` for
         * `$p.baseUrl` (the identifier the scheduler rewrites it to), the name
         * itself otherwise. Variable maps are keyed by this.
         */
        val variable: String = if (scope != null) "${scope}_$key" else name
    }

    /** Every reference in [text], in order. */
    fun find(text: String): List<Reference> {
        val out = mutableListOf<Reference>()
        var i = 0
        while (i < text.length) {
            if (text[i] != '$') {
                i++
                continue
            }
            val ref = match(text, i)
            if (ref == null) {
                i++
            } else {
                out += ref
                i = ref.end
            }
        }
        return out
    }

    /**
     * [text] with each reference replaced by what [transform] returns for it;
     * a null keeps the reference as written.
     */
    fun replace(text: String, transform: (Reference) -> String?): String {
        val refs = find(text)
        if (refs.isEmpty()) return text
        val out = StringBuilder(text.length)
        var at = 0
        for (ref in refs) {
            val replacement = transform(ref) ?: continue
            out.append(text, at, ref.start).append(replacement)
            at = ref.end
        }
        return out.append(text, at, text.length).toString()
    }

    /**
     * The value of a string literal's body, as the Lace lexer produces it
     * (§2.2): `\"`, `\\`, `\n`, `\t`, `\r` and `\$` resolved. Anything else
     * after a backslash is kept as written. Interpolation is applied to this
     * value, so `\$name` still interpolates `name`.
     */
    fun unescape(body: String): String {
        if ('\\' !in body) return body
        val out = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                val resolved = when (body[i + 1]) {
                    '"' -> '"'
                    '\\' -> '\\'
                    'n' -> '\n'
                    't' -> '\t'
                    'r' -> '\r'
                    '$' -> '$'
                    else -> null
                }
                if (resolved != null) {
                    out.append(resolved)
                    i += 2
                    continue
                }
            }
            out.append(c)
            i++
        }
        return out.toString()
    }

    private fun isNameStart(c: Char) = c == '_' || c in 'a'..'z' || c in 'A'..'Z'

    private fun isNameChar(c: Char) = isNameStart(c) || c in '0'..'9'

    /** The reference starting at the `$` in [i], or null if that `$` is text. */
    private fun match(s: String, i: Int): Reference? {
        if (i + 1 < s.length && s[i + 1] == '{') {
            var j = i + 2
            if (j >= s.length || s[j] != '$') return null
            j++
            val run = j < s.length && s[j] == '$'
            if (run) j++
            val name = readName(s, j) ?: return null
            val after = j + name.length
            if (after >= s.length || s[after] != '}') return null
            return Reference(i, after + 1, name, run, braced = true)
        }
        var j = i + 1
        val run = j < s.length && s[j] == '$'
        if (run) j++
        val name = readName(s, j) ?: return null
        return Reference(i, j + name.length, name, run, braced = false)
    }

    /** An identifier at [start], plus one `.name` suffix after a scope letter. */
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
}
