package dev.tracedown.notifications.templates

/**
 * Renders notification templates by interpolating `${var}` placeholders.
 *
 * Supports escape sequences:
 * - `\${` renders as literal `${`
 * - `\` anywhere else is a literal backslash
 *
 * Unknown variables render as empty string.
 */
object TemplateRenderer {

    /**
     * Renders a JSON template: `${var}` values are JSON-string-escaped so
     * quotes/newlines in notification text can't break the document. The
     * variables sit inside JSON string literals, so escaping for that
     * context is always correct.
     */
    fun renderJson(template: String, vars: Map<String, String>): String {
        val escaped = vars.mapValues { (_, value) -> jsonEscape(value) }
        return render(template, escaped)
    }

    private fun jsonEscape(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Renders template to plaintext by replacing `${var}` with values from the vars map.
     */
    fun render(template: String, vars: Map<String, String>): String {
        val sb = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '\\' && i + 1 < template.length && template[i + 1] == '$'
                && i + 2 < template.length && template[i + 2] == '{'
            ) {
                // Escaped: \${ → literal ${
                sb.append("\${")
                i += 3
            } else if (ch == '$' && i + 1 < template.length && template[i + 1] == '{') {
                // Variable reference: ${varName}
                val closeBrace = template.indexOf('}', i + 2)
                if (closeBrace == -1) {
                    // Unclosed brace — output as literal
                    sb.append(ch)
                    i++
                } else {
                    val varName = template.substring(i + 2, closeBrace)
                    sb.append(vars[varName] ?: "")
                    i = closeBrace + 1
                }
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * Renders template to HTML with variable values wrapped in styled spans.
     *
     * Two-pass approach:
     * 1. Replace `${var}` with `<span class="var">VALUE</span>`
     * 2. HTML-escape the values inside spans for safety
     */
    fun renderHtml(template: String, vars: Map<String, String>): String {
        val sb = StringBuilder(template.length)
        var i = 0
        while (i < template.length) {
            val ch = template[i]
            if (ch == '\\' && i + 1 < template.length && template[i + 1] == '$'
                && i + 2 < template.length && template[i + 2] == '{'
            ) {
                sb.append("\${")
                i += 3
            } else if (ch == '$' && i + 1 < template.length && template[i + 1] == '{') {
                val closeBrace = template.indexOf('}', i + 2)
                if (closeBrace == -1) {
                    sb.append(escapeHtml(ch.toString()))
                    i++
                } else {
                    val varName = template.substring(i + 2, closeBrace)
                    val value = vars[varName] ?: ""
                    sb.append("<span class=\"var\">")
                    sb.append(escapeHtml(value))
                    sb.append("</span>")
                    i = closeBrace + 1
                }
            } else {
                sb.append(escapeHtml(ch.toString()))
                i++
            }
        }
        return sb.toString()
    }

    // A newline becomes <br>: HTML collapses it to a space, which ran the
    // coalesced messages of one run together on a single line.
    private fun escapeHtml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("\n", "<br>\n")
}
